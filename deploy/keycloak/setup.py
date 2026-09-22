#!/usr/bin/env python3
"""Create or update take-back's confidential OIDC client in Keycloak.

Uses only Keycloak's Admin REST API and Python's standard library. It is
idempotent and preserves the client secret when updating an existing client.

  KC_URL=https://sso.example.org \
  KC_ADMIN_USER=admin KC_ADMIN_PASSWORD=... \
  python3 setup.py --realm take-back \
    --redirect https://takeback.example.org/auth/callback
"""
import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request


def request(method, url, body=None, token=None, form=False):
    if body is None:
        data = None
    elif form:
        data = urllib.parse.urlencode(body).encode()
    else:
        data = json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("User-Agent", "take-back-keycloak-setup/1.0")
    req.add_header("Content-Type", "application/x-www-form-urlencoded" if form else "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req) as response:
            raw = response.read()
            return response.status, json.loads(raw) if raw else None, response.headers
    except urllib.error.HTTPError as exc:
        raw = exc.read()
        try:
            detail = json.loads(raw) if raw else None
        except json.JSONDecodeError:
            detail = raw.decode("utf-8", "replace")[:500]
        return exc.code, detail, exc.headers


def required_env(name):
    value = os.environ.get(name)
    if not value:
        sys.exit(f"set {name}")
    return value


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--realm", default="take-back")
    parser.add_argument("--client-id", default="take-back")
    parser.add_argument("--redirect", required=True)
    parser.add_argument("--create-realm", action="store_true",
                        help="create the realm when it does not exist")
    args = parser.parse_args()

    base = required_env("KC_URL").rstrip("/")
    username = required_env("KC_ADMIN_USER")
    password = required_env("KC_ADMIN_PASSWORD")
    code, tokens, _ = request("POST", base + "/realms/master/protocol/openid-connect/token", {
        "client_id": "admin-cli", "grant_type": "password",
        "username": username, "password": password,
    }, form=True)
    if code != 200:
        sys.exit(f"admin sign-in failed: HTTP {code}: {tokens}")
    token = tokens["access_token"]
    admin = base + "/admin/realms"

    code, realm, _ = request("GET", admin + "/" + urllib.parse.quote(args.realm, safe=""), token=token)
    if code == 404 and args.create_realm:
        code, realm, _ = request("POST", admin, {"realm": args.realm, "enabled": True}, token)
        if code != 201:
            sys.exit(f"create realm failed: HTTP {code}: {realm}")
        print(f"realm created: {args.realm}")
    elif code != 200:
        sys.exit(f"realm {args.realm!r} does not exist (use --create-realm): HTTP {code}")

    realm_url = admin + "/" + urllib.parse.quote(args.realm, safe="")
    query = urllib.parse.urlencode({"clientId": args.client_id})
    code, clients, _ = request("GET", realm_url + "/clients?" + query, token=token)
    if code != 200:
        sys.exit(f"list clients failed: HTTP {code}: {clients}")

    home = urllib.parse.urlunsplit(urllib.parse.urlsplit(args.redirect)[:2] + ("/", "", ""))
    client = {
        "clientId": args.client_id,
        "name": "take-back",
        "description": "Chat, calls and servers — your own.",
        "enabled": True,
        "protocol": "openid-connect",
        "publicClient": False,
        "clientAuthenticatorType": "client-secret",
        "standardFlowEnabled": True,
        "implicitFlowEnabled": False,
        "directAccessGrantsEnabled": False,
        "serviceAccountsEnabled": False,
        "redirectUris": [args.redirect],
        "webOrigins": [urllib.parse.urlunsplit(urllib.parse.urlsplit(home)[:2] + ("", "", ""))],
        "attributes": {
            "post.logout.redirect.uris": home,
            "oauth2.device.authorization.grant.enabled": "true",
        },
    }
    if clients:
        internal_id = clients[0]["id"]
        # PUT is a full representation in Keycloak. Merge with what exists so
        # unrelated administrator choices are not erased by a rerun.
        merged = dict(clients[0])
        merged.update(client)
        attrs = dict(clients[0].get("attributes") or {})
        attrs.update(client["attributes"])
        merged["attributes"] = attrs
        code, detail, _ = request("PUT", realm_url + "/clients/" + internal_id, merged, token)
        if code != 204:
            sys.exit(f"update client failed: HTTP {code}: {detail}")
        print(f"client updated: {args.client_id}")
    else:
        code, detail, headers = request("POST", realm_url + "/clients", client, token)
        if code != 201:
            sys.exit(f"create client failed: HTTP {code}: {detail}")
        internal_id = headers["Location"].rstrip("/").rsplit("/", 1)[-1]
        print(f"client created: {args.client_id}")

    code, secret, _ = request("GET", realm_url + f"/clients/{internal_id}/client-secret", token=token)
    if code != 200 or not secret.get("value"):
        sys.exit(f"read client secret failed: HTTP {code}: {secret}")

    issuer = base + "/realms/" + urllib.parse.quote(args.realm, safe="")
    print("\n--- take-back server configuration ---")
    print("TB_OIDC_BACKEND=keycloak")
    print("TB_OIDC_ISSUER=" + issuer)
    print("TB_OIDC_CLIENT_ID=" + args.client_id)
    print("TB_OIDC_CLIENT_SECRET=" + secret["value"])
    print("TB_OIDC_REDIRECT_URL=" + args.redirect)


if __name__ == "__main__":
    main()
