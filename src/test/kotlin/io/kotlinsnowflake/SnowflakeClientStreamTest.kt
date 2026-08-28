package io.kotlinsnowflake

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotlinsnowflake.pool.ConnectionPool
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.util.concurrent.Executors
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class SnowflakeClientStreamTest : DescribeSpec({

    val jdbcDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    class Fixture(
        val client: SnowflakeClient,
        val conn: Connection,
        val ps: PreparedStatement,
        val rs: ResultSet,
    )

    /** A pool whose connection yields [rowCount] rows of a single INT column "N". */
    fun fixture(rowCount: Int, timeout: Duration = Duration.ZERO): Fixture {
        val meta = mockk<ResultSetMetaData>(relaxed = true)
        every { meta.columnCount } returns 1
        every { meta.getColumnLabel(1) } returns "N"
        every { meta.getColumnName(1) } returns "N"

        var cursor = 0
        val rs = mockk<ResultSet>(relaxed = true)
        every { rs.metaData } returns meta
        every { rs.next() } answers { cursor++ < rowCount }
        every { rs.getLong("N") } answers { cursor.toLong() }
        every { rs.getString(1) } answers { cursor.toString() }
        every { rs.wasNull() } returns false

        val ps = mockk<PreparedStatement>(relaxed = true)
        every { ps.executeQuery() } returns rs

        val conn = mockk<Connection>(relaxed = true)
        every { conn.prepareStatement(any()) } returns ps

        val pool = mockk<ConnectionPool>(relaxed = true)
        every { pool.borrow() } returns conn

        val config = SnowflakeConfig.Builder().apply {
            account      = "test-account"
            username     = "test-user"
            password     = "test-password"
            dispatcher   = jdbcDispatcher
            queryTimeout = timeout
            fetchSize    = 500
        }.build()

        return Fixture(SnowflakeClient(config, pool), conn, ps, rs)
    }

    fun clientOverRows(rowCount: Int) = fixture(rowCount).client

    describe("stream") {

        it("emits every mapped row when collected from another dispatcher") {
            val rows = clientOverRows(3).stream("SELECT N FROM T") { it.long("N") }.toList()

            rows shouldBe listOf(1L, 2L, 3L)
        }

        it("emits raw column maps") {
            val rows = clientOverRows(2).stream("SELECT N FROM T").toList()

            rows shouldBe listOf(mapOf("N" to "1"), mapOf("N" to "2"))
        }

        it("closes the result set, statement and connection when collection completes") {
            val f = fixture(rowCount = 2)

            f.client.stream("SELECT N FROM T") { it.long("N") }.toList()

            verify { f.rs.close() }
            verify { f.ps.close() }
            verify { f.conn.close() }
        }

        it("closes everything when the collector stops early") {
            val f = fixture(rowCount = 1_000)

            val firstTwo = f.client.stream("SELECT N FROM T") { it.long("N") }.take(2).toList()

            // take() cancels the flow mid-emission; the JDBC resources must not be left
            // to a garbage collector that may never run.
            firstTwo shouldBe listOf(1L, 2L)
            verify { f.rs.close() }
            verify { f.ps.close() }
            verify { f.conn.close() }
        }

        it("applies the configured fetch size and statement timeout") {
            val f = fixture(rowCount = 1, timeout = 30.seconds)

            f.client.stream("SELECT N FROM T") { it.long("N") }.toList()

            verify { f.ps.fetchSize = 500 }
            verify { f.ps.queryTimeout = 30 }
        }

        it("binds parameters positionally") {
            val f = fixture(rowCount = 1)

            f.client.stream("SELECT N FROM T WHERE A = ? AND B = ?", "x", 7L) { it.long("N") }
                .toList()

            verifyOrder {
                f.ps.setObject(1, "x")
                f.ps.setObject(2, 7L)
            }
        }
    }
})
