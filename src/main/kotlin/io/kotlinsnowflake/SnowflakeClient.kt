package io.kotlinsnowflake

import io.kotlinsnowflake.pool.ConnectionPool
import io.kotlinsnowflake.query.Row
import io.kotlinsnowflake.query.RowMapper
import io.kotlinsnowflake.query.SelectBuilder
import io.kotlinsnowflake.tx.TransactionScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.sql.Connection

/**
 * Coroutine-native Kotlin client for Snowflake.
 *
 * All JDBC operations are dispatched on [SnowflakeConfig.dispatcher] (default: [Dispatchers.IO])
 * so they never block the calling coroutine's thread.
 *
 * Create via the [snowflake] DSL:
 *
 * ```kotlin
 * val client = snowflake {
 *     account   = "myorg-myaccount"
 *     username  = "service_user"
 *     password  = env("SNOWFLAKE_PASSWORD")
 *     database  = "MY_DATABASE"
 *     warehouse = "COMPUTE_WH"
 * }
 * ```
 *
 * Remember to [close] the client (or use [use]) when done to release pool connections.
 */
class SnowflakeClient(private val config: SnowflakeConfig) : Closeable {

    private val log  = LoggerFactory.getLogger(SnowflakeClient::class.java)
    private val pool = ConnectionPool(config)

    // -- Query (list) -------------------------------------------------------------------------

    /**
     * Executes a SELECT and maps each row using [mapper].
     *
     * ```kotlin
     * val campaigns = client.query("SELECT ID, NAME FROM CAMPAIGNS WHERE ACTIVE = ?", true) {
     *     Campaign(id = it.long("ID"), name = it.string("NAME"))
     * }
     * ```
     */
    suspend fun <T> query(sql: String, vararg params: Any?, mapper: RowMapper<T>): List<T> =
        withContext(config.dispatcher) {
            log.debug("query: {}", sql)
            pool.borrow().use { conn ->
                conn.executeQuery(sql, params) { rs ->
                    val results = mutableListOf<T>()
                    val row = Row(rs)
                    while (rs.next()) results += mapper.map(row)
                    results
                }
            }
        }

    /**
     * Executes a SELECT and returns raw [Row] objects.
     * Results are fully materialized into memory before returning.
     */
    suspend fun query(sql: String, vararg params: Any?): List<Map<String, String?>> =
        withContext(config.dispatcher) {
            log.debug("query (raw): {}", sql)
            pool.borrow().use { conn ->
                conn.executeQuery(sql, params) { rs ->
                    val results = mutableListOf<Map<String, String?>>()
                    while (rs.next()) {
                        results += (1..rs.metaData.columnCount).associate {
                            rs.metaData.getColumnName(it) to rs.getString(it)
                        }
                    }
                    results
                }
            }
        }

    // -- Stream (Flow) ------------------------------------------------------------------------

    /**
     * Streams query results as a [Flow], fetching [SnowflakeConfig.fetchSize] rows at a time.
     * Suitable for large result sets that should not be fully loaded into memory.
     *
     * ```kotlin
     * client.stream("SELECT KEYWORD_ID, BID FROM KEYWORD_STATS")
     *     .map { row -> ... }
     *     .collect { ... }
     * ```
     */
    fun <T> stream(sql: String, vararg params: Any?, mapper: RowMapper<T>): Flow<T> = flow {
        withContext(config.dispatcher) {
            pool.borrow().use { conn ->
                conn.prepareStatement(sql).use { ps ->
                    ps.fetchSize = config.fetchSize
                    params.forEachIndexed { i, v -> ps.setObject(i + 1, v) }
                    ps.executeQuery().use { rs ->
                        val row = Row(rs)
                        while (rs.next()) emit(mapper.map(row))
                    }
                }
            }
        }
    }

    /** Streams raw [Row] objects as a [Flow]. */
    fun stream(sql: String, vararg params: Any?): Flow<Map<String, String?>> = flow {
        withContext(config.dispatcher) {
            pool.borrow().use { conn ->
                conn.prepareStatement(sql).use { ps ->
                    ps.fetchSize = config.fetchSize
                    params.forEachIndexed { i, v -> ps.setObject(i + 1, v) }
                    ps.executeQuery().use { rs ->
                        while (rs.next()) {
                            val map = (1..rs.metaData.columnCount).associate {
                                rs.metaData.getColumnName(it) to rs.getString(it)
                            }
                            emit(map)
                        }
                    }
                }
            }
        }
    }

    // -- Execute (DML) ------------------------------------------------------------------------

    /**
     * Executes a DML statement (INSERT, UPDATE, DELETE) and returns the affected row count.
     *
     * ```kotlin
     * val affected = client.execute(
     *     "UPDATE KEYWORDS SET BID = ? WHERE ID = ?",
     *     newBid, keywordId
     * )
     * ```
     */
    suspend fun execute(sql: String, vararg params: Any?): Int =
        withContext(config.dispatcher) {
            log.debug("execute: {}", sql)
            pool.borrow().use { conn ->
                conn.prepareStatement(sql).use { ps ->
                    params.forEachIndexed { i, v -> ps.setObject(i + 1, v) }
                    ps.executeUpdate()
                }
            }
        }

