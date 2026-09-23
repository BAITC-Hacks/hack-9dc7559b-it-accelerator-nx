# D2 catalog services: CAT-02 / CAT-03 / CAT-04

`CatalogDataAdapter` implements D1 `CatalogPort`, `StockPort`, and `AnalogsPort` on profiles other than `contract` and `test`. The `d2` profile uses these real data services with an explicitly offline LLM. Existing D1 fixture beans remain isolated. No cart service or cart mutation endpoint is changed.

## Search and snapshots

- `GET /api/products/search?q=000001` retains the existing `items` wrapper and adds `mode` and `warnings`. Exact normalized articles bypass embeddings; absent explicit/ASCII SKU queries return `NOT_FOUND` without substitution.
- `GET /api/catalog/search?q=автомат` and `POST /api/catalog/search` return `{resultSet, appliedConstraints, mode, warnings}`. POST accepts `SearchCriteria`: query, limit (1–50), category, brand, decimal string minPrice/maxPrice, currency, allowlisted specs, exactArticle.
- `GET /api/catalog/results/{id}` and `POST /api/catalog/results/{id}/compare` with `{indices:[0,1]}` read immutable owner-scoped results, preserve original filters, and never rerun ranking. Indices are zero-based. UUIDs expire after 24 hours. D1 chat independently persists its own owner/conversation snapshots through `ChatService.saveResults`.
- Lexical matching supports one-edit typos and is bounded to 2,000 category/brand candidates. Query <=500 characters and 64 whitespace tokens; vector top-k <=100, two concurrent provider calls, queue eight, three-second query deadline. Filters are allowlisted data, never SQL. Vector space must match the published index.

The small fixture vector comparison exercises restrictive SQL filters against an exact SQL baseline with deterministic embeddings. It does **not** establish real OpenAI recall or production latency; QA-02 retains that external gate.

## Authoritative sample offers

`GET /api/products/{article}/offers` exposes per-warehouse status, freshness, error, and a D1 quantitative quote when verified. `UNKNOWN`, `ON_ORDER`, ineligible warehouse, missing source, and timeout never become a fabricated quantity. `StockPort` rejects unavailable quotes because D1's DTO has no unknown-quantity union.

The sample source writes only `sample_offers`, which D1's existing `SampleCartAdapter` already uses for atomic validation. A per-product DB lock and catalog/source version marker initialize these source rows once per imported catalog version. Repeated searches do not overwrite price/stock changes. Quotes bypass caches, and no customer-specific prices are cached. ObservedAt reflects reading the authoritative local sample source; expiry is 60 seconds. SQL has a two-second timeout and batches are limited to 50 selections.

`PATCH /api/admin/catalog/offers/{article}/{warehouse}` accepts string `{price,available,expectedVersion}` to exercise price/stock races. Only synthetic products and a verified admin identity are allowed. Source version uses optimistic comparison and changes atomically. Scope comes from D1 JWT, never request body.

Units are canonical and must match exactly; no implicit conversion. Minimum and step are checked without rounding. Different warehouses are not summed. D1 retains responsibility for existing-cart-plus-addition aggregate validation and the final conditional mutation.

`PartnerOfferClient` is an **unregistered scaffold** with verified HTTPS configuration, bounded HTTP timeouts, server-derived price context, response identity/freshness checks, and explicit failures. No real partner stock API or transaction guarantees have been verified.

## Compatibility and alternatives

`GET /api/products/{article}/analogs?quantity=20&unit=pcs` returns verified analogs and immutable options. `GET /api/catalog/options/{id}` retrieves a selected option for its owner without changing cart state.

Version `synthetic-compatibility-v1` is deliberately limited to synthetic fixtures:

| Category | Required equal attributes |
|---|---|
| breakers | poles, currentA, voltageV, curve, breakingCapacityKa |
| cables | cores, crossSectionMm2, material, voltageV, insulation |
| lamps | voltageV, base, powerW, colorTemperatureK |

Units and categories must match. Missing hard attributes require clarification; real products need partner-reviewed rules. Explanations contain matched attributes and brand differences. Each line and total quantity is validated exactly. Fixtures `000001` (12), `000002` (8), and `000003` (20) produce `PARTIAL_REPLACEMENT` 12+8 and `FULL_ALTERNATIVE` 20. Similar `000004` with 32A is excluded. No option selection or repeated lookup mutates a cart.

## Integration

Append `backend/src/main/resources/db/drafts/catalog.sql` to the next common migration after both supported V3/V4 histories. Do not change the existing histories. The draft augments stock-source metadata and adds initialization/read-snapshot tables.

Verification: `DOCKER_HOST=unix:///Users/zubanyszarylkasynov/.docker/run/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock ./gradlew test --tests 'com.hackalem.catalog.*'`. Testcontainers use isolated volumes; the shared demo database is untouched.

## Publication and certificate follow-up

Catalog publication now projects synthetic source rows into `sample_offers` **inside the same transaction** as the active-version switch. An already prepared proposal cannot confirm against an old stock/minimum/unit after a newly published import, even when no catalog read occurs between import and confirmation. Existing source versions advance; newly unknown, removed, or ineligible buckets are removed from the authoritative source. The cart implementation remains unchanged.

`GET /api/products/{article}/certificates/{certificateId}` serves the actual synthetic certificate PDF only for an authenticated visitor and a certificate belonging to that active product. File resolution uses the configured seed resource and an allowlisted fixture; arbitrary imported paths/URLs are never opened. Synthetic `ProductResponse.certificates[].url` and `SourceRef.id` contain this catalog route. Citation consumers must recognize `/api/products/.../certificates/...` and fetch it with the session bearer token; KB UUID references keep the separate KB source route.

Focused verification: catalog integration suite **10 tests passed**, including prepared-cart rejection immediately after a stock-changing import, PDF bytes for an authorized product certificate, and rejected unauthenticated/unrelated-certificate access.
