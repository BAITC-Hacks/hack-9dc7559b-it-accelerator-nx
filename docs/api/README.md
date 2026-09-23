# API v1 — D1 handoff

Canonical schema: Springdoc `/v3/api-docs`; `openapi.json` is an exported snapshot.
Money and quantities are decimal strings; UUIDs and sequence/version numbers cross the
HTTP boundary as strings. Additive optional fields are compatible within v1. Removing
fields or changing confirmation/replay semantics requires a new API version.

Bearer visitor tokens bind a principal to a server-created cart. Never send cart IDs,
owner IDs, prices or a model `confirmed` flag as authority. Fetch SSE uses the same
Authorization header as REST; tokens must never go into URLs.

Ports in `com.hackalem.domain.port` are the D2 integration boundary. Test adapters are
available only under `contract`/`test` profiles. Live missing data adapters fail closed.
Cart writes are only available to the separate HTTP confirmation gate.
