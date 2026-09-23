# Partner cart HTTP boundary

`HttpCartAdapter` is an opt-in proposed HTTP contract. **Real partner integration is unverified.** Contract tests use an in-memory HTTP transport, no real partner and no real credentials. The default `CART_MODE=sample` continues to use the transactional PostgreSQL cart. A live AI profile does not turn on partner cart mutations.

To enable a partner adapter, an operator must explicitly set `CART_MODE=partner` and `PARTNER_CART_VERIFIED=true`. Partner mode fails startup while the verification gate is false, configuration is invalid, the service credential is absent, or the API base origin is not allowlisted. Never set the verified flag merely to make a demo start.

Configuration, also declared in `.env.example` and Compose:

| Environment variable | Meaning / default |
| --- | --- |
| `CART_MODE` | `sample` by default; `partner` is opt-in |
| `PARTNER_CART_VERIFIED` | `false`; operator acknowledgement of the completed integration gate below |
| `PARTNER_CART_BASE_URL` | Required HTTPS API base including any path prefix; no query, credentials or fragment |
| `PARTNER_CART_ALLOWED_ORIGINS` | Required comma-separated exact HTTPS origins for the API and returned shop cart links; no wildcard or path |
| `PARTNER_CART_TOKEN` | Required service bearer token, supplied only through environment; never log or commit it |
| `PARTNER_CART_CONNECT_TIMEOUT_MS` | Positive connect timeout; `2000` |
| `PARTNER_CART_REQUEST_TIMEOUT_MS` | Positive response timeout; `5000` |

The HTTP client does not follow redirects. All requests send the service bearer credential and `X-Principal-Id` taken from `TrustedScope`. The cart path is always formed from that server-bound scope. A proposal naming a different cart is rejected before network access. Cart responses must identify the same cart; returned links must match an exact configured HTTPS origin, with no embedded credentials or fragment.

The boundary uses the shared `Contracts` JSON records:

| Request relative to base URL | Response |
| --- | --- |
| `GET /carts/{cartId}` | `CartSnapshot` with opaque version, decimal strings, cart ID, lines, and allowlisted HTTPS cart URL |
| `POST /carts/{cartId}/operations/{operationId}` | Body: stored `ProposalSnapshot`. Headers: `Idempotency-Key: {operationId}` and `If-Match: "{expectedCartVersion}"`. Response: durable `OperationOutcome` |
| `GET /carts/{cartId}/operations/{operationId}` | Durable `OperationOutcome`, or 404 when currently unknown |

The partner must atomically validate version, all quantities, units, conversion steps, warehouses, prices and cumulative existing-plus-added stock before adding any lines. The stable operation ID must durably deduplicate mutations and identify the same outcome across timeouts and application/partner restarts. Version tokens use `[A-Za-z0-9._-]{1,128}`; decimals remain strings.

A timeout, redirect, malformed reply, non-success HTTP status or explicit uncertain response triggers a read-only operation lookup. Even 409/412 is not treated as a confirmed failure until a valid stored outcome is retrieved. A missing or unavailable lookup returns `outcome_unknown` with no committed-cart snapshot or link. It never retries the mutation. The domain layer persists the operation before calling the adapter and supports subsequent reconciliation by operation ID. Only a validated `succeeded` outcome establishes a committed cart.

## Integration gate — NOT VERIFIED

Before changing `PARTNER_CART_VERIFIED`, record partner-specific evidence for all of the following:

- Real endpoint paths and payload/version semantics agree with this proposed boundary or are mapped in the adapter.
- Real credentials authenticate the intended service, and the partner enforces principal/cart ownership on every read, write and lookup.
- A multi-line mutation is atomic, including price/stock changes, concurrent edits, duplicate stock buckets and supported quantity conversions.
- Idempotency is durable for the entire reconciliation period and survives restarts; conditional version conflicts never partially mutate.
- Timeout after commit is recoverable through authenticated operation lookup; repeated lookup returns the original outcome.
- Cart URLs identify the same authorized server cart and are covered by an explicit HTTPS origin allowlist.

In-memory HTTP tests validate this adapter's request and response handling, gating and failure behavior. They do **not** prove partner ownership enforcement, atomicity, durable deduplication, real timeout behavior, quantity/stock semantics or operational capacity.
