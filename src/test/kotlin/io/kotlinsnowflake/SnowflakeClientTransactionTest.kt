package io.kotlinsnowflake

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotlinsnowflake.pool.ConnectionPool
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import java.sql.Connection
import java.sql.SQLException

/**
 * Covers the commit/rollback lifecycle in [SnowflakeClient.transaction].
 *
 * The failure this guards against is a connection going back to the pool still carrying
 * autoCommit = false, which would silently swallow writes made by whoever borrows it next.
 */
class SnowflakeClientTransactionTest : DescribeSpec({

    fun testConfig() = SnowflakeConfig.Builder().apply {
        account    = "test-account"
        username   = "test-user"
        password   = "test-password"
        dispatcher = Dispatchers.Unconfined
    }.build()

    /** A client whose pool hands out [conn] instead of opening a real connection. */
    fun clientOver(conn: Connection): SnowflakeClient {
        val pool = mockk<ConnectionPool>(relaxed = true)
        every { pool.borrow() } returns conn
        return SnowflakeClient(testConfig(), pool)
    }

    describe("transaction on success") {

        it("disables autoCommit, commits, then restores autoCommit and releases the connection") {
            val conn = mockk<Connection>(relaxed = true)

            clientOver(conn).transaction { "done" } shouldBe "done"

            verifyOrder {
                conn.autoCommit = false
                conn.commit()
                conn.autoCommit = true
                conn.close()
            }
            verify(exactly = 0) { conn.rollback() }
        }

        it("returns the value produced by the block") {
            val conn = mockk<Connection>(relaxed = true)

            clientOver(conn).transaction { 42 } shouldBe 42
        }

        it("gives the block a scope bound to the same connection") {
            val ps = mockk<java.sql.PreparedStatement>(relaxed = true)
            every { ps.executeUpdate() } returns 1
            val conn = mockk<Connection>(relaxed = true)
            every { conn.prepareStatement(any()) } returns ps

            val affected = clientOver(conn).transaction {
                execute("UPDATE CAMPAIGNS SET STATUS = ? WHERE ID = ?", "PAUSED", 7L)
            }

            affected shouldBe 1
            verify { conn.prepareStatement("UPDATE CAMPAIGNS SET STATUS = ? WHERE ID = ?") }
            verify { conn.commit() }
        }
    }

    describe("transaction on failure") {

        it("rolls back, never commits, and rethrows the original exception") {
            val conn = mockk<Connection>(relaxed = true)
            val boom = IllegalStateException("block failed")

            val thrown = shouldThrow<IllegalStateException> {
                clientOver(conn).transaction { throw boom }
            }

            thrown shouldBe boom
            verify { conn.rollback() }
            verify(exactly = 0) { conn.commit() }
        }

        it("restores autoCommit and releases the connection even when the block throws") {
            val conn = mockk<Connection>(relaxed = true)

            shouldThrow<SQLException> {
                clientOver(conn).transaction { throw SQLException("constraint violation") }
            }

            // The ordering that matters: rollback happens before autoCommit is restored,
            // and the connection is only released afterwards.
            verifyOrder {
                conn.autoCommit = false
                conn.rollback()
                conn.autoCommit = true
                conn.close()
            }
        }

        it("rolls back when commit itself fails") {
            val conn = mockk<Connection>(relaxed = true)
            every { conn.commit() } throws SQLException("commit rejected")

            shouldThrow<SQLException> {
                clientOver(conn).transaction { "value" }
            }

            verifyOrder {
                conn.commit()
                conn.rollback()
                conn.autoCommit = true
            }
        }

        it("keeps the original failure when the rollback also fails") {
            val conn = mockk<Connection>(relaxed = true)
            val rollbackFailure = SQLException("rollback failed")
            every { conn.rollback() } throws rollbackFailure

            val thrown = shouldThrow<IllegalStateException> {
                clientOver(conn).transaction { throw IllegalStateException("block failed") }
            }

            // The block's exception is what the caller can act on; the rollback failure
            // is cleanup detail, so it rides along rather than replacing it.
            thrown.message shouldBe "block failed"
            thrown.suppressed.toList() shouldBe listOf(rollbackFailure)
        }

        it("restores autoCommit and releases the connection when the rollback fails") {
            val conn = mockk<Connection>(relaxed = true)
            every { conn.rollback() } throws SQLException("rollback failed")

            shouldThrow<IllegalStateException> {
                clientOver(conn).transaction { throw IllegalStateException("block failed") }
            }

            // The finally block must still run, or this connection poisons the pool.
            verify { conn.autoCommit = true }
            verify { conn.close() }
        }

        it("rolls back when the block fails with an Error rather than an Exception") {
            val conn = mockk<Connection>(relaxed = true)

            shouldThrow<StackOverflowError> {
                clientOver(conn).transaction { throw StackOverflowError("deep") }
            }

            // Catching Exception would have let this commit-less path skip the rollback,
            // leaving the transaction open on a connection headed back to the pool.
            verify { conn.rollback() }
            verify(exactly = 0) { conn.commit() }
            verify { conn.autoCommit = true }
        }

        it("rolls back when the caller's coroutine is cancelled mid-block") {
            val conn = mockk<Connection>(relaxed = true)

            shouldThrow<CancellationException> {
                clientOver(conn).transaction { throw CancellationException("cancelled") }
            }

            verify { conn.rollback() }
            verify(exactly = 0) { conn.commit() }
            verify { conn.autoCommit = true }
            verify { conn.close() }
        }
    }

    describe("close") {

        it("closes the underlying pool") {
            val pool = mockk<ConnectionPool>(relaxed = true)
            SnowflakeClient(testConfig(), pool).close()

            verify { pool.close() }
        }
    }
})
