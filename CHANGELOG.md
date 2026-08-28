# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

- **HikariCP 5.1.0 -> 7.1.0.** A transitive dependency consumers inherit, and the one bump
  here with any behavioural risk: `PoolConfig`'s validation encodes Hikari's own clamping
  thresholds, so a change to them would have silently made that validation wrong. Checked
  `validateNumerics` in 7.1.0 directly - the 30s lifetime floor, 10s idle floor, 2s leak
  threshold and `SOFT_TIMEOUT_FLOOR` are unchanged, every setter `ConnectionPool` uses is
  still present, and the jar targets Java 11 bytecode.
- slf4j-api 2.0.12 -> 2.0.18. Patch bump of the logging facade, also transitive.
- Build tooling: Dokka 1.9.20 -> 2.2.0, Kover 0.9.1 -> 0.9.9,
  binary-compatibility-validator 0.16.3 -> 0.18.1, detekt 1.23.6 -> 1.23.8. `check` does not
  run Dokka, so the javadoc jar was verified by hand through `publishToMavenLocal` rather
  than inferred from a green build.
- Gradle wrapper 9.3.0 -> 9.7.1.
- CI actions: checkout v4 -> v7, setup-java v4 -> v6, upload-artifact v4 -> v7. Clears the
  Node 20 deprecation warnings that were annotating every run.
- Dependabot groups split so a blocked bump stops holding safe ones. A `kotlin` group
  matching `org.jetbrains.kotlinx*` had been catching Kover and the ABI validator by their
  plugin ids alongside coroutines, and a `test-dependencies` group did the same to logback
  via Kotest. Grouping is now limited to members that genuinely must move together.

### Fixed

- `CONTRIBUTING.md` documented `./gradlew dokkaHtml`, which Dokka 2 renames to
  `dokkaGenerateHtml`. The old name survives as a disabled V1 stub that reports `SKIPPED`,
  so the documented command would have appeared to succeed while generating nothing.

### Not upgraded

Kotlin is pinned to 1.9.23, because the Maven Publish Plugin is pinned to 0.30.0 - the last
line supporting Kotlin 1.9.x. Anything whose artifacts carry Kotlin 2.2 metadata is blocked
behind that upgrade, which has to move the compiler, the publish plugin, detekt and the
compiler-options DSL together:

- kotlinx-coroutines stays at 1.8.0. It is an `api` dependency, so this is part of the
  published contract.
- Kotest stays at 5.8.1; 6.x also rewrites the spec and config API this suite uses.
- mockk stays at 1.13.10. Not its own API - 1.14+ pulls `kotlin-stdlib` 2.2 transitively,
  which resolves the whole test classpath past what the compiler can read.

The Snowflake JDBC driver stays at 3.16.0. A major bump is available, but `ConnectionPool`
is the one class with no unit coverage, so it needs the live integration suite rather than a
green `check`.

## [0.3.0] - 2026-08-28

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
- `ReadmeSnippetsCompileCheck`, which compiles and runs every README example against a mocked
  JDBC surface. The documentation errors above would not have compiled, and the DSL's documented
  `OR` output is now pinned by an assertion rather than a comment.
- Public ABI pinned in `api/kotlin-snowflake.api` via the binary-compatibility-validator plugin.
  `apiCheck` runs as part of `check` and fails on unintended drift; regenerate with
  `./gradlew apiDump`.
- Line-coverage floor enforced by Kover as part of `check`, so a coverage regression fails the
  same command that runs the tests. No hosted service is involved. Coverage is 96.7%, the floor
  90%.
  `ConnectionPool` is excluded: constructing it opens a real pool against a real account.
- CI runs one `check` task across JDK 17 and 21, uploading test results and an HTML coverage
  report as artifacts.
- Dependabot for Gradle dependencies and GitHub Actions, grouped so routine bumps arrive as one
  reviewable PR. The JDBC driver and HikariCP stay ungrouped - their behaviour is what this
  library wraps.
- Issue templates for bugs and feature requests, and a pull request template.
- `RELEASING.md`, covering Central Portal setup, the dry-run-first release procedure, and the rule
  that a change to driver-level wiring needs the live suite before it ships.
- `printVersion` Gradle task, so the publish workflow reports the version it is about to upload
  and refuses a `-SNAPSHOT`.
- Unit coverage for every `WhereBuilder` operator, `PreparedSelect`'s fetch and stream paths,
  `env()`, and the statement-preparation failure path.

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
- The README configuration reference no longer shows `password` and `privateKeyPath` set
  together, which is now rejected.
- `CONTRIBUTING.md` rewritten around `./gradlew check` as the single gate, and documents the
  resource-closing and parameter-binding rules a change is held to.
- The published POM description matches the README's positioning.

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

[Unreleased]: https://github.com/deepakvijayakumar14/kotlin-snowflake/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/deepakvijayakumar14/kotlin-snowflake/releases/tag/v0.3.0
[0.2.0]: https://github.com/deepakvijayakumar14/kotlin-snowflake/releases/tag/v0.2.0
[0.1.0]: https://github.com/deepakvijayakumar14/kotlin-snowflake/releases/tag/v0.1.0
