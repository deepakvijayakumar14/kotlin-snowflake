package io.kotlinsnowflake

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Top-level entry point. Creates a [SnowflakeClient] using the builder DSL.
 *
 * ```kotlin
 * val client = snowflake {
 *     account   = "myorg-myaccount"
 *     username  = "my_user"
 *     password  = env("SNOWFLAKE_PASSWORD")
 *     database  = "MY_DATABASE"
 *     warehouse = "COMPUTE_WH"
 * }
 * ```
 */
fun snowflake(block: SnowflakeConfig.Builder.() -> Unit): SnowflakeClient =
    SnowflakeClient(SnowflakeConfig.Builder().apply(block).build())

/** Reads an environment variable, throwing a clear error if it is absent. */
fun env(name: String): String =
    System.getenv(name) ?: error("Required environment variable '$name' is not set")

// -------------------------------------------------------------------------------------------------

/**
 * Immutable configuration for [SnowflakeClient].
 * Build via [SnowflakeConfig.Builder] or the [snowflake] DSL.
 */
data class SnowflakeConfig(
    val account: String,
    val username: String,
    val password: String?,
    val privateKeyPath: String?,
    val privateKeyPassphrase: String?,
    val database: String?,
    val schema: String?,
    val warehouse: String?,
    val role: String?,
    val queryTimeout: Duration,
    val fetchSize: Int,
    val dispatcher: CoroutineDispatcher,
    val pool: PoolConfig,
) {

    init {
        require(account.isNotBlank()) { "account must not be blank" }
        require(username.isNotBlank()) { "username must not be blank" }
        require(password != null || privateKeyPath != null) {
            "Either password or privateKeyPath must be provided"
        }
    }

    // -- Pool config ---------------------------------------------------------------------------

    data class PoolConfig(
        val maxSize: Int,
        val minIdle: Int,
        val connectionTimeout: Duration,
        val idleTimeout: Duration,
        val maxLifetime: Duration,
    ) {
        class Builder {
            var maxSize: Int              = 10
            var minIdle: Int              = 2
            var connectionTimeout: Duration = 30.seconds
            var idleTimeout: Duration      = 10.minutes
            var maxLifetime: Duration      = 30.minutes

            internal fun build() = PoolConfig(maxSize, minIdle, connectionTimeout, idleTimeout, maxLifetime)
        }
    }

    // -- Top-level builder ---------------------------------------------------------------------

    class Builder {
        var account: String                    = ""
        var username: String                   = ""
        var password: String?                  = null
        var privateKeyPath: String?            = null
        var privateKeyPassphrase: String?      = null
        var database: String?                  = null
        var schema: String?                    = null
        var warehouse: String?                 = null
        var role: String?                      = null
        var queryTimeout: Duration             = 5.minutes
        var fetchSize: Int                     = 1_000
        var dispatcher: CoroutineDispatcher    = Dispatchers.IO

        private var poolBuilder = PoolConfig.Builder()

        /** Configure the HikariCP connection pool. */
        fun pool(block: PoolConfig.Builder.() -> Unit) {
            poolBuilder.apply(block)
        }

        internal fun build() = SnowflakeConfig(
            account              = account,
            username             = username,
            password             = password,
            privateKeyPath       = privateKeyPath,
            privateKeyPassphrase = privateKeyPassphrase,
            database             = database,
            schema               = schema,
            warehouse            = warehouse,
            role                 = role,
            queryTimeout         = queryTimeout,
            fetchSize            = fetchSize,
            dispatcher           = dispatcher,
            pool                 = poolBuilder.build(),
        )
    }
}
