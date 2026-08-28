package io.kotlinsnowflake

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotlinsnowflake.pool.ConnectionPool
import io.kotlinsnowflake.query.SortOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.ResultSetMetaData

/**
 * The DSL builds SQL and a parameter list; [PreparedSelect] is what carries both to the
 * client. The failure worth guarding against is the two drifting apart - the right SQL
 * reaching the driver with the wrong bind values, which no SQL assertion would catch.
 */
class PreparedSelectTest : DescribeSpec({

    class Fixture(val client: SnowflakeClient, val conn: Connection, val ps: PreparedStatement)

    fun fixture(rowCount: Int = 1): Fixture {
        val meta = mockk<ResultSetMetaData>(relaxed = true)
        every { meta.columnCount } returns 1
        every { meta.getColumnLabel(1) } returns "CAMPAIGN_ID"

        var cursor = 0
        val rs = mockk<ResultSet>(relaxed = true)
        every { rs.metaData } returns meta
        every { rs.next() } answers { cursor++ < rowCount }
        every { rs.getLong("CAMPAIGN_ID") } answers { cursor.toLong() }
        every { rs.getString(1) } answers { cursor.toString() }
        every { rs.wasNull() } returns false

        val ps = mockk<PreparedStatement>(relaxed = true)
        every { ps.executeQuery() } returns rs

        val conn = mockk<Connection>(relaxed = true)
        every { conn.prepareStatement(any()) } returns ps

        val pool = mockk<ConnectionPool>(relaxed = true)
        every { pool.borrow() } returns conn

        val config = SnowflakeConfig.Builder().apply {
            account    = "test-account"
            username   = "test-user"
            password   = "test-password"
            dispatcher = Dispatchers.Unconfined
        }.build()

        return Fixture(SnowflakeClient(config, pool), conn, ps)
    }

    fun Fixture.select() = client.select {
        columns("CAMPAIGN_ID")
        from("AD_PERFORMANCE")
        where {
            "ACCOUNT_ID" eq 42L
            or {
                "STATUS" eq "ACTIVE"
                "STATUS" eq "PAUSED"
            }
        }
        groupBy("CAMPAIGN_ID")
        having("SUM(SPEND) > ?", 1000.0)
        orderBy("CAMPAIGN_ID" to SortOrder.DESC)
        limit(10)
        offset(5)
    }

    describe("toSql") {

        it("assembles the clauses in SQL order") {
            fixture().select().toSql() shouldBe
                "SELECT CAMPAIGN_ID FROM AD_PERFORMANCE " +
                "WHERE ACCOUNT_ID = ? AND (STATUS = ? OR STATUS = ?) " +
                "GROUP BY CAMPAIGN_ID HAVING SUM(SPEND) > ? " +
                "ORDER BY CAMPAIGN_ID DESC LIMIT 10 OFFSET 5"
        }
    }

    describe("fetch") {

        it("carries the DSL's parameters through in placeholder order") {
            val f = fixture()

            f.select().fetch { it.long("CAMPAIGN_ID") } shouldBe listOf(1L)

            verifyOrder {
                f.ps.setObject(1, 42L)
                f.ps.setObject(2, "ACTIVE")
                f.ps.setObject(3, "PAUSED")
                f.ps.setObject(4, 1000.0)
            }
        }

        it("returns raw maps without a mapper") {
            val f = fixture()

            f.select().fetch() shouldBe listOf(mapOf("CAMPAIGN_ID" to "1"))
        }
    }

    describe("stream") {

        it("streams mapped rows and releases the connection") {
            val f = fixture(rowCount = 3)

            f.select().stream { it.long("CAMPAIGN_ID") }.toList() shouldBe listOf(1L, 2L, 3L)

            verify { f.conn.close() }
        }

        it("streams raw maps without a mapper") {
            val f = fixture(rowCount = 2)

            f.select().stream().toList() shouldBe
                listOf(mapOf("CAMPAIGN_ID" to "1"), mapOf("CAMPAIGN_ID" to "2"))
        }

        it("binds the same parameters the fetch path does") {
            val f = fixture()

            f.select().stream { it.long("CAMPAIGN_ID") }.toList()

            verifyOrder {
                f.ps.setObject(1, 42L)
                f.ps.setObject(2, "ACTIVE")
                f.ps.setObject(3, "PAUSED")
                f.ps.setObject(4, 1000.0)
            }
        }
    }
})
