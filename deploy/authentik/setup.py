#!/usr/bin/env python3
"""Configure an Authentik instance to be take-back's identity provider.

Idempotent: running it twice changes nothing the second time, so it doubles as
documentation of what the dashboard would otherwise hide. It creates one
confidential OAuth2/OIDC provider and the application in front of it, and
prints the client credentials the take-back server needs.

    AK_URL=http://127.0.0.1:9100 AK_TOKEN=... python3 setup.py [--redirect URL]...

Every take-back client (browser, phone, desktop, CLI) authenticates through the
take-back *server*, which is the only OIDC client: that keeps the client secret
off every device and means the phone and CLI never speak OAuth themselves.
"""
import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

PROVIDER_NAME = "take-back"
APP_SLUG = "take-back"

# Scopes the provider may issue. `openid` and `profile` carry the identity;
# `email` is included because Authentik users are usually keyed by it, and
# `offline_access` gives refresh tokens for long-lived phone sessions.
SCOPES = ["openid", "profile", "email", "offline_access"]


class API:
    def __init__(self, base, token):
        self.base = base.rstrip("/") + "/api/v3"
        self.token = token

    def call(self, method, path, body=None):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(self.base + path, data=data, method=method)
        req.add_header("Authorization", "Bearer " + self.token)
        req.add_header("Content-Type", "application/json")
        # Python's default user agent is blocked by Cloudflare's bot rules with
        # an HTML error page, which then fails to parse as JSON and looks like
        # an Authentik problem. Say who we are instead.
        req.add_header("User-Agent", "take-back-setup/1.0")
        try:
            with urllib.request.urlopen(req) as resp:
                return resp.status, self._json(resp.read())
        except urllib.error.HTTPError as e:
            return e.code, self._json(e.read())

    @staticmethod
    def _json(raw):
        """Parse a response, keeping the body when it isn't JSON at all."""
        if not raw:
            return None
        try:
            return json.loads(raw)
        except json.JSONDecodeError:
            text = raw.decode("utf-8", "replace").strip()
            return {"non_json_response": text[:300]}

    def get(self, path):
        code, out = self.call("GET", path)
        if code != 200:
            sys.exit(f"GET {path} failed: {code} {out}")
        return out

    def one(self, path, label):
        """The single object a query is expected to match."""
        out = self.get(path)
        results = out["results"] if "results" in out else out
        if not results:
            sys.exit(f"no {label} matched {path} — is this a stock Authentik?")
        return results[0]



PASSWORDLESS_SLUG = "takeback-passkey"


