# kotlin-snowflake

[![Maven Central](https://img.shields.io/maven-central/v/io.github.deepakvijayakumar14/kotlin-snowflake.svg)](https://central.sonatype.com/artifact/io.github.deepakvijayakumar14/kotlin-snowflake)
[![CI](https://github.com/deepakvijayakumar14/kotlin-snowflake/actions/workflows/ci.yml/badge.svg)](https://github.com/deepakvijayakumar14/kotlin-snowflake/actions)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

A coroutine-friendly Kotlin/JVM wrapper around the Snowflake JDBC driver: parameterized query construction, explicit row mapping, HikariCP pooling, transactions, batching, and `Flow`-based streaming.

The official Snowflake JDBC driver works, but it was designed for Java. This library wraps it with a Kotlin-first API: `suspend` functions instead of blocking calls, `Flow` for streaming large result sets, a builder DSL for configuration, and a `SELECT` DSL that binds every value as a JDBC parameter.

---

## Features

- **Coroutine-friendly** - all blocking JDBC calls are dispatched off the calling thread; results are returned via `suspend` functions or `Flow<T>`
- **Explicit row mapping** - `RowMapper<T>` lambdas with typed, null-aware column accessors. No reflection, no annotations, no surprises
- **Query DSL** - build `SELECT` statements without concatenating values into SQL; every value is bound as a JDBC parameter
- **Connection pooling** - built-in HikariCP pool, with settings validated up front rather than silently clamped
- **Transaction support** - structured `transaction { }` blocks with automatic rollback on any failure
- **Batch operations** - efficient bulk inserts and updates
- **Statement timeouts** - a single `queryTimeout` applied to every statement the client issues

### What it is not

- **Not a type-safe schema DSL.** Tables, columns and expressions are strings. The DSL protects *values* through parameter binding; identifiers are passed through as written. If you need compile-time schema checking, use JOOQ or Exposed.
- **Not an ORM.** There is no reflective data class mapping - you write the `RowMapper`.
- **No Snowflake semi-structured types.** `VARIANT`, `OBJECT` and `ARRAY` columns are returned as raw JSON strings for you to parse.

---

## Installation

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.deepakvijayakumar14:kotlin-snowflake:0.2.0")
}
```

---

## Quick Start

### 1. Create a client

```kotlin
val snowflake = snowflake {
    account   = "myorg-myaccount"
    username  = "my_user"
    password  = env("SNOWFLAKE_PASSWORD")
    database  = "MY_DATABASE"
    schema    = "PUBLIC"
    warehouse = "COMPUTE_WH"
    role      = "MY_ROLE"

    pool {
        maxSize     = 10
        minIdle     = 2
        idleTimeout = 10.minutes
    }
}
```

### 2. Query with explicit row mapping

```kotlin
data class Campaign(val id: Long, val name: String, val status: String)

val campaigns: List<Campaign> = snowflake.query(
    "SELECT ID, NAME, STATUS FROM CAMPAIGNS WHERE ACCOUNT_ID = ?",
    accountId
) {
    Campaign(
        id     = it.long("ID"),
        name   = it.string("NAME"),
        status = it.string("STATUS")
    )
}
```

Without a mapper, `query()` returns each row as a `Map<String, String?>` keyed by column label:

```kotlin
val rows: List<Map<String, String?>> =
    snowflake.query("SELECT ID, NAME FROM CAMPAIGNS LIMIT 10")
```

### 3. Stream large result sets

Rows are pulled from Snowflake `fetchSize` at a time rather than materialized in memory. The connection is held for as long as the flow is collected, and released when collection ends - including when the collector stops early.

```kotlin
snowflake
    .stream("SELECT KEYWORD_ID, BID, IMPRESSIONS FROM KEYWORD_STATS WHERE DATE = ?", today) { row ->
        KeywordStat(row.long("KEYWORD_ID"), row.double("BID"))
    }
    .filter { it.bid > 0.10 }
    .collect { stat -> processStat(stat) }
```

The mapper-less overload streams `Map<String, String?>` instead:

```kotlin
snowflake.stream("SELECT * FROM KEYWORD_STATS").collect { row -> println(row["BID"]) }
```

### 4. Query DSL

```kotlin
val results = snowflake.select {
    columns("CAMPAIGN_ID", "SUM(SPEND) AS TOTAL_SPEND")
    from("AD_PERFORMANCE")
    where {
        "DATE" between (startDate to endDate)
        "STATUS" eq "ACTIVE"
        "ACCOUNT_ID" inList accountIds
    }
    groupBy("CAMPAIGN_ID")
    orderBy("TOTAL_SPEND" to SortOrder.DESC)
    limit(100)
}.fetch { row ->
    row.long("CAMPAIGN_ID") to row.double("TOTAL_SPEND")
}
```

Predicates in a `where { }` block are combined with `AND`. Nest an `or { }` or `and { }` group to change how a subset is joined; the group is parenthesized, so its combinator binds tighter:

```kotlin
where {
    "ACCOUNT_ID" eq accountId
    or {
        "STATUS" eq "ACTIVE"
        "STATUS" eq "PAUSED"
    }
}
// ACCOUNT_ID = ? AND (STATUS = ? OR STATUS = ?)
```

Values become `?` placeholders bound through the prepared statement. Identifiers and expressions - the table name, columns, `GROUP BY`, `HAVING`, `ORDER BY` - are passed through as written, so keep them trusted. Call `toSql()` on the result to see the generated statement.

A DSL query can be streamed as well as fetched:

```kotlin
snowflake.select { /* ... */ }.stream { row -> row.long("CAMPAIGN_ID") }.collect { ... }
```

### 5. Transactions

```kotlin
val result = snowflake.transaction {
    execute("UPDATE CAMPAIGNS SET STATUS = ? WHERE ID = ?", "PAUSED", campaignId)
    execute("INSERT INTO AUDIT_LOG (CAMPAIGN_ID, ACTION) VALUES (?, ?)", campaignId, "PAUSED")
    query("SELECT STATUS FROM CAMPAIGNS WHERE ID = ?", campaignId) { it.string("STATUS") }
        .first()
}
```

### 6. Batch inserts

```kotlin
snowflake.batch(
    sql  = "INSERT INTO KEYWORD_BIDS (KEYWORD_ID, BID, UPDATED_AT) VALUES (?, ?, ?)",
    rows = keywords
) { kw ->
    bind(kw.id, kw.bid, Instant.now())
}
```

---

## Row API

Every row returned by a query exposes typed accessors:

| Method | Return type |
|---|---|
| `row.string("COL")` | `String` |
| `row.stringOrNull("COL")` | `String?` |
| `row.long("COL")` | `Long` |
| `row.int("COL")` | `Int` |
| `row.double("COL")` | `Double` |
| `row.bigDecimal("COL")` | `BigDecimal` |
| `row.boolean("COL")` | `Boolean` |
| `row.instant("COL")` | `Instant` |
| `row.localDate("COL")` | `LocalDate` |
| `row.localDateTime("COL")` | `LocalDateTime` |
| `row.json("COL")` | `String` (raw JSON) |

Each accessor has an `OrNull` variant (`stringOrNull`, `longOrNull`, ...). The non-null form throws on a SQL `NULL` rather than returning `0`, `0.0` or `false`, which is what the underlying JDBC primitives would give you.

Columns are addressed by label, so `SELECT SUM(SPEND) AS TOTAL_SPEND` is read as `row.double("TOTAL_SPEND")`. Lookup is case-insensitive, as the JDBC spec requires.

---

## Configuration Reference

```kotlin
snowflake {
    // Required
    account   = "orgname-accountname"   // Snowflake account identifier
    username  = "my_user"
    password  = "secret"                // or key-pair auth below, but not both

    // Optional with defaults
    database  = "MY_DB"
    schema    = "PUBLIC"
    warehouse = "COMPUTE_WH"
    role      = "MY_ROLE"

    // Key-pair authentication (alternative to password)
    privateKeyPath       = "/path/to/rsa_key.p8"
    privateKeyPassphrase = env("KEY_PASSPHRASE")

    // Connection pool (HikariCP)
    pool {
        maxSize              = 10
        minIdle              = 2
        connectionTimeout    = 30.seconds
        idleTimeout          = 10.minutes
        maxLifetime          = 30.minutes
        keepaliveTime        = 5.minutes   // must stay below maxLifetime
    }

    // Statement defaults
    queryTimeout = 5.minutes         // applied to every statement; Duration.ZERO = no limit
    fetchSize    = 1000              // rows per JDBC fetch for streaming
}
```

Configuration is validated when the client is built, so a setting that HikariCP would silently clamp or ignore - a `keepaliveTime` at or beyond `maxLifetime`, a `minIdle` above `maxSize`, a sub-250ms `connectionTimeout` - fails immediately with an explanation instead of quietly not taking effect.

---

## Coroutine Dispatcher

All JDBC calls are dispatched on an internal `Dispatchers.IO` thread pool by default. You can override this:

```kotlin
snowflake {
    dispatcher = Dispatchers.IO.limitedParallelism(16)
    // ...
}
```

---

## Comparison

| | kotlin-snowflake | Raw JDBC | Exposed | JOOQ |
|---|:---:|:---:|:---:|:---:|
| `suspend` API | YES | NO | Partial | NO |
| `Flow` streaming | YES | NO | NO | NO |
| Pooling built in | YES | NO | NO | NO |
| Code gen required | NO | NO | NO | YES |
| Compile-time schema safety | NO | NO | YES | YES |
| Multi-database | NO | YES | YES | YES |

---

## Status

Everything above JDBC - the DSL, row mapping, streaming, transactions, timeouts and configuration validation - is covered by the unit suite. The driver-level wiring in `ConnectionPool` (JDBC URL, driver class name, key-pair auth properties) has not yet been exercised against a live Snowflake account; see the integration suite for the tests that would.

---

## Contributing

Contributions welcome. See [CONTRIBUTING.md](CONTRIBUTING.md).

```bash
git clone https://github.com/deepakvijayakumar14/kotlin-snowflake
cd kotlin-snowflake
./gradlew test
```

`./gradlew test` runs the unit suite offline. Integration tests run against a live Snowflake account and are excluded from `check`; copy `.env.example` to `.env`, fill in your credentials, and run `./gradlew integrationTest`. Without credentials they report as skipped.

---

## License

[MIT](LICENSE)
