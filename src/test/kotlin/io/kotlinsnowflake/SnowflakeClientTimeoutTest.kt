package io.kotlinsnowflake

import io.kotest.core.spec.style.DescribeSpec
import io.kotlinsnowflake.pool.ConnectionPool
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The statement timeout is the only guard against a runaway query holding a warehouse
 * open, so every execution path has to apply it - not just the ones that happen to go
 * through the mapped-query helper.
 */
class SnowflakeClientTimeoutTest : DescribeSpec({

    fun clientAndStatement(timeout: Duration): Pair<SnowflakeClient, PreparedStatement> {
        val rs = mockk<ResultSet>(relaxed = true)
        every { rs.next() } returns false

        val ps = mockk<PreparedStatement>(relaxed = true)
        every { ps.executeQuery() } returns rs
        every { ps.executeUpdate() } returns 0
        every { ps.executeBatch() } returns intArrayOf(1)

        val conn = mockk<Connection>(relaxed = true)
        every { conn.prepareStatement(any()) } returns ps

        val pool = mockk<ConnectionPool>(relaxed = true)
        every { pool.borrow() } returns conn

        val config = SnowflakeConfig.Builder().apply {
            account      = "test-account"
            username     = "test-user"
            password     = "test-password"
            dispatcher   = Dispatchers.Unconfined
            queryTimeout = timeout
        }.build()

        return SnowflakeClient(config, pool) to ps
    }

    describe("queryTimeout") {

        it("is applied to query, execute, batch and transaction statements") {
            val (client, ps) = clientAndStatement(30.seconds)

            client.query("SELECT 1") { it.string("X") }
            client.query("SELECT 1")
            client.execute("DELETE FROM T")
            client.batch("INSERT INTO T VALUES (?)", listOf("a")) { bind(it) }
            client.transaction { execute("DELETE FROM T") }

            verify(exactly = 5) { ps.queryTimeout = 30 }
        }

        it("is left unset when configured as zero") {
            val (client, ps) = clientAndStatement(Duration.ZERO)

            client.execute("DELETE FROM T")

            verify(exactly = 0) { ps.queryTimeout = any() }
        }
    }
})
