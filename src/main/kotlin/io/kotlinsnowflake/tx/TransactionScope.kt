package io.kotlinsnowflake.tx

import io.kotlinsnowflake.prepared
import io.kotlinsnowflake.query.Row
import io.kotlinsnowflake.query.RowMapper
import io.kotlinsnowflake.query.toColumnMap
import java.sql.Connection

/**
 * Provides transactional query execution within a [SnowflakeClient.transaction] block.
 *
 * All operations execute on the same [Connection] with autoCommit disabled, and carry the
 * same statement timeout as queries issued outside a transaction.
 * The transaction is committed automatically on successful completion or rolled back on any
 * exception.
 *
 * ```kotlin
 * val status = client.transaction {
 *     execute("UPDATE CAMPAIGNS SET STATUS = ? WHERE ID = ?", "PAUSED", id)
 *     execute("INSERT INTO AUDIT_LOG (CAMPAIGN_ID, ACTION) VALUES (?, ?)", id, "PAUSED")
 *     query("SELECT STATUS FROM CAMPAIGNS WHERE ID = ?", id) { it.string("STATUS") }.first()
 * }
 * ```
 */
class TransactionScope internal constructor(
    private val connection: Connection,
    private val timeoutSeconds: Int,
) {

    /**
     * Executes a DML statement (INSERT, UPDATE, DELETE) and returns the affected row count.
     */
    fun execute(sql: String, vararg params: Any?): Int =
        connection.prepared(sql, params, timeoutSeconds).use { ps -> ps.executeUpdate() }

    /**
     * Executes a SELECT and maps results using the provided [mapper].
     */
    fun <T> query(sql: String, vararg params: Any?, mapper: RowMapper<T>): List<T> =
        connection.prepared(sql, params, timeoutSeconds).use { ps ->
            ps.executeQuery().use { rs ->
                val results = mutableListOf<T>()
                val row = Row(rs)
                while (rs.next()) results += mapper.map(row)
                results
            }
        }

    /**
     * Executes a SELECT and returns rows as column-label-to-string maps.
     * Note: [Row] is tied to the [java.sql.ResultSet] lifetime, so results are materialized
     * into maps before the ResultSet is closed.
     */
    fun query(sql: String, vararg params: Any?): List<Map<String, String?>> =
        connection.prepared(sql, params, timeoutSeconds).use { ps ->
            ps.executeQuery().use { rs ->
                val rows = mutableListOf<Map<String, String?>>()
                while (rs.next()) rows += rs.toColumnMap()
                rows
            }
        }

    /**
     * Executes multiple DML statements as a JDBC batch, returning per-statement update counts.
     */
    fun <T> batch(sql: String, items: Iterable<T>, binder: BatchBinder.(T) -> Unit): IntArray =
        connection.prepared(sql, emptyArray(), timeoutSeconds).use { ps ->
            items.forEach { item ->
                val b = BatchBinder(ps)
                b.binder(item)
                ps.addBatch()
            }
            ps.executeBatch()
        }
}

/** Binds positional parameters for a single batch row. */
class BatchBinder internal constructor(private val ps: java.sql.PreparedStatement) {
    private var index = 1
    fun bind(vararg values: Any?) = values.forEach { ps.setObject(index++, it) }
}
