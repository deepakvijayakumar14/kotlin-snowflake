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
 * - keepaliveTime from [SnowflakeConfig.PoolConfig], which validates that it is shorter
 *   than maxLifetime - Hikari disables a keepalive that is not
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
            keepaliveTime      = config.pool.keepaliveTime.inWholeMilliseconds
            isAutoCommit       = true
            poolName           = "kotlin-snowflake-pool"

            // Key-pair auth properties
            if (config.privateKeyPath != null) {
                addDataSourceProperty("private_key_file", config.privateKeyPath)
                // private_key_file_pwd, not private_key_pwd: the latter does not exist in
                // SFSessionProperty for the pinned driver (3.16.0) and would be ignored.
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
