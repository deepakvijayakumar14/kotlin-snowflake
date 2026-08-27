package io.kotlinsnowflake.tx

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.ResultSetMetaData

class TransactionScopeTest : DescribeSpec({

    /** A connection whose prepareStatement always yields [ps]. */
    fun connectionYielding(ps: PreparedStatement): Connection {
        val conn = mockk<Connection>()
        every { conn.prepareStatement(any()) } returns ps
        return conn
    }

    describe("execute") {

        it("binds parameters positionally, starting at index 1") {
            val ps = mockk<PreparedStatement>(relaxed = true)
            every { ps.executeUpdate() } returns 1
            val conn = connectionYielding(ps)

            TransactionScope(conn).execute(
                "UPDATE CAMPAIGNS SET STATUS = ? WHERE ID = ?",
                "PAUSED",
                7L,
            )

            verifyOrder {
                ps.setObject(1, "PAUSED")
                ps.setObject(2, 7L)
                ps.executeUpdate()
            }
        }

        it("returns the affected row count") {
            val ps = mockk<PreparedStatement>(relaxed = true)
            every { ps.executeUpdate() } returns 3
            val conn = connectionYielding(ps)

            TransactionScope(conn).execute("DELETE FROM KEYWORDS WHERE BID < ?", 0.05) shouldBe 3
        }

        it("closes the statement") {
            val ps = mockk<PreparedStatement>(relaxed = true)
            every { ps.executeUpdate() } returns 0
            val conn = connectionYielding(ps)

            TransactionScope(conn).execute("DELETE FROM KEYWORDS")

            verify { ps.close() }
        }

        it("passes no parameters for a statement without placeholders") {
            val ps = mockk<PreparedStatement>(relaxed = true)
            every { ps.executeUpdate() } returns 0
            val conn = connectionYielding(ps)

            TransactionScope(conn).execute("DELETE FROM KEYWORDS")

            verify(exactly = 0) { ps.setObject(any(), any()) }
        }
    }

    describe("query with a mapper") {

        it("maps every row and closes both statement and result set") {
            val rs = mockk<ResultSet>(relaxed = true)
            every { rs.next() } returnsMany listOf(true, true, false)
            every { rs.getString("STATUS") } returnsMany listOf("ACTIVE", "PAUSED")

            val ps = mockk<PreparedStatement>(relaxed = true)
            every { ps.executeQuery() } returns rs
            val conn = connectionYielding(ps)

            val statuses = TransactionScope(conn)
                .query("SELECT STATUS FROM CAMPAIGNS WHERE ID = ?", 7L) { it.string("STATUS") }

            statuses shouldBe listOf("ACTIVE", "PAUSED")
            verify { rs.close() }
            verify { ps.close() }
        }

        it("returns an empty list when there are no rows") {
            val rs = mockk<ResultSet>(relaxed = true)
            every { rs.next() } returns false

            val ps = mockk<PreparedStatement>(relaxed = true)
            every { ps.executeQuery() } returns rs
            val conn = connectionYielding(ps)

            val result = TransactionScope(conn)
                .query("SELECT STATUS FROM CAMPAIGNS") { it.string("STATUS") }

            result shouldBe emptyList()
        }
    }

    describe("query returning raw maps") {

        it("materializes rows before the result set is closed") {
            val meta = mockk<ResultSetMetaData>()
            every { meta.columnCount } returns 2
            every { meta.getColumnName(1) } returns "ID"
            every { meta.getColumnName(2) } returns "NAME"

            val rs = mockk<ResultSet>(relaxed = true)
            every { rs.metaData } returns meta
            every { rs.next() } returnsMany listOf(true, false)
            every { rs.getString("ID") } returns "7"
            every { rs.getString("NAME") } returns "campaign-a"

            val ps = mockk<PreparedStatement>(relaxed = true)
            every { ps.executeQuery() } returns rs
            val conn = connectionYielding(ps)

            val rows = TransactionScope(conn).query("SELECT ID, NAME FROM CAMPAIGNS")

            // The maps must survive the close(), which is the whole point of materializing.
            rows shouldBe listOf(mapOf("ID" to "7", "NAME" to "campaign-a"))
            verify { rs.close() }
        }
    }

    describe("batch") {

        it("restarts parameter indices for each item") {
            val ps = mockk<PreparedStatement>(relaxed = true)
            every { ps.executeBatch() } returns intArrayOf(1, 1)
            val conn = connectionYielding(ps)

            TransactionScope(conn).batch(
                "INSERT INTO KEYWORD_BIDS (KEYWORD_ID, BID) VALUES (?, ?)",
                listOf(1L to 0.25, 2L to 0.50),
            ) { (id, bid) -> bind(id, bid) }

            // A fresh BatchBinder per item, so both rows bind at indices 1 and 2 --
            // not 1,2 then 3,4.
            verifyOrder {
                ps.setObject(1, 1L)
                ps.setObject(2, 0.25)
                ps.addBatch()
                ps.setObject(1, 2L)
                ps.setObject(2, 0.50)
                ps.addBatch()
                ps.executeBatch()
            }
        }

        it("returns the per-statement update counts") {
            val ps = mockk<PreparedStatement>(relaxed = true)
            every { ps.executeBatch() } returns intArrayOf(1, 1, 1)
            val conn = connectionYielding(ps)

            val counts = TransactionScope(conn).batch(
                "INSERT INTO AUDIT_LOG (ACTION) VALUES (?)",
                listOf("A", "B", "C"),
            ) { bind(it) }

            counts.toList() shouldBe listOf(1, 1, 1)
        }

        it("executes an empty batch without binding anything") {
            val ps = mockk<PreparedStatement>(relaxed = true)
            every { ps.executeBatch() } returns intArrayOf()
            val conn = connectionYielding(ps)

            val counts = TransactionScope(conn)
                .batch("INSERT INTO AUDIT_LOG (ACTION) VALUES (?)", emptyList<String>()) { bind(it) }

            counts.toList() shouldBe emptyList()
            verify(exactly = 0) { ps.addBatch() }
        }
    }

    describe("BatchBinder") {

        it("advances the index across successive bind calls") {
            val ps = mockk<PreparedStatement>(relaxed = true)

            val binder = BatchBinder(ps)
            binder.bind("a", "b")
            binder.bind("c")

            verifyOrder {
                ps.setObject(1, "a")
                ps.setObject(2, "b")
                ps.setObject(3, "c")
            }
        }
    }
})
