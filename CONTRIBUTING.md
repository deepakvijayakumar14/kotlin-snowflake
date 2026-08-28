# Contributing

Thanks for your interest in contributing to kotlin-snowflake!

## Getting started

```bash
git clone https://github.com/deepakvijayakumar14/kotlin-snowflake
cd kotlin-snowflake
./gradlew build
```

## Running tests

Unit tests (no Snowflake account needed):
```bash
./gradlew test
```

Integration tests (requires a real Snowflake account):
```bash
# Copy .env.example to .env and fill in your credentials
cp .env.example .env

./gradlew integrationTest
```

## Guidelines

- All new features should include unit tests (Kotest preferred)
- Keep public API changes backward-compatible where possible; mark breaking changes clearly in the PR description
- Run `./gradlew detekt` before opening a PR and fix any warnings
- Follow existing code style: 4-space indent, Kotlin idioms, suspend functions for anything JDBC

## Areas looking for contribution

- `Row.getObject()` generic accessor with reified type
- Named parameter support (`:name` style bindings)
- Snowflake stage upload helpers (PUT/GET)
- COPY INTO integration for bulk loads
- Kotlin Multiplatform / Native support exploration
- Improved error messages with SQL context on failure

## Opening a PR

1. Fork the repo
2. Create a branch: `git checkout -b feat/my-feature`
3. Commit your changes
4. Open a pull request against `main`

Please include in your PR description:
- What the change does
- Why it is needed
- Any breaking changes
