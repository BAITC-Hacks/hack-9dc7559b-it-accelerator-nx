# D1 + CAT-01 integration

The two branches independently released different V3 migrations. Existing
databases must retain their original V3 description and checksum.

- New databases: common V1/V2, `baseline-identity/V3__identity_chat_cart.sql`,
  then `baseline-identity/V4__catalog_versions_offers_stock.sql`.
- Existing catalog V3 databases: unchanged `baseline-catalog/V3__catalog_versions_offers_stock.sql`,
  then `baseline-catalog/V4__identity_chat_cart.sql`.
- `SchemaLineageConfig` inspects existing Flyway history without writing it and
  selects the matching baseline directory. Unknown histories fail closed.
- Future V5+ migrations belong in `db/migration` and apply to both histories.
- Do not use Flyway repair, manually rewrite history, or reset the shared volume.
  Each baseline SQL is an exact byte copy of the original branch migration.

`SchemaLineageConfigTest` checks both V3 upgrades, immutable history/checksum,
preserved product IDs and idempotent re-entry. `CoreIntegrationTest` covers a
clean database and V2 upgrade, plus D1 chat/cart regressions.

Catalog admin endpoints now use AUTH-01's verified JWT principal and server
`ADMIN_PRINCIPAL_IDS` allowlist. The temporary `X-Admin-Token`/`ADMIN_API_TOKEN`
mechanism is removed. Catalog projection is named `CatalogAdminScope` to avoid
shadowing D1's `Contracts.TrustedScope` in the security package.

The legacy web ProductSearchService was removed with its obsolete controller;
the versioned CAT-01 service owns product reads. Generated SDK conflicts are
resolved by regeneration from the merged running backend.

Local checks use the isolated `hackalem-d2` Compose project, not the shared demo
database. Docker Desktop may require `DOCKER_HOST` pointing to its active socket.
Test-only `docker-java.properties` selects API 1.44 for compatibility with
[Docker 29 and the pinned Testcontainers release](https://github.com/testcontainers/testcontainers-java/issues/11211).
