# kotlin-snowflake

[![Maven Central](https://img.shields.io/maven-central/v/io.kotlinsnowflake/kotlin-snowflake.svg)](https://search.maven.org/artifact/io.kotlinsnowflake/kotlin-snowflake)
[![CI](https://github.com/deepakvijayakumar/kotlin-snowflake/actions/workflows/ci.yml/badge.svg)](https://github.com/deepakvijayakumar/kotlin-snowflake/actions)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

A coroutine-native Kotlin client for [Snowflake](https://www.snowflake.com/) with an idiomatic query DSL, type-safe row mapping, and connection pooling — built for modern Kotlin/JVM backends.

The official Snowflake JDBC driver works, but it was designed for Java. This library wraps it with a Kotlin-first API: `suspend` functions, `Flow` for streaming, data class mapping via reified generics, and a builder DSL for configuration.

---

## Features

- **Coroutine-native** - all blocking JDBC calls are dispatched off the main thread; results are returned via `suspend` functions or `Flow<T>`
- **Type-safe row mapping** - map query results directly into data classes using reified generics
- **Query DSL** - build `SELECT` statements with a type-safe Kotlin DSL; no string concatenation
- **Connection pooling** - built-in HikariCP pool configured with sane defaults for Snowflake
- **Transaction support** - structured `transaction { }` blocks with automatic rollback on exception
- **Batch operations** - efficient bulk inserts and updates
- **Zero reflection** - row mapping uses explicit `RowMapper<T>` lambdas, no magic

---

## Installation

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.kotlinsnowflake:kotlin-snowflake:0.2.0")
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

### 2. Query with type-safe mapping

```kotlin
data class Campaign(val id: Long, val name: String, val status: String)

val campaigns: List<Campaign> = snowflake.query(
    sql    = "SELECT ID, NAME, STATUS FROM CAMPAIGNS WHERE ACCOUNT_ID = ?",
    params = arrayOf(accountId)
) {
    Campaign(
        id     = it.long("ID"),
        name   = it.string("NAME"),
        status = it.string("STATUS")
    )
}
```

### 3. Stream large result sets

```kotlin
snowflake
    .stream("SELECT KEYWORD_ID, BID, IMPRESSIONS FROM KEYWORD_STATS WHERE DATE = ?", today)
    .map { row -> KeywordStat(row.long("KEYWORD_ID"), row.double("BID")) }
    .filter { it.bid > 0.10 }
    .collect { stat -> processStat(stat) }
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
    orderBy("TOTAL_SPEND" to DESC)
    limit(100)
}.fetch { row ->
    row.long("CAMPAIGN_ID") to row.double("TOTAL_SPEND")
}
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

---

## Configuration Reference

```kotlin
snowflake {
    // Required
    account   = "orgname-accountname"   // Snowflake account identifier
    username  = "my_user"
    password  = "secret"                // or use privateKey for key-pair auth

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
    }

    // Query defaults
    queryTimeout = 5.minutes
    fetchSize    = 1000              // rows per JDBC fetch for streaming
}
```

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
| Coroutine-native | YES | NO | Partial | NO |
| Snowflake-specific types | YES | NO | NO | NO |
| Zero-dependency DSL | YES | - | YES | YES |
| Flow streaming | YES | NO | NO | NO |
| Code gen required | NO | NO | NO | YES |

---

## Contributing

Contributions welcome. See [CONTRIBUTING.md](CONTRIBUTING.md).

```bash
git clone https://github.com/deepakvijayakumar/kotlin-snowflake
cd kotlin-snowflake
./gradlew test
```

Integration tests require a live Snowflake account. Copy `.env.example` to `.env` and fill in your credentials.

---

## License

[MIT](LICENSE)
