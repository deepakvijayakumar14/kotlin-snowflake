package io.kotlinsnowflake.pool

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotlinsnowflake.SnowflakeConfig
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.sql.Connection

/**
 * HikariCP connection pool configured for Snowflake.
 *
 * Snowflake-specific defaults applied automatically:
 * - JDBC connection string with account, database, schema, warehouse, role
 * - autoCommit = true (Snowflake default)
 * - connectionTestQuery = "SELECT 1" (cheap liveness check)
 * - keepaliveTime set to prevent idle connection drops from Snowflake's 4-hour timeout
 */
internal class ConnectionPool(private val config: SnowflakeConfig) : Closeable {

    private val log = LoggerFactory.getLogger(ConnectionPool::class.java)

    private val dataSource: HikariDataSource = run {
        val hikari = HikariConfig().apply {
            driverClassName    = "net.snowflake.client.jdbc.SnowflakeDriver"
            jdbcUrl            = buildJdbcUrl(config)
            username           = config.username
            password           = config.password
            maximumPoolSize    = config.pool.maxSize
            minimumIdle        = config.pool.minIdle
            connectionTimeout  = config.pool.connectionTimeout.inWholeMilliseconds
            idleTimeout        = config.pool.idleTimeout.inWholeMilliseconds
            maxLifetime        = config.pool.maxLifetime.inWholeMilliseconds
            // Cheap liveness check - avoids full connection validation overhead
            connectionTestQuery = "SELECT 1"
            // Keep connections alive below Snowflake's 4-hour idle limit
            keepaliveTime      = 3 * 60 * 60 * 1000L
            isAutoCommit       = true
            poolName           = "kotlin-snowflake-pool"

            // Key-pair auth properties
            if (config.privateKeyPath != null) {
                addDataSourceProperty("private_key_file", config.privateKeyPath)
                config.privateKeyPassphrase?.let {
                    addDataSourceProperty("private_key_file_pwd", it)
                }
            }
        }

        log.info(
            "Initializing Snowflake connection pool [account={}, database={}, warehouse={}, maxSize={}]",
            config.account, config.database, config.warehouse, config.pool.maxSize
        )

        HikariDataSource(hikari)
    }

    fun borrow(): Connection = dataSource.connection

    override fun close() {
        log.info("Closing Snowflake connection pool")
        dataSource.close()
    }

    private fun buildJdbcUrl(config: SnowflakeConfig): String = buildString {
        append("jdbc:snowflake://")
        append(config.account)
        append(".snowflakecomputing.com/?")

        val params = mutableListOf<String>()
        config.database?.let  { params += "db=$it" }
        config.schema?.let    { params += "schema=$it" }
        config.warehouse?.let { params += "warehouse=$it" }
        config.role?.let      { params += "role=$it" }

        // Recommended Snowflake JDBC settings
        params += "application=kotlin-snowflake"
        params += "loginTimeout=30"

        append(params.joinToString("&"))
    }
}
