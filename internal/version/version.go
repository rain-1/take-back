// Package version is the single source of truth for take-back's version and
// wire-compatibility contract.
//
// Scheme: MAJOR.MINOR.PATCH, where
//
//	MAJOR — the wire protocol / API contract. It is ALWAYS equal to Protocol
//	        below. Bump it only for a breaking change (removing or repurposing
//	        an endpoint, field, or event; changing auth). Clients whose MAJOR
//	        differs from the server's cannot talk to it and must update.
//	MINOR — new features, bug fixes, and any other backwards-compatible change.
//	        A client on the same MAJOR but older MINOR keeps working.
//	PATCH — changes with no client-visible surface at all (internal refactors,
//	        packaging, docs shipped in the binary).
//
// Because MAJOR == Protocol, you can tell at a glance whether two builds are
// compatible: 1.4.0 and 1.9.2 interoperate; 1.9.2 and 2.0.0 do not.
package version

const (
	// Version is the human-readable release of this build.
	Version = "1.11.0"

	// Protocol is the wire-contract version. It must equal the MAJOR component
	// of Version. Clients compare their own Protocol against the server's and
	// refuse/warn on mismatch.
	Protocol = 1

	// Name identifies the service in the /api/version response.
	Name = "take-back"
)
