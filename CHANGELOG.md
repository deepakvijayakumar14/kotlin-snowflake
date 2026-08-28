# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed

- `stream()` threw `IllegalStateException` on every call. Both overloads emitted from
  inside a `withContext(config.dispatcher)` block, which violates the flow invariant that
  a `flow { }` may only emit in the context it was collected in. The JDBC work now runs
  upstream via `flowOn`. No test collected a stream, so nothing caught it: the integration
  test named "streams rows via the query DSL" called `fetch()`.
- **`or { }` generated `AND`.** The DSL rendered predicates to a flat string as they were
  declared, so a group's combinator was lost by the time it was appended. `WHERE X = ? AND
  (STATUS = ? AND STATUS = ?)` can never match. Predicates are now built as a tree that
  keeps its combinator until SQL is generated, and `and { }` was added for symmetry.
- The pool's keepalive never ran. Hikari disables a `keepaliveTime` at or beyond
  `maxLifetime`, and the hard-coded 3 hours sat well beyond the 30-minute default. The
  interval is now configurable, defaults to 5 minutes, and is validated against
  `maxLifetime`.
- `queryTimeout` was applied only to mapped and raw `query()`. `stream()`, `execute()`,
  `batch()` and every statement inside a `transaction { }` ran with no limit. All
  statements now go through one helper that applies it.
- A failed rollback replaced the exception that caused it, hiding what the transaction
  actually failed on. The original is now rethrown with the rollback failure attached as
  suppressed. `transaction` also catches `Throwable` rather than `Exception`, so an
  `Error` or a cancellation rolls back instead of leaving the transaction open on a
  connection headed back to the pool.
- `SelectBuilder` kept WHERE and HAVING bind values in one list, so declaring `having()`
  before `where()` bound them in the wrong order. They are now tracked separately and
  emitted WHERE-first, matching the generated SQL. Repeated `where()` or `having()` calls
  replace the previous clause *and* its parameters; previously the clause was replaced but
  its parameters were kept.
- Raw result maps and `Row.columnNames` reported `getColumnName()`, which drops aliases:
  `SELECT SUM(SPEND) AS TOTAL_SPEND` was keyed by the underlying column rather than by
  `TOTAL_SPEND`, which is what callers address. They now use `getColumnLabel()`, and values
  are read by index so duplicate labels do not collapse.
- A `queryTimeout` of `Duration.INFINITE` truncated to `-1` seconds, and a sub-second one
  truncated to `0`, which JDBC reads as "no limit" - the opposite of what was asked for.

### Added

- Configuration validation at construction for settings that Hikari or the driver would
  otherwise clamp or ignore: `fetchSize`, `maxSize`, `minIdle`, `connectionTimeout`,
  `idleTimeout`, `maxLifetime`, `keepaliveTime` and `queryTimeout`.
- `pool { keepaliveTime = ... }`. `Duration.ZERO` disables it.
- `and { }` inside a `where { }` block, and `raw()` for a predicate the DSL does not cover.
- `PreparedSelect.stream()` without a mapper, matching the existing `fetch()` overload.
- Unit coverage for streaming: emission across dispatchers, resource release on completion
  and on early collector cancellation, fetch size, timeout, and parameter binding.
- Unit coverage for `or`/`and` grouping, WHERE/HAVING parameter ordering, clause
  replacement, statement timeouts on every execution path, and configuration validation.
- Integration tests that collect an actual stream, exercise OR semantics, check alias
  handling, and re-borrow the pool after early cancellation to catch a leaked connection.

### Changed

- **Breaking:** `password` and `privateKeyPath` are now mutually exclusive, and a blank
  value counts as absent. Setting both previously left one silently ignored.
- **Breaking:** `SnowflakeConfig.PoolConfig` takes a `keepaliveTime`. Configure the pool
  through `pool { }` rather than constructing `PoolConfig` directly.
- **Breaking:** `or { }` now generates `OR`. Any query relying on the old `AND` output was
  matching nothing.
- README no longer claims reified-generic data class mapping, a type-safe DSL, or
  Snowflake-specific type support, none of which the library provides; it documents what it
  does provide, and what it deliberately does not.

### Known limitations

- The live integration suite has still not been run: it requires Snowflake credentials that
  are not available. The streaming and OR fixes are verified against mocked JDBC only.

## [0.2.0] - 2026-08-27

First release published to Maven Central.

### Added

- `integrationTest` source set and Gradle task for tests against a live Snowflake
  account, excluded from `check` so `./gradlew build` stays offline.
- Connectivity smoke tests that skip unless `SNOWFLAKE_ACCOUNT`, `SNOWFLAKE_USERNAME`
  and `SNOWFLAKE_PASSWORD` are set.
- Test coverage for `Row`'s null protocol, pinning the `getX()`/`wasNull()` pairing per
  accessor so a SQL NULL is never mistaken for `0`, `0.0` or `false`, and vice versa.
- Test coverage for `TransactionScope`: positional parameter binding, statement and
  result set closing, row materialization, and per-item parameter index reset in
  `batch()`.
- Test coverage for the transaction lifecycle in `SnowflakeClient.transaction`: commit
  on success, rollback and rethrow on failure, rollback when the commit itself fails,
  and `autoCommit` restored plus the connection released on every path.
- Internal pool-injecting constructor on `SnowflakeClient`, so the transaction lifecycle
  can be tested without opening a real connection. The public
  `SnowflakeClient(SnowflakeConfig)` constructor is unchanged.
- Manually triggered `Publish` workflow, defaulting to a dry run that builds and signs
  without uploading.

### Changed

- **Breaking:** Maven coordinates are now `io.github.deepakvijayakumar14:kotlin-snowflake`.
  The previous `io.kotlinsnowflake` group was never published. The Kotlin package
  namespace is unchanged, so no imports are affected.
- **Breaking:** `TransactionScope.query(sql, vararg params)` returns
  `List<Map<String, String?>>` rather than `List<Row>`. A `Row` is bound to the lifetime
  of its `ResultSet`, so results are now materialized before the set is closed.
- Publishing targets the Sonatype Central Portal. The previous OSSRH host
  (`s01.oss.sonatype.org`) has been decommissioned.
- Artifacts are signed only when a signing key is actually configured, so
  `publishToMavenLocal` and CI do not require GPG.

### Fixed

- `./gradlew build` failed outright: the `detekt` task referenced `config/detekt.yml`,
  which was absent from the repository. Added that config and resolved the issues it
  then reported.
- The javadoc jar was empty (261 bytes). `withJavadocJar()` runs the Java javadoc task,
  which has no sources in a Kotlin project; it is now built from Dokka.
- The POM `url` and all three `scm` entries pointed at a GitHub account that does not
  exist, so every link in the published metadata was broken.
- The keepalive interval, default pool size and default fetch size were unnamed numeric
  literals; they are now named constants.
- A blank signing key is treated as absent. GitHub Actions substitutes an empty string
  for an undefined secret, which previously caused the build to fail on
  `:signMavenPublication` with "no configured signatory".

### Removed

- The CI integration-test job. It required Snowflake credentials that do not exist, so
  it reported success without ever connecting. The source set and task remain for
  running the tests by hand.

### Known limitations

- The driver-level wiring in `ConnectionPool` — the JDBC URL, driver class name and
  key-pair auth properties — has not been exercised against a live Snowflake account.
  The test suite covers everything above JDBC only.

## [0.1.0] - 2026-04-14

Initial development version. Never published to Maven Central.

### Added

- Coroutine-native `query`, `stream`, `execute`, `batch` and `transaction` APIs, with all
  JDBC work dispatched off the calling thread.
- `SelectBuilder` DSL for SELECT statements, covering columns, `WHERE` predicates with
  `AND`/`OR` grouping, `GROUP BY`, `HAVING`, `ORDER BY`, `LIMIT` and `OFFSET`.
- `Row` with typed column accessors in nullable and non-null variants.
- HikariCP-backed connection pooling with Snowflake-specific defaults.
- Password and key-pair authentication.

[Unreleased]: https://github.com/deepakvijayakumar14/kotlin-snowflake/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/deepakvijayakumar14/kotlin-snowflake/releases/tag/v0.2.0
[0.1.0]: https://github.com/deepakvijayakumar14/kotlin-snowflake/releases/tag/v0.1.0
