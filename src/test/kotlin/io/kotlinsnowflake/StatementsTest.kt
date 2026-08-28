package io.kotlinsnowflake

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException

class StatementsTest : DescribeSpec({

    fun connectionYielding(ps: PreparedStatement): Connection {
        val conn = mockk<Connection>(relaxed = true)
        every { conn.prepareStatement(any()) } returns ps
        return conn
    }

    describe("prepared") {

        it("closes the statement when binding a parameter fails") {
            val ps = mockk<PreparedStatement>(relaxed = true)
            val boom = SQLException("unsupported parameter type")
            every { ps.setObject(2, any()) } throws boom

            val thrown = shouldThrow<SQLException> {
                connectionYielding(ps).prepared("SELECT ? , ?", arrayOf("a", Any()), 0)
            }

            // Nothing calls use() on a statement that was never returned, so it has to close
            // itself or it leaks for as long as the connection is out of the pool.
            thrown shouldBe boom
            verify { ps.close() }
        }

        it("keeps the binding failure when the close also fails") {
            val ps = mockk<PreparedStatement>(relaxed = true)
            val boom = SQLException("bad parameter")
            val closeFailure = SQLException("close failed")
            every { ps.setObject(any(), any()) } throws boom
            every { ps.close() } throws closeFailure

            val thrown = shouldThrow<SQLException> {
                connectionYielding(ps).prepared("SELECT ?", arrayOf("a"), 0)
            }

            thrown shouldBe boom
            thrown.suppressed.toList() shouldBe listOf(closeFailure)
        }

        it("returns a bound statement on the happy path") {
            val ps = mockk<PreparedStatement>(relaxed = true)

            connectionYielding(ps).prepared("SELECT ?", arrayOf("a"), 15) shouldBe ps

            verify { ps.queryTimeout = 15 }
            verify { ps.setObject(1, "a") }
        }
    }
})
