package io.kotlinsnowflake.query

/**
 * DSL for building Snowflake SELECT statements.
 *
 * Values are always bound as JDBC parameters, never interpolated into the SQL text.
 * Identifiers and expressions - table names, columns, `GROUP BY`, `HAVING`, `ORDER BY` -
 * are passed through as written, so they are your responsibility to keep trusted.
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
 * val rows: List<Map<String, String?>> = query.fetch()
 * ```
 */
class SelectBuilder {

    private var columns: List<String>  = listOf("*")
    private var table: String          = ""
    private var whereClause: String?   = null
    private var whereParams: List<Any?> = emptyList()
    private var groupByClause: String? = null
    private var havingClause: String?  = null
    private var havingParams: List<Any?> = emptyList()
    private var orderByClause: String? = null
    private var limitValue: Int?       = null
    private var offsetValue: Int?      = null

    /** Specify SELECT columns. Defaults to `*`. */
    fun columns(vararg cols: String) {
        require(cols.isNotEmpty()) { "columns() requires at least one column" }
        columns = cols.toList()
    }

    /** Specify the FROM table. Accepts fully-qualified names: `DB.SCHEMA.TABLE`. */
    fun from(tableName: String) {
        require(tableName.isNotBlank()) { "from() requires a table name" }
        table = tableName
    }

    /** Add a WHERE clause using the predicate DSL. Replaces any previous WHERE clause. */
    fun where(block: WhereBuilder.() -> Unit) {
        val predicate = WhereBuilder().apply(block).predicate()
        whereClause = predicate?.render()
        whereParams = predicate?.params.orEmpty()
    }

    /** Raw WHERE clause for cases the DSL doesn't cover. Replaces any previous WHERE clause. */
    fun whereRaw(sql: String, vararg params: Any?) {
        whereClause = sql
        whereParams = params.toList()
    }

    fun groupBy(vararg cols: String) {
        require(cols.isNotEmpty()) { "groupBy() requires at least one column" }
        groupByClause = cols.joinToString(", ")
    }

    /** Add a HAVING clause. Replaces any previous HAVING clause. */
    fun having(sql: String, vararg params: Any?) {
        havingClause = sql
        havingParams = params.toList()
    }

    /** Order by one or more columns. Use [SortOrder.ASC] or [SortOrder.DESC]. */
    fun orderBy(vararg cols: Pair<String, SortOrder>) {
        require(cols.isNotEmpty()) { "orderBy() requires at least one column" }
        orderByClause = cols.joinToString(", ") { (col, dir) -> "$col ${dir.name}" }
    }

    /** Convenience: order by a single column ascending. */
    fun orderBy(col: String, direction: SortOrder = SortOrder.ASC) {
        orderByClause = "$col ${direction.name}"
    }

    fun limit(n: Int) {
        require(n > 0) { "limit must be positive, was $n" }
        limitValue = n
    }

    fun offset(n: Int) {
        require(n >= 0) { "offset must not be negative, was $n" }
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

    /**
     * Bind values in placeholder order. WHERE precedes HAVING in the generated SQL, so it
     * precedes it here too - regardless of the order the DSL functions were called in.
     */
    internal fun params(): List<Any?> = whereParams + havingParams
}

enum class SortOrder { ASC, DESC }

// -------------------------------------------------------------------------------------------------

/**
 * Builds a WHERE clause from individual predicates.
 *
 * Predicates declared directly in the block are combined with AND. Use [or] and [and] to
 * nest groups; a nested group is parenthesized, so its combinator binds tighter:
 *
 * ```kotlin
 * where {
 *     "ACCOUNT_ID" eq 42
 *     or {
 *         "STATUS" eq "ACTIVE"
 *         "STATUS" eq "PAUSED"
 *     }
 * }
 * // ACCOUNT_ID = ? AND (STATUS = ? OR STATUS = ?)
 * ```
 */
class WhereBuilder internal constructor(private val combinator: Combinator) {

    /** Predicates declared at this level are combined with AND. */
    constructor() : this(Combinator.AND)

    private val children: MutableList<Predicate> = mutableListOf()

    /** `COLUMN = ?` */
    infix fun String.eq(value: Any?) {
        children += Comparison("$this = ?", listOf(value))
    }

    /** `COLUMN != ?` */
    infix fun String.neq(value: Any?) {
        children += Comparison("$this != ?", listOf(value))
    }

    /** `COLUMN > ?` */
    infix fun String.gt(value: Any?) {
        children += Comparison("$this > ?", listOf(value))
    }

    /** `COLUMN >= ?` */
    infix fun String.gte(value: Any?) {
        children += Comparison("$this >= ?", listOf(value))
    }

    /** `COLUMN < ?` */
    infix fun String.lt(value: Any?) {
        children += Comparison("$this < ?", listOf(value))
    }

    /** `COLUMN <= ?` */
    infix fun String.lte(value: Any?) {
        children += Comparison("$this <= ?", listOf(value))
    }

    /** `COLUMN BETWEEN ? AND ?` */
    infix fun String.between(range: Pair<Any?, Any?>) {
        children += Comparison("$this BETWEEN ? AND ?", listOf(range.first, range.second))
    }

    /** `COLUMN IN (?, ?, ...)` */
    infix fun String.inList(values: Collection<Any?>) {
        require(values.isNotEmpty()) { "inList requires at least one value" }
        children += Comparison("$this IN (${placeholders(values)})", values.toList())
    }

    /** `COLUMN NOT IN (?, ?, ...)` */
    infix fun String.notInList(values: Collection<Any?>) {
        require(values.isNotEmpty()) { "notInList requires at least one value" }
        children += Comparison("$this NOT IN (${placeholders(values)})", values.toList())
    }

    /** `COLUMN IS NULL` */
    fun String.isNull() {
        children += Comparison("$this IS NULL")
    }

    /** `COLUMN IS NOT NULL` */
    fun String.isNotNull() {
        children += Comparison("$this IS NOT NULL")
    }

    /** `COLUMN LIKE ?` */
    infix fun String.like(pattern: String) {
        children += Comparison("$this LIKE ?", listOf(pattern))
    }

    /** `COLUMN ILIKE ?` (Snowflake case-insensitive LIKE) */
    infix fun String.ilike(pattern: String) {
        children += Comparison("$this ILIKE ?", listOf(pattern))
    }

    /** Raw predicate for cases the DSL doesn't cover. Values are still bound as parameters. */
    fun raw(sql: String, vararg params: Any?) {
        children += Comparison(sql, params.toList())
    }

    /** Combine a group of predicates with OR. */
    fun or(block: WhereBuilder.() -> Unit) = nest(Combinator.OR, block)

    /** Combine a group of predicates with AND. Useful inside an [or] group. */
    fun and(block: WhereBuilder.() -> Unit) = nest(Combinator.AND, block)

    private fun nest(combinator: Combinator, block: WhereBuilder.() -> Unit) {
        WhereBuilder(combinator).apply(block).predicate()?.let { children += it }
    }

    private fun placeholders(values: Collection<Any?>) = values.joinToString(", ") { "?" }

    /** The predicate tree for this level, or null if no predicates were declared. */
    internal fun predicate(): Predicate? = when (children.size) {
        0    -> null
        1    -> children.single()
        else -> Group(combinator, children.toList())
    }
}