    // -- Batch --------------------------------------------------------------------------------

    /**
     * Executes a batch DML operation. More efficient than individual [execute] calls for bulk work.
     *
     * ```kotlin
     * client.batch(
     *     sql  = "INSERT INTO KEYWORD_BIDS (KEYWORD_ID, BID) VALUES (?, ?)",
     *     rows = keywords
     * ) { kw ->
     *     bind(kw.id, kw.bid)
     * }
     * ```
     */
    suspend fun <T> batch(
        sql: String,
        rows: Iterable<T>,
        binder: io.kotlinsnowflake.tx.BatchBinder.(T) -> Unit
    ): IntArray =
        withContext(config.dispatcher) {
            log.debug("batch: {}", sql)
            pool.borrow().use { conn ->
                conn.prepareStatement(sql).use { ps ->
                    rows.forEach { item ->
                        val b = io.kotlinsnowflake.tx.BatchBinder(ps)
                        b.binder(item)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
            }
        }

    // -- Transaction --------------------------------------------------------------------------

    /**
     * Executes [block] within a database transaction.
     * The transaction is committed on success or rolled back on any exception.
     *
     * ```kotlin
     * val result = client.transaction {
     *     execute("UPDATE CAMPAIGNS SET STATUS = ? WHERE ID = ?", "PAUSED", id)
     *     execute("INSERT INTO AUDIT_LOG (CAMPAIGN_ID, ACTION) VALUES (?, ?)", id, "PAUSED")
     *     query("SELECT STATUS FROM CAMPAIGNS WHERE ID = ?", id) { it.string("STATUS") }.first()
     * }
     * ```
     */
    suspend fun <T> transaction(block: suspend TransactionScope.() -> T): T =
        withContext(config.dispatcher) {
            pool.borrow().use { conn ->
                conn.autoCommit = false
                try {
                    val result = TransactionScope(conn).block()
                    conn.commit()
                    result
                } catch (ex: Exception) {
                    log.warn("Transaction rolled back due to: {}", ex.message)
                    conn.rollback()
                    throw ex
                } finally {
                    conn.autoCommit = true
                }
            }
        }

    // -- Query DSL ----------------------------------------------------------------------------

    /**
     * Builds a SELECT query using the DSL and returns a [PreparedSelect] ready to fetch.
     *
     * ```kotlin
     * val rows = client.select {
     *     columns("CAMPAIGN_ID", "SUM(SPEND) AS TOTAL")
     *     from("AD_PERFORMANCE")
     *     where { "DATE" between (start to end) }
     *     groupBy("CAMPAIGN_ID")
     *     orderBy("TOTAL" to SortOrder.DESC)
     *     limit(100)
     * }.fetch { row -> row.long("CAMPAIGN_ID") to row.double("TOTAL") }
     * ```
     */
    fun select(block: SelectBuilder.() -> Unit): PreparedSelect {
        val builder = SelectBuilder().apply(block)
        return PreparedSelect(this, builder.buildSql(), builder.params())
    }

    // -- Internal helpers ---------------------------------------------------------------------

    private fun <T> Connection.executeQuery(
        sql: String,
        params: Array<out Any?>,
        block: (java.sql.ResultSet) -> T
    ): T = prepareStatement(sql).use { ps ->
        params.forEachIndexed { i, v -> ps.setObject(i + 1, v) }
        val timeoutSecs = config.queryTimeout.inWholeSeconds.toInt()
        if (timeoutSecs > 0) ps.queryTimeout = timeoutSecs
        ps.executeQuery().use(block)
    }

    // -- Lifecycle ----------------------------------------------------------------------------

    override fun close() = pool.close()
}

// -------------------------------------------------------------------------------------------------

/** A SELECT query built via the DSL, ready to execute. */
class PreparedSelect internal constructor(
    private val client: SnowflakeClient,
    private val sql: String,
    private val params: List<Any?>,
) {
    /** Fetch results mapped by [mapper]. */
    suspend fun <T> fetch(mapper: RowMapper<T>): List<T> =
        client.query(sql, *params.toTypedArray(), mapper = mapper)

    /** Fetch raw rows as column-name-to-string maps. */
    suspend fun fetch(): List<Map<String, String?>> =
        client.query(sql, *params.toTypedArray())

    /** Stream results as a [Flow] mapped by [mapper]. */
    fun <T> stream(mapper: RowMapper<T>): Flow<T> =
        client.stream(sql, *params.toTypedArray(), mapper = mapper)

    /** Returns the generated SQL (useful for debugging). */
    fun toSql(): String = sql
}