def ensure_passwordless(api):
    """Let a passkey replace the password, not just follow it.

    Stock Authentik validates a passkey *after* a password (second factor).
    Signing in with only a passkey needs a separate authentication flow, which
    the identification stage then offers as "Use a security key". Everything
    here is created once and reused; running setup again finds it.
    """
    flows = api.get(f"/flows/instances/?slug={PASSWORDLESS_SLUG}")["results"]
    if flows:
        flow = flows[0]
    else:
        code, flow = api.call("POST", "/flows/instances/", {
            "name": "take-back passkey sign-in",
            "slug": PASSWORDLESS_SLUG,
            "title": "Sign in with a passkey",
            "designation": "authentication",
            "layout": "stacked",
            # Only for people who aren't signed in yet.
            "authentication": "require_unauthenticated",
        })
        if code not in (200, 201):
            sys.exit(f"passwordless flow failed: {code} {json.dumps(flow, indent=2)}")

    # The stage that asks the browser for a passkey. "deny" rather than "skip":
    # in this flow the passkey *is* the credential, so no device means no entry
    # — skipping would wave the person through having proved nothing.
    name = "take-back passkey validation"
    stages = api.get(f"/stages/authenticator/validate/?name={name.replace(' ', '%20')}")["results"]
    body = {
        "name": name,
        "device_classes": ["webauthn"],
        "not_configured_action": "deny",
        "webauthn_user_verification": "preferred",
    }
    if stages:
        code, validate = api.call("PATCH", f"/stages/authenticator/validate/{stages[0]['pk']}/", body)
    else:
        code, validate = api.call("POST", "/stages/authenticator/validate/", body)
    if code not in (200, 201):
        sys.exit(f"passkey stage failed: {code} {json.dumps(validate, indent=2)}")

    login = api.one("/stages/user_login/?name=default-authentication-login", "login stage")

    existing = {b["stage"]: b for b in api.get(f"/flows/bindings/?target={flow['pk']}")["results"]}
    for order, stage_pk in ((10, validate["pk"]), (100, login["pk"])):
        if stage_pk in existing:
            continue
        code, out = api.call("POST", "/flows/bindings/", {
            "target": flow["pk"], "stage": stage_pk, "order": order,
            "evaluate_on_plan": True, "re_evaluate_policies": False,
            "policy_engine_mode": "any", "invalid_response_action": "retry",
        })
        if code not in (200, 201):
            sys.exit(f"binding {stage_pk} failed: {code} {json.dumps(out, indent=2)}")

    # Offer it from the normal sign-in screen.
    ident = api.one("/stages/identification/?name=default-authentication-identification",
                    "identification stage")
    if ident.get("passwordless_flow") != flow["pk"]:
        # The serializer validates the whole stage even on a PATCH, so the
        # fields it cross-checks have to be sent back with the change.
        code, out = api.call("PATCH", f"/stages/identification/{ident['pk']}/", {
            "passwordless_flow": flow["pk"],
            "user_fields": ident.get("user_fields") or ["username", "email"],
            "sources": ident.get("sources", []),
        })
        if code not in (200, 201):
            sys.exit(f"wiring passwordless flow failed: {code} {json.dumps(out, indent=2)}")
    print(f"passkey sign-in: {PASSWORDLESS_SLUG} (offered on the sign-in screen)")



