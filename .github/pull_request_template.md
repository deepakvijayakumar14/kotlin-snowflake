## What this changes

<!-- One or two sentences. What was the behaviour before, and what is it now? -->

## Why

<!-- The problem being solved. For a bug fix, the query or sequence that reproduces it. -->

## Checklist

- [ ] `./gradlew check` passes locally
- [ ] A test fails without this change
- [ ] Every JDBC resource opened is closed on the failure path too, not only the happy one
- [ ] No test needs a live Snowflake account to pass (mock the JDBC surface instead)
- [ ] `./gradlew apiDump` re-run and committed, if the public API changed
- [ ] `CHANGELOG.md` updated, with **Breaking:** if existing callers are affected
- [ ] README snippets updated in both `README.md` and `ReadmeSnippetsCompileCheck`, if examples changed
