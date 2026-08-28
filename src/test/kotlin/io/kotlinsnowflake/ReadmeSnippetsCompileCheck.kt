package io.kotlinsnowflake

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotlinsnowflake.pool.ConnectionPool
import io.kotlinsnowflake.query.Row
import io.kotlinsnowflake.query.SortOrder.DESC
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.toList
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.time.Instant
import java.time.LocalDate
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Compiles - and, wherever a live account is not required, runs - the snippets published in
 * README.md, so the documented API cannot drift away from the real one again.
 *
 * The last release shipped a README describing reified-generic mapping that did not exist and a
 * `stream()` example calling `Row` accessors on a `Map`. Both would have failed to compile here.
 */
class ReadmeSnippetsCompileCheck : DescribeSpec({

    data class Campaign(val id: Long, val name: String, val status: String)
    data class KeywordStat(val keywordId: Long, val bid: Double)

    /**
     * A client over a mocked pool, so README snippets execute for real without an account.
     * Every query yields [rowCount] rows of ID / NAME / STATUS / BID.
     */
    fun readmeClient(rowCount: Int = 2): SnowflakeClient {
        val meta = mockk<ResultSetMetaData>(relaxed = true)
        every { meta.columnCount } returns 2
        every { meta.getColumnLabel(1) } returns "ID"
        every { meta.getColumnLabel(2) } returns "NAME"

        var cursor = 0
        val rs = mockk<ResultSet>(relaxed = true)
        every { rs.metaData } returns meta
        every { rs.next() } answers { cursor++ < rowCount }
        every { rs.getString(any<Int>()) } answers { "value-$cursor" }
        every { rs.getString("NAME") } answers { "campaign-$cursor" }
        every { rs.getString("STATUS") } returns "ACTIVE"
        every { rs.getLong(any<String>()) } answers { cursor.toLong() }
        every { rs.getDouble(any<String>()) } returns 0.25
        every { rs.wasNull() } returns false

        val ps = mockk<PreparedStatement>(relaxed = true)
        every { ps.executeQuery() } returns rs
        every { ps.executeUpdate() } returns 1
        every { ps.executeBatch() } returns IntArray(rowCount) { 1 }

        val conn = mockk<Connection>(relaxed = true)
        every { conn.prepareStatement(any()) } returns ps

        val pool = mockk<ConnectionPool>(relaxed = true)
        every { pool.borrow() } returns conn

        val config = SnowflakeConfig.Builder().apply {
            account    = "myorg-myaccount"
            username   = "my_user"
            password   = "secret"
            database   = "MY_DATABASE"
            schema     = "PUBLIC"
            warehouse  = "COMPUTE_WH"
            dispatcher = Dispatchers.Unconfined
        }.build()

        return SnowflakeClient(config, pool)
    }

    describe("Quick Start") {

        it("1. creates a client") {
            // README calls snowflake { } , which opens a real pool. The builder underneath it
            // takes exactly these properties, which is the part that can drift.
            val config = SnowflakeConfig.Builder().apply {
                account   = "myorg-myaccount"
                username  = "my_user"
                password  = "secret"
                database  = "MY_DATABASE"
                schema    = "PUBLIC"
                warehouse = "COMPUTE_WH"
                role      = "MY_ROLE"

                pool {
                    maxSize     = 10
                    minIdle     = 2
                    idleTimeout = 10.minutes
                }
            }.build()

            config.warehouse shouldBe "COMPUTE_WH"
        }

        it("2. queries with an explicit row mapper") {
            val snowflake = readmeClient()
            val accountId = 42L

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

            campaigns.first().status shouldBe "ACTIVE"
        }

        it("2b. queries without a mapper, returning raw maps") {
            val snowflake = readmeClient()

            val rows: List<Map<String, String?>> =
                snowflake.query("SELECT ID, NAME FROM CAMPAIGNS LIMIT 10")

            rows.first().keys shouldBe setOf("ID", "NAME")
        }

        it("3. streams large result sets") {
            val snowflake = readmeClient()
            val today = LocalDate.now()
            val collected = mutableListOf<KeywordStat>()
            fun processStat(stat: KeywordStat) { collected += stat }

            snowflake
                .stream(
                    "SELECT KEYWORD_ID, BID, IMPRESSIONS FROM KEYWORD_STATS WHERE DATE = ?",
                    today
                ) { row ->
                    KeywordStat(row.long("KEYWORD_ID"), row.double("BID"))
                }
                .filter { it.bid > 0.10 }
                .collect { stat -> processStat(stat) }

            collected.size shouldBe 2
        }

        it("3b. streams raw maps without a mapper") {
            val snowflake = readmeClient()
            val printed = mutableListOf<String?>()

            // README prints row["BID"]; the point is that the element is a Map, not a Row.
            snowflake.stream("SELECT * FROM KEYWORD_STATS").collect { row -> printed += row["BID"] }

            printed.size shouldBe 2
        }

        it("4. builds a query with the DSL") {
            val snowflake = readmeClient()
            val startDate = LocalDate.of(2026, 1, 1)
            val endDate   = LocalDate.of(2026, 3, 31)
            val accountIds = listOf(1L, 2L)

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

            results.size shouldBe 2
        }

        it("4b. generates the OR clause the README documents") {
            val snowflake = readmeClient()
            val accountId = 42L

            val sql = snowflake.select {
                from("CAMPAIGNS")
                where {
                    "ACCOUNT_ID" eq accountId
                    or {
                        "STATUS" eq "ACTIVE"
                        "STATUS" eq "PAUSED"
                    }
                }
            }.toSql()

            // The README annotates this snippet with the generated clause. Pin it.
            sql shouldBe
                "SELECT * FROM CAMPAIGNS WHERE ACCOUNT_ID = ? AND (STATUS = ? OR STATUS = ?)"
        }

        it("4c. streams a DSL query") {
            val snowflake = readmeClient()

            val ids = snowflake.select {
                columns("CAMPAIGN_ID")
                from("AD_PERFORMANCE")
            }.stream { row -> row.long("CAMPAIGN_ID") }.toList()

            ids.size shouldBe 2
        }

        it("5. runs a transaction") {
            val snowflake = readmeClient()
            val campaignId = 7L

            val result = snowflake.transaction {
                execute("UPDATE CAMPAIGNS SET STATUS = ? WHERE ID = ?", "PAUSED", campaignId)
                execute(
                    "INSERT INTO AUDIT_LOG (CAMPAIGN_ID, ACTION) VALUES (?, ?)",
                    campaignId,
                    "PAUSED"
                )
                query("SELECT STATUS FROM CAMPAIGNS WHERE ID = ?", campaignId) { it.string("STATUS") }
                    .first()
            }

            result shouldBe "ACTIVE"
        }

        it("6. inserts in a batch") {
            val snowflake = readmeClient()
            val keywords = listOf(KeywordStat(1L, 0.25), KeywordStat(2L, 0.50))

            val counts = snowflake.batch(
                sql  = "INSERT INTO KEYWORD_BIDS (KEYWORD_ID, BID, UPDATED_AT) VALUES (?, ?, ?)",
                rows = keywords
            ) { kw ->
                bind(kw.keywordId, kw.bid, Instant.now())
            }

            counts.toList() shouldBe listOf(1, 1)
        }
    }

    describe("Row API") {

        it("exposes every accessor the table lists") {
            val rs = mockk<ResultSet>(relaxed = true)
            every { rs.getString(any<String>()) } returns "x"
            every { rs.getBigDecimal(any<String>()) } returns java.math.BigDecimal.ONE
            every { rs.getTimestamp(any<String>()) } returns java.sql.Timestamp(0)
            every { rs.getDate(any<String>()) } returns java.sql.Date(0)
            every { rs.wasNull() } returns false

            val row = Row(rs)

            // One call per documented row of the README table; a rename would stop compiling.
            row.string("COL")
            row.stringOrNull("COL")
            row.long("COL")
            row.int("COL")
            row.double("COL")
            row.bigDecimal("COL")
            row.boolean("COL")
            row.instant("COL")
            row.localDate("COL")
            row.localDateTime("COL")
            row.json("COL") shouldBe "x"
        }
    }

    describe("Configuration Reference") {

        it("accepts every documented setting") {
            val config = SnowflakeConfig.Builder().apply {
                account   = "orgname-accountname"
                username  = "my_user"
                password  = "secret"

                database  = "MY_DB"
                schema    = "PUBLIC"
                warehouse = "COMPUTE_WH"
                role      = "MY_ROLE"

                pool {
                    maxSize              = 10
                    minIdle              = 2
                    connectionTimeout    = 30.seconds
                    idleTimeout          = 10.minutes
                    maxLifetime          = 30.minutes
                    keepaliveTime        = 5.minutes
                }

                queryTimeout = 5.minutes
                fetchSize    = 1000
            }.build()

            config.queryTimeoutSeconds shouldBe 300
        }

        it("accepts the key-pair alternative") {
            val config = SnowflakeConfig.Builder().apply {
                account              = "orgname-accountname"
                username             = "my_user"
                privateKeyPath       = "/path/to/rsa_key.p8"
                privateKeyPassphrase = "key-passphrase"
            }.build()

            config.password shouldBe null
        }

        it("overrides the dispatcher") {
            val config = SnowflakeConfig.Builder().apply {
                account    = "orgname-accountname"
                username   = "my_user"
                password   = "secret"
                dispatcher = Dispatchers.IO.limitedParallelism(16)
            }.build()

            config.dispatcher shouldBe config.dispatcher
        }
    }
})
