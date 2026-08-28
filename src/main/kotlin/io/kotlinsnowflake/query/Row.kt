package io.kotlinsnowflake.query

import java.math.BigDecimal
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * A single row returned from a Snowflake query.
 *
 * Provides typed column accessors with both nullable and non-null variants.
 * Column lookup is delegated to the JDBC driver's `findColumn`, which matches column
 * labels case-insensitively, so `row.string("name")` and `row.string("NAME")` are
 * equivalent. A column given an alias is addressed by that alias.
 *
 * ```kotlin
 * val name: String  = row.string("NAME")
 * val bid: Double?  = row.doubleOrNull("BID")
 * val date: LocalDate = row.localDate("START_DATE")
 * ```
 */
class Row internal constructor(private val rs: ResultSet) {

    // -- String -------------------------------------------------------------------------------

    fun string(column: String): String =
        rs.getString(column) ?: error("Column '$column' is NULL; use stringOrNull()")

    fun stringOrNull(column: String): String? = rs.getString(column)

    // -- Numeric ------------------------------------------------------------------------------

    fun long(column: String): Long   = rs.getLong(column).also { check(!rs.wasNull()) { nullError(column) } }
    fun longOrNull(column: String): Long? = rs.getLong(column).takeUnless { rs.wasNull() }

    fun int(column: String): Int     = rs.getInt(column).also { check(!rs.wasNull()) { nullError(column) } }
    fun intOrNull(column: String): Int? = rs.getInt(column).takeUnless { rs.wasNull() }

    fun double(column: String): Double   = rs.getDouble(column).also { check(!rs.wasNull()) { nullError(column) } }
    fun doubleOrNull(column: String): Double? = rs.getDouble(column).takeUnless { rs.wasNull() }

    fun float(column: String): Float   = rs.getFloat(column).also { check(!rs.wasNull()) { nullError(column) } }
    fun floatOrNull(column: String): Float? = rs.getFloat(column).takeUnless { rs.wasNull() }

    fun bigDecimal(column: String): BigDecimal =
        rs.getBigDecimal(column) ?: error(nullError(column))

    fun bigDecimalOrNull(column: String): BigDecimal? = rs.getBigDecimal(column)

    // -- Boolean ------------------------------------------------------------------------------

    fun boolean(column: String): Boolean = rs.getBoolean(column).also { check(!rs.wasNull()) { nullError(column) } }
    fun booleanOrNull(column: String): Boolean? = rs.getBoolean(column).takeUnless { rs.wasNull() }

    // -- Date / Time --------------------------------------------------------------------------

    fun instant(column: String): Instant =
        rs.getTimestamp(column)?.toInstant() ?: error(nullError(column))

    fun instantOrNull(column: String): Instant? = rs.getTimestamp(column)?.toInstant()

    fun localDate(column: String): LocalDate =
        rs.getDate(column)?.toLocalDate() ?: error(nullError(column))

    fun localDateOrNull(column: String): LocalDate? = rs.getDate(column)?.toLocalDate()

    fun localDateTime(column: String): LocalDateTime =
        rs.getTimestamp(column)?.toLocalDateTime() ?: error(nullError(column))

    fun localDateTimeOrNull(column: String): LocalDateTime? =
        rs.getTimestamp(column)?.toLocalDateTime()

    /** Returns a TIMESTAMP_TZ column as [Instant] (UTC). */
    fun timestampTz(column: String): Instant =
        rs.getTimestamp(column)?.toInstant() ?: error(nullError(column))

    // -- JSON / Variant -----------------------------------------------------------------------

    /** Returns a VARIANT or OBJECT column as a raw JSON string. */
    fun json(column: String): String =
        rs.getString(column) ?: error(nullError(column))

    fun jsonOrNull(column: String): String? = rs.getString(column)

    // -- Bytes --------------------------------------------------------------------------------

    fun bytes(column: String): ByteArray =
        rs.getBytes(column) ?: error(nullError(column))

    fun bytesOrNull(column: String): ByteArray? = rs.getBytes(column)

    // -- Column metadata ----------------------------------------------------------------------

    /** Returns the number of columns in this row. */
    val columnCount: Int get() = rs.metaData.columnCount

    /**
     * Returns column labels as an ordered list.
     *
     * Labels, not underlying names: `SELECT SUM(SPEND) AS TOTAL_SPEND` reports
     * `TOTAL_SPEND`, which is what callers address the column by.
     */
    val columnNames: List<String> get() =
        (1..columnCount).map { rs.metaData.getColumnLabel(it) }

    /**
     * Returns a map of all column labels to their raw string values.
     * Values are read by index, so duplicate labels do not collapse onto one value.
     */
    fun toMap(): Map<String, String?> = rs.toColumnMap()

    // -----------------------------------------------------------------------------------------

    private fun nullError(column: String) =
        "Column '$column' is NULL; use the nullable variant (e.g. stringOrNull, longOrNull)"
}

/**
 * Reads the current row as a map of column label to raw string value.
 * Shared by the raw query and stream paths so both agree on labelling.
 */
internal fun ResultSet.toColumnMap(): Map<String, String?> {
    val meta = metaData
    return (1..meta.columnCount).associate { meta.getColumnLabel(it) to getString(it) }
}

/** Functional interface for mapping a [Row] to a domain object. */
fun interface RowMapper<T> {
    fun map(row: Row): T
}
