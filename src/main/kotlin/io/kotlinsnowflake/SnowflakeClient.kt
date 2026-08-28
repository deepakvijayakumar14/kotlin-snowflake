package io.kotlinsnowflake

import io.kotlinsnowflake.pool.ConnectionPool
import io.kotlinsnowflake.query.Row
import io.kotlinsnowflake.query.RowMapper
import io.kotlinsnowflake.query.SelectBuilder
import io.kotlinsnowflake.query.toColumnMap
import io.kotlinsnowflake.tx.BatchBinder
import io.kotlinsnowflake.tx.TransactionScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.sql.Connection

/**
 * Coroutine-native Kotlin client for Snowflake.
 *
 * All JDBC operations are dispatched on [SnowflakeConfig.dispatcher] (default:
 * [kotlinx.coroutines.Dispatchers.IO]) so they never block the calling coroutine's thread,
 * and every statement carries [SnowflakeConfig.queryTimeout].
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
class SnowflakeClient internal constructor(
    private val config: SnowflakeConfig,
    private val pool: ConnectionPool,
) : Closeable {

    /**
     * Creates a client backed by its own HikariCP connection pool.
     * This is the constructor callers use; the pool-injecting one exists so tests can
     * substitute a pool without opening a real connection.
     */
    constructor(config: SnowflakeConfig) : this(config, ConnectionPool(config))

    private val log = LoggerFactory.getLogger(SnowflakeClient::class.java)

    private val timeout = config.queryTimeoutSeconds

    // -- Query (list) -------------------------------------------------------------------------

    /**
     * Executes a SELECT and maps each row using [mapper].
     * Results are fully materialized into memory before returning; use [stream] for
     * result sets that should not be.
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
     * Executes a SELECT and returns each row as a column-label-to-string map, for cases
     * where the shape is not known up front. Results are fully materialized into memory.
     */
    suspend fun query(sql: String, vararg params: Any?): List<Map<String, String?>> =
        withContext(config.dispatcher) {
            log.debug("query (raw): {}", sql)
            pool.borrow().use { conn ->
                conn.executeQuery(sql, params) { rs ->
                    val results = mutableListOf<Map<String, String?>>()
                    while (rs.next()) results += rs.toColumnMap()
                    results
                }
            }
        }

    // -- Stream (Flow) ------------------------------------------------------------------------

    /**
     * Streams query results as a [Flow], fetching [SnowflakeConfig.fetchSize] rows at a time.
     * Suitable for large result sets that should not be fully loaded into memory.
     *
     * The connection is held for as long as the flow is collected and released when
     * collection ends, including on cancellation.
     *
     * ```kotlin
     * client.stream("SELECT KEYWORD_ID, BID FROM KEYWORD_STATS") { row ->
     *     KeywordStat(row.long("KEYWORD_ID"), row.double("BID"))
     * }.collect { ... }
     * ```
     */
    fun <T> stream(sql: String, vararg params: Any?, mapper: RowMapper<T>): Flow<T> =
        // The JDBC work runs on config.dispatcher via flowOn, not withContext: a flow may
        // only emit from the context it was collected in, so the context change has to
        // happen upstream of the emission rather than around it.
        flow {
            log.debug("stream: {}", sql)
            pool.borrow().use { conn ->
                conn.streamStatement(sql, params).use { ps ->
                    ps.executeQuery().use { rs ->
                        val row = Row(rs)
                        while (rs.next()) emit(mapper.map(row))
                    }
                }
            }
        }.flowOn(config.dispatcher)

    /** Streams rows as column-label-to-string maps. */
    fun stream(sql: String, vararg params: Any?): Flow<Map<String, String?>> =
        flow {
            log.debug("stream (raw): {}", sql)
            pool.borrow().use { conn ->
                conn.streamStatement(sql, params).use { ps ->
                    ps.executeQuery().use { rs ->
                        while (rs.next()) emit(rs.toColumnMap())
                    }
                }
            }
        }.flowOn(config.dispatcher)

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
                conn.prepared(sql, params, timeout).use { ps -> ps.executeUpdate() }
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
        binder: BatchBinder.(T) -> Unit
    ): IntArray =
        withContext(config.dispatcher) {
            log.debug("batch: {}", sql)
            pool.borrow().use { conn ->
                conn.prepared(sql, emptyArray(), timeout).use { ps ->
                    rows.forEach { item ->
                        BatchBinder(ps).binder(item)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
            }
        }

    // -- Transaction --------------------------------------------------------------------------

    /**
     * Executes [block] within a database transaction.
     * The transaction is committed on success or rolled back on any failure, including
     * cancellation.
     *
     * ```kotlin
     * val result = client.transaction {
     *     execute("UPDATE CAMPAIGNS SET STATUS = ? WHERE ID = ?", "PAUSED", id)
     *     execute("INSERT INTO AUDIT_LOG (CAMPAIGN_ID, ACTION) VALUES (?, ?)", id, "PAUSED")
     *     query("SELECT STATUS FROM CAMPAIGNS WHERE ID = ?", id) { it.string("STATUS") }.first()
     * }
     * ```
     */
    // Any failure inside the block must roll back, so the broad catch is deliberate.
    @Suppress("TooGenericExceptionCaught")
    suspend fun <T> transaction(block: suspend TransactionScope.() -> T): T =
        withContext(config.dispatcher) {
            pool.borrow().use { conn ->
                conn.autoCommit = false
                try {
                    val result = TransactionScope(conn, timeout).block()
                    conn.commit()
                    result
                } catch (original: Throwable) {
                    log.warn("Transaction rolled back due to: {}", original.message)
                    try {
                        conn.rollback()
                    } catch (rollbackFailure: Throwable) {
                        // The application's exception explains what went wrong; a failed
                        // rollback is a detail of the cleanup, so it rides along suppressed
                        // rather than replacing it.
                        original.addSuppressed(rollbackFailure)
                    }
                    throw original
                } finally {
                    // A connection returned to the pool with autoCommit still off would
                    // silently swallow the next borrower's writes. If restoring it fails the
                    // connection is broken anyway; don't let that mask the real failure.
                    runCatching { conn.autoCommit = true }
                        .onFailure { log.warn("Failed to restore autoCommit: {}", it.message) }
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
    ): T = prepared(sql, params, timeout).use { ps -> ps.executeQuery().use(block) }

    /** A statement configured to pull rows in [SnowflakeConfig.fetchSize] batches. */
    private fun Connection.streamStatement(sql: String, params: Array<out Any?>) =
        prepared(sql, params, timeout).apply { fetchSize = config.fetchSize }

    // -- Lifecycle ----------------------------------------------------------------------------

    override fun close() = pool.close()
}

// -------------------------------------------------------------------------------------------------

/**
 * A SELECT query built via the DSL, ready to execute.
 *
 * The spread operator is unavoidable here: DSL params arrive as a [List] and the
 * client's query/stream entry points take `vararg`.
 */
@Suppress("SpreadOperator")
class PreparedSelect internal constructor(
    private val client: SnowflakeClient,
    private val sql: String,
    private val params: List<Any?>,
) {
    /** Fetch results mapped by [mapper]. */
    suspend fun <T> fetch(mapper: RowMapper<T>): List<T> =
        client.query(sql, *params.toTypedArray(), mapper = mapper)

    /** Fetch raw rows as column-label-to-string maps. */
    suspend fun fetch(): List<Map<String, String?>> =
        client.query(sql, *params.toTypedArray())

    /** Stream results as a [Flow] mapped by [mapper]. */
    fun <T> stream(mapper: RowMapper<T>): Flow<T> =
        client.stream(sql, *params.toTypedArray(), mapper = mapper)

    /** Stream raw rows as column-label-to-string maps. */
    fun stream(): Flow<Map<String, String?>> =
        client.stream(sql, *params.toTypedArray())

    /** Returns the generated SQL (useful for debugging). */
    fun toSql(): String = sql
}
