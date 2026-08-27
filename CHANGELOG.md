# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
