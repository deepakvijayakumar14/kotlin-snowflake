# Contributing

Thanks for taking an interest. This is a wrapper around the Snowflake JDBC driver with a
deliberately small API, so the bar for adding surface is high and the bar for correctness is
higher — a bug here shows up as wrong SQL, wrong bound values, or a connection that never goes
back to the pool.

## Getting set up

You need JDK 17. Everything else comes from the Gradle wrapper.

```bash
git clone https://github.com/deepakvijayakumar14/kotlin-snowflake.git
cd kotlin-snowflake
./gradlew check
```

`check` is the whole gate: it compiles, runs the unit tests, runs detekt, verifies the public ABI
against `api/kotlin-snowflake.api`, and fails if line coverage drops below the floor in
`build.gradle.kts`. If it passes locally it will pass in CI — no hosted service is in the loop, so
there is nothing to sign up for and nothing that can be down.

No Snowflake account is needed. The unit suite mocks the JDBC surface.

Useful individual tasks:

| Task | What it does |
|---|---|
| `./gradlew test` | Unit tests only |
| `./gradlew detekt` | Static analysis |
| `./gradlew apiDump` | Regenerate `api/kotlin-snowflake.api` after an intentional API change |
| `./gradlew koverHtmlReport` | Coverage report at `build/reports/kover/html/index.html` |
| `./gradlew koverLog` | Print the coverage percentage |
| `./gradlew dokkaHtml` | API documentation |
| `./gradlew integrationTest` | Live tests; needs credentials, see below |

## Integration tests

`src/integrationTest` runs against a real Snowflake account and is excluded from `check`, so
`./gradlew build` stays offline.

```bash
cp .env.example .env    # fill in SNOWFLAKE_ACCOUNT, SNOWFLAKE_USERNAME, SNOWFLAKE_PASSWORD
./gradlew integrationTest
```

Without credentials every test reports as **skipped**, which is not the same as passing. If you
change anything the mocks cannot reach — the JDBC URL, the driver class name, the key-pair auth
properties — say so in the PR, because that code is only exercised here.

## What a change needs

**A test that fails without it.** Mock the JDBC surface with mockk rather than reaching for an
account; every existing test does, including the ones covering streaming and transactions.

**Every resource closed on the failure path.** A `PreparedStatement` or `ResultSet` that only
closes on the happy path leaks a pooled connection for as long as the borrower holds it, and the
symptom is pool exhaustion far away from the cause. Prefer `use`; where a statement is built
before it can be wrapped, close it explicitly on the throw path.

**Values bound, never interpolated.** Anything derived from caller data becomes a `?` placeholder.
The DSL exists to make that the easy path — a change that concatenates a value into SQL text needs
a very good reason.

**Comments explain decisions, not syntax.** The existing comments say *why* a flow changes context
upstream of its emission, or why a rollback failure is suppressed rather than thrown. Match that.
A comment restating what the line already says is noise.

## API changes

The public ABI is pinned in `api/kotlin-snowflake.api`. `apiCheck` fails the build when it drifts,
so an intentional change means running `./gradlew apiDump` and committing the result. Treat that
diff as part of the review: it is exactly what a consumer would experience.

Anything that changes behaviour for existing callers — even without changing a signature — needs a
`CHANGELOG.md` entry under **Changed** marked `**Breaking:**`, saying what used to happen and what
happens now.

## Documentation

README snippets are compiled and executed by `ReadmeSnippetsCompileCheck`. If you add or change an
example in the README, add or change the matching snippet there too, so the documentation cannot
drift away from the API. That test exists because it once did: a shipped README described reified
generic mapping that was never implemented, and a `stream()` example that called `Row` accessors
on a `Map`.

## Areas looking for contribution

- Named parameter support (`:name` style bindings)
- Snowflake stage upload helpers (PUT/GET)
- `COPY INTO` integration for bulk loads
- Typed access to `VARIANT` / `OBJECT` / `ARRAY` beyond raw JSON strings
- Improved error messages carrying the SQL and bound values on failure

## Commits and pull requests

Conventional-commit prefixes: `feat`, `fix`, `docs`, `test`, `build`, `ci`, `refactor`. Add `!`
for a breaking change (`feat!:`).

In the pull request, say what the behaviour was before and what it is after. For a bug fix, the
most useful thing you can include is the query or sequence that reproduces it.

## Releasing

Maintainers only — see [RELEASING.md](RELEASING.md).
