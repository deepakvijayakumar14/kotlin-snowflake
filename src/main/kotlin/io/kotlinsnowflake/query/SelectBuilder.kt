package io.kotlinsnowflake.query

/**
 * DSL for building Snowflake SELECT statements.
 *
 * ```kotlin
 * val query = client.select {
 *     columns("CAMPAIGN_ID", "SUM(SPEND) AS TOTAL_SPEND")
 *     from("AD_PERFORMANCE")
 *     where {
 *         "DATE"       between (startDate to endDate)
 *         "STATUS"     eq "ACTIVE"
 *         "ACCOUNT_ID" inList accountIds
 *     }
 *     groupBy("CAMPAIGN_ID")
 *     orderBy("TOTAL_SPEND" to SortOrder.DESC)
 *     limit(100)
 * }
 * val rows: List<Row> = query.fetch()
 * ```
 */
class SelectBuilder {

    private var columns: List<String>  = listOf("*")
    private var table: String          = ""
    private var whereClause: String?   = null
    private val whereParams: MutableList<Any?> = mutableListOf()
    private var groupByClause: String? = null
    private var havingClause: String?  = null
    private var orderByClause: String? = null
    private var limitValue: Int?       = null
    private var offsetValue: Int?      = null

    /** Specify SELECT columns. Defaults to `*`. */
    fun columns(vararg cols: String) {
        columns = cols.toList()
    }

    /** Specify the FROM table. Accepts fully-qualified names: `DB.SCHEMA.TABLE`. */
    fun from(tableName: String) {
        table = tableName
    }

    /** Add a WHERE clause using the predicate DSL. */
    fun where(block: WhereBuilder.() -> Unit) {
        val builder = WhereBuilder().apply(block)
        whereClause = builder.build()
        whereParams.addAll(builder.params())
    }

    /** Raw WHERE clause for cases the DSL doesn't cover. */
    fun whereRaw(sql: String, vararg params: Any?) {
        whereClause = sql
        whereParams.addAll(params.toList())
    }

    fun groupBy(vararg cols: String) {
        groupByClause = cols.joinToString(", ")
    }

    fun having(sql: String, vararg params: Any?) {
        havingClause = sql
        whereParams.addAll(params.toList())
    }

    /** Order by one or more columns. Use [SortOrder.ASC] or [SortOrder.DESC]. */
    fun orderBy(vararg cols: Pair<String, SortOrder>) {
        orderByClause = cols.joinToString(", ") { (col, dir) -> "$col ${dir.name}" }
    }

    /** Convenience: order by a single column ascending. */
    fun orderBy(col: String, direction: SortOrder = SortOrder.ASC) {
        orderByClause = "$col ${direction.name}"
    }

    fun limit(n: Int) {
        limitValue = n
    }

    fun offset(n: Int) {
        offsetValue = n
    }

    internal fun buildSql(): String {
        require(table.isNotBlank()) { "from() must be specified" }

        return buildString {
            append("SELECT ")
            append(columns.joinToString(", "))
            append(" FROM ")
            append(table)
            whereClause?.let { append(" WHERE $it") }
            groupByClause?.let { append(" GROUP BY $it") }
            havingClause?.let { append(" HAVING $it") }
            orderByClause?.let { append(" ORDER BY $it") }
            limitValue?.let { append(" LIMIT $it") }
            offsetValue?.let { append(" OFFSET $it") }
        }
    }

    internal fun params(): List<Any?> = whereParams.toList()
}

enum class SortOrder { ASC, DESC }

// -------------------------------------------------------------------------------------------------

/**
 * Builds a WHERE clause from individual predicates.
 * All predicates are combined with AND. Use [or] for OR groups.
 */
class WhereBuilder {

    private val parts: MutableList<String> = mutableListOf()
    private val bindParams: MutableList<Any?> = mutableListOf()

    /** `COLUMN = ?` */
    infix fun String.eq(value: Any?) {
        parts += "$this = ?"
        bindParams += value
    }

    /** `COLUMN != ?` */
    infix fun String.neq(value: Any?) {
        parts += "$this != ?"
        bindParams += value
    }

    /** `COLUMN > ?` */
    infix fun String.gt(value: Any?) {
        parts += "$this > ?"
        bindParams += value
    }

    /** `COLUMN >= ?` */
    infix fun String.gte(value: Any?) {
        parts += "$this >= ?"
        bindParams += value
    }

    /** `COLUMN < ?` */
    infix fun String.lt(value: Any?) {
        parts += "$this < ?"
        bindParams += value
    }

    /** `COLUMN <= ?` */
    infix fun String.lte(value: Any?) {
        parts += "$this <= ?"
        bindParams += value
    }

    /** `COLUMN BETWEEN ? AND ?` */
    infix fun String.between(range: Pair<Any?, Any?>) {
        parts += "$this BETWEEN ? AND ?"
        bindParams += range.first
        bindParams += range.second
    }

    /** `COLUMN IN (?, ?, ...)` */
    infix fun String.inList(values: Collection<Any?>) {
        require(values.isNotEmpty()) { "inList requires at least one value" }
        val placeholders = values.joinToString(", ") { "?" }
        parts += "$this IN ($placeholders)"
        bindParams.addAll(values)
    }

    /** `COLUMN NOT IN (?, ?, ...)` */
    infix fun String.notInList(values: Collection<Any?>) {
        require(values.isNotEmpty()) { "notInList requires at least one value" }
        val placeholders = values.joinToString(", ") { "?" }
        parts += "$this NOT IN ($placeholders)"
        bindParams.addAll(values)
    }

    /** `COLUMN IS NULL` */
    fun String.isNull() {
        parts += "$this IS NULL"
    }

    /** `COLUMN IS NOT NULL` */
    fun String.isNotNull() {
        parts += "$this IS NOT NULL"
    }

    /** `COLUMN LIKE ?` */
    infix fun String.like(pattern: String) {
        parts += "$this LIKE ?"
        bindParams += pattern
    }

    /** `COLUMN ILIKE ?` (Snowflake case-insensitive LIKE) */
    infix fun String.ilike(pattern: String) {
        parts += "$this ILIKE ?"
        bindParams += pattern
    }

    /** Combine a group of predicates with OR. */
    fun or(block: WhereBuilder.() -> Unit) {
        val inner = WhereBuilder().apply(block)
        val innerSql = inner.build()
        if (innerSql.isNotBlank()) {
            parts += "($innerSql)"
            bindParams.addAll(inner.params())
        }
    }

    internal fun build(): String = parts.joinToString(" AND ")
    internal fun params(): List<Any?> = bindParams.toList()
}