def ensure_device_code(api):
    """Let the CLI sign in with a device code.

    The device grant hands the person a short code to type in on another
    machine. Authentik only serves that page when the brand names a flow to run
    for it — until then /device returns 404 *after* a successful sign-in, which
    looks like a broken deployment rather than a missing setting.
    """
    brand = api.one("/core/brands/", "brand")
    if brand.get("flow_device_code"):
        return
    auth_flow = api.one("/flows/instances/?slug=default-authentication-flow", "authentication flow")
    code, out = api.call("PATCH", f"/core/brands/{brand['brand_uuid']}/",
                         {"flow_device_code": auth_flow["pk"]})
    if code not in (200, 201):
        sys.exit(f"device code flow failed: {code} {json.dumps(out, indent=2)}")
    print("device sign-in enabled (for the tb CLI)")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--redirect", action="append", default=[],
                    help="an allowed redirect URI (repeatable)")
    args = ap.parse_args()

    url = os.environ.get("AK_URL")
    token = os.environ.get("AK_TOKEN")
    if not url or not token:
        sys.exit("set AK_URL and AK_TOKEN")
    redirects = args.redirect or ["https://takeback.chain-of-thought.org/auth/callback"]
    # Where people land after logging out: the take-back that sent them.
    post_logout = sorted({
        urllib.parse.urlunsplit(urllib.parse.urlsplit(u)[:2] + ("/", "", ""))
        for u in redirects
    })

    api = API(url, token)
    me = api.get("/core/users/me/")
    print(f"connected to {url} as {me['user']['username']}")

    # No consent screen: take-back is the same people's own application, so an
    # "allow take-back to see your profile?" prompt would be theatre.
    authz = api.one("/flows/instances/?slug=default-provider-authorization-implicit-consent",
                    "authorization flow")
    # Authentik ships two logout flows. `default-provider-invalidation-flow`
    # ends only the application's session and leaves you signed in to the
    # provider — so "log out" of take-back, then "sign in", walks straight back
    # in without asking for anything, which is wrong on a shared machine.
    # `default-invalidation-flow` ends the provider session as well. (If this
    # Authentik ever fronts other applications and you would rather log out of
    # one at a time, switch this back.)
    invalidation = api.one("/flows/instances/?slug=default-invalidation-flow",
                           "invalidation flow")
    signing = api.one("/crypto/certificatekeypairs/?has_key=true", "signing keypair")

    mappings = api.get("/propertymappings/provider/scope/")["results"]
    by_scope = {m["scope_name"]: m["pk"] for m in mappings}
    missing = [s for s in SCOPES if s not in by_scope]
    if missing:
        sys.exit(f"missing scope mappings: {missing}")

    body = {
        "name": PROVIDER_NAME,
        "authorization_flow": authz["pk"],
        "invalidation_flow": invalidation["pk"],
        "client_type": "confidential",
        # Authentik 2026.x requires the allowed grants to be stated; an empty
        # list refuses everything with "invalid_request", which reads like a
        # malformed URL rather than a provider that was never switched on.
        # authorization_code is the browser/phone flow, device_code is the CLI,
        # refresh_token keeps a phone signed in without re-prompting.
        "grant_types": ["authorization_code", "refresh_token", "urn:ietf:params:oauth:grant-type:device_code"],
        # Authentik types each URI. An `authorization` entry is where a sign-in
        # may come back to; a `logout` entry is where the person may be sent
        # after the provider ends its own session. Without the logout entry the
        # end-session request is ignored, and "log out" then "sign in" walks
        # silently back into the same account.
        "redirect_uris": (
            [{"matching_mode": "strict", "url": u, "redirect_uri_type": "authorization"}
             for u in redirects]
            + [{"matching_mode": "strict", "url": u, "redirect_uri_type": "logout"}
               for u in post_logout]
        ),
        "property_mappings": [by_scope[s] for s in SCOPES],
        "signing_key": signing["pk"],
        # The `sub` claim is what take-back stores to recognise an account
        # again. user_uuid is tied to the user record itself; the default
        # hashed_user_id is derived from AUTHENTIK_SECRET_KEY, so rotating that
        # key would silently turn everyone into strangers.
        "sub_mode": "user_uuid",
        "include_claims_in_id_token": True,
    }

    existing = api.get(f"/providers/oauth2/?name={PROVIDER_NAME}")["results"]
    if existing:
        pk = existing[0]["pk"]
        code, prov = api.call("PATCH", f"/providers/oauth2/{pk}/", body)
        action = "updated"
    else:
        code, prov = api.call("POST", "/providers/oauth2/", body)
        action = "created"
    if code not in (200, 201):
        sys.exit(f"provider {action} failed: {code} {json.dumps(prov, indent=2)}")
    print(f"provider {action}: {prov['name']} (pk {prov['pk']})")

    app_body = {
        "name": "take-back",
        "slug": APP_SLUG,
        "provider": prov["pk"],
        "meta_description": "Chat, calls and servers — your own.",
        "meta_publisher": "take-back",
        "policy_engine_mode": "any",
    }
    apps = api.get(f"/core/applications/?slug={APP_SLUG}")["results"]
    if apps:
        code, app = api.call("PATCH", f"/core/applications/{APP_SLUG}/", app_body)
        action = "updated"
    else:
        code, app = api.call("POST", "/core/applications/", app_body)
        action = "created"
    if code not in (200, 201):
        sys.exit(f"application {action} failed: {code} {json.dumps(app, indent=2)}")
    print(f"application {action}: {app['slug']}")

    ensure_passwordless(api)
    ensure_device_code(api)

    print("\n--- take-back server configuration ---")
    print("TB_OIDC_BACKEND=authentik")
    print(f"TB_OIDC_ISSUER={url.rstrip('/')}/application/o/{APP_SLUG}/")
    print(f"TB_OIDC_CLIENT_ID={prov['client_id']}")
    print(f"TB_OIDC_CLIENT_SECRET={prov['client_secret']}")
    print("\nredirect URIs allowed:")
    for u in redirects:
        print("  sign-in:  " + u)
    for u in post_logout:
        print("  log out:  " + u)


if __name__ == "__main__":
    main()
