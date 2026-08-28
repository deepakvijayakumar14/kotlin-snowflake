package io.kotlinsnowflake.query

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.math.BigDecimal
import java.sql.Date
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.Timestamp
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Covers [Row]'s null protocol. Every non-null accessor for a primitive type depends on
 * calling getX() and then consulting wasNull(), because JDBC returns 0/false for a SQL
 * NULL rather than signalling it. These tests pin that pairing per accessor.
 */
class RowTest : DescribeSpec({

    describe("string accessors") {

        it("returns the value when present") {
            val rs = mockk<ResultSet>()
            every { rs.getString("NAME") } returns "campaign-a"

            Row(rs).string("NAME") shouldBe "campaign-a"
        }

        it("throws on NULL and directs the caller to the nullable variant") {
            val rs = mockk<ResultSet>()
            every { rs.getString("NAME") } returns null

            val ex = shouldThrow<IllegalStateException> { Row(rs).string("NAME") }
            ex.message shouldBe "Column 'NAME' is NULL; use stringOrNull()"
        }

        it("returns null from stringOrNull on NULL") {
            val rs = mockk<ResultSet>()
            every { rs.getString("NAME") } returns null

            Row(rs).stringOrNull("NAME") shouldBe null
        }
    }

    describe("numeric accessors honour wasNull()") {

        it("long returns the value when the column is not NULL") {
            val rs = mockk<ResultSet>()
            every { rs.getLong("ID") } returns 42L
            every { rs.wasNull() } returns false

            Row(rs).long("ID") shouldBe 42L
            Row(rs).longOrNull("ID") shouldBe 42L
        }

        it("long throws on NULL even though JDBC reports 0") {
            val rs = mockk<ResultSet>()
            every { rs.getLong("ID") } returns 0L
            every { rs.wasNull() } returns true

            shouldThrow<IllegalStateException> { Row(rs).long("ID") }
            Row(rs).longOrNull("ID") shouldBe null
        }

        it("int throws on NULL even though JDBC reports 0") {
            val rs = mockk<ResultSet>()
            every { rs.getInt("N") } returns 0
            every { rs.wasNull() } returns true

            shouldThrow<IllegalStateException> { Row(rs).int("N") }
            Row(rs).intOrNull("N") shouldBe null
        }

        it("double throws on NULL even though JDBC reports 0.0") {
            val rs = mockk<ResultSet>()
            every { rs.getDouble("BID") } returns 0.0
            every { rs.wasNull() } returns true

            shouldThrow<IllegalStateException> { Row(rs).double("BID") }
            Row(rs).doubleOrNull("BID") shouldBe null
        }

        it("float throws on NULL even though JDBC reports 0.0f") {
            val rs = mockk<ResultSet>()
            every { rs.getFloat("RATE") } returns 0.0f
            every { rs.wasNull() } returns true

            shouldThrow<IllegalStateException> { Row(rs).float("RATE") }
            Row(rs).floatOrNull("RATE") shouldBe null
        }

        it("distinguishes a genuine zero from NULL") {
            val rs = mockk<ResultSet>()
            every { rs.getDouble("SPEND") } returns 0.0
            every { rs.wasNull() } returns false

            Row(rs).double("SPEND") shouldBe 0.0
            Row(rs).doubleOrNull("SPEND") shouldBe 0.0
        }

        it("bigDecimal relies on a null reference rather than wasNull()") {
            val rs = mockk<ResultSet>()
            every { rs.getBigDecimal("TOTAL") } returns BigDecimal("10.50")

            Row(rs).bigDecimal("TOTAL") shouldBe BigDecimal("10.50")

            val nullRs = mockk<ResultSet>()
            every { nullRs.getBigDecimal("TOTAL") } returns null

            shouldThrow<IllegalStateException> { Row(nullRs).bigDecimal("TOTAL") }
            Row(nullRs).bigDecimalOrNull("TOTAL") shouldBe null
        }
    }

    describe("boolean accessor") {

        it("throws on NULL even though JDBC reports false") {
            val rs = mockk<ResultSet>()
            every { rs.getBoolean("ACTIVE") } returns false
            every { rs.wasNull() } returns true

            shouldThrow<IllegalStateException> { Row(rs).boolean("ACTIVE") }
            Row(rs).booleanOrNull("ACTIVE") shouldBe null
        }

        it("distinguishes a genuine false from NULL") {
            val rs = mockk<ResultSet>()
            every { rs.getBoolean("ACTIVE") } returns false
            every { rs.wasNull() } returns false

            Row(rs).boolean("ACTIVE") shouldBe false
            Row(rs).booleanOrNull("ACTIVE") shouldBe false
        }
    }

    describe("date and time accessors") {

        it("converts a timestamp to an Instant") {
            val ts = Timestamp.valueOf("2024-03-01 12:30:00")
            val rs = mockk<ResultSet>()
            every { rs.getTimestamp("CREATED_AT") } returns ts

            Row(rs).instant("CREATED_AT") shouldBe ts.toInstant()
            Row(rs).timestampTz("CREATED_AT") shouldBe ts.toInstant()
        }

        it("converts a date to a LocalDate") {
            val rs = mockk<ResultSet>()
            every { rs.getDate("START_DATE") } returns Date.valueOf("2024-01-15")

            Row(rs).localDate("START_DATE") shouldBe LocalDate.of(2024, 1, 15)
        }

        it("converts a timestamp to a LocalDateTime") {
            val rs = mockk<ResultSet>()
            every { rs.getTimestamp("UPDATED_AT") } returns Timestamp.valueOf("2024-03-01 12:30:00")

            Row(rs).localDateTime("UPDATED_AT") shouldBe LocalDateTime.of(2024, 3, 1, 12, 30, 0)
        }

        it("throws on a NULL timestamp and returns null from the nullable variant") {
            val rs = mockk<ResultSet>()
            every { rs.getTimestamp("CREATED_AT") } returns null
            every { rs.getDate("START_DATE") } returns null

            shouldThrow<IllegalStateException> { Row(rs).instant("CREATED_AT") }
            shouldThrow<IllegalStateException> { Row(rs).localDate("START_DATE") }
            Row(rs).instantOrNull("CREATED_AT") shouldBe null
            Row(rs).localDateOrNull("START_DATE") shouldBe null
            Row(rs).localDateTimeOrNull("CREATED_AT") shouldBe null
        }
    }

    describe("column metadata") {

        it("reads column labels from the result set metadata") {
            val meta = mockk<ResultSetMetaData>()
            every { meta.columnCount } returns 2
            every { meta.getColumnLabel(1) } returns "ID"
            every { meta.getColumnLabel(2) } returns "NAME"

            val rs = mockk<ResultSet>()
            every { rs.metaData } returns meta

            val row = Row(rs)
            row.columnCount shouldBe 2
            row.columnNames shouldBe listOf("ID", "NAME")
        }

        it("reports the alias, not the underlying column, for a computed column") {
            // SELECT SUM(SPEND) AS TOTAL_SPEND: getColumnName() reports SPEND (or ""),
            // but TOTAL_SPEND is what callers address the column by.
            val meta = mockk<ResultSetMetaData>()
            every { meta.columnCount } returns 1
            every { meta.getColumnLabel(1) } returns "TOTAL_SPEND"
            every { meta.getColumnName(1) } returns "SPEND"

            val rs = mockk<ResultSet>()
            every { rs.metaData } returns meta

            Row(rs).columnNames shouldBe listOf("TOTAL_SPEND")
        }

        it("materializes the row as a map, preserving NULLs") {
            val meta = mockk<ResultSetMetaData>()
            every { meta.columnCount } returns 2
            every { meta.getColumnLabel(1) } returns "ID"
            every { meta.getColumnLabel(2) } returns "NAME"

            val rs = mockk<ResultSet>()
            every { rs.metaData } returns meta
            every { rs.getString(1) } returns "7"
            every { rs.getString(2) } returns null

            Row(rs).toMap() shouldBe mapOf("ID" to "7", "NAME" to null)
        }
    }
})
