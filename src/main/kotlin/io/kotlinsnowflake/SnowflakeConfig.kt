package io.kotlinsnowflake

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
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

/** Default HikariCP pool size; matches Hikari's own default. */
private const val DEFAULT_MAX_POOL_SIZE = 10

/** Default JDBC fetch size, i.e. rows pulled from Snowflake per round trip while streaming. */
private const val DEFAULT_FETCH_SIZE = 1_000

/**
 * Immutable configuration for [SnowflakeClient].
 * Build via [SnowflakeConfig.Builder] or the [snowflake] DSL.
 *
 * @param queryTimeout applied to every statement this client issues, as
 *   [java.sql.Statement.setQueryTimeout]. [Duration.ZERO] means no limit.
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

        // Blank counts as absent: an unset environment variable that resolves to "" would
        // otherwise look like a credential and fail much later, at connect time.
        val hasPassword = !password.isNullOrBlank()
        val hasKeyPair  = !privateKeyPath.isNullOrBlank()
        require(hasPassword || hasKeyPair) {
            "Either password or privateKeyPath must be provided"
        }
        require(!(hasPassword && hasKeyPair)) {
            "Set either password or privateKeyPath, not both: the driver would silently " +
                "pick one and the other would look configured but be ignored"
        }
        require(privateKeyPassphrase == null || hasKeyPair) {
            "privateKeyPassphrase was set without privateKeyPath"
        }

        require(queryTimeout.isFinite() && !queryTimeout.isNegative()) {
            "queryTimeout must be a finite, non-negative duration, was $queryTimeout"
        }
        require(fetchSize > 0) { "fetchSize must be positive, was $fetchSize" }
    }

    /**
     * [queryTimeout] as whole seconds, clamped to the `int` that JDBC accepts.
     * `0` disables the timeout; a sub-second timeout rounds up to 1s rather than to
     * `0`, which JDBC would read as "no limit" - the opposite of what was asked for.
     */
    internal val queryTimeoutSeconds: Int =
        when {
            queryTimeout == Duration.ZERO -> 0
            else -> queryTimeout.inWholeSeconds.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
        }

    // -- Pool config ---------------------------------------------------------------------------

    /**
     * HikariCP pool settings.
     *
     * Values that Hikari would silently clamp or ignore are rejected here instead, so a
     * misconfigured pool fails at construction rather than quietly not doing its job.
     *
     * @param keepaliveTime how often an idle connection is pinged. Must be shorter than
     *   [maxLifetime] or Hikari disables it. [Duration.ZERO] turns keepalive off.
     */
    data class PoolConfig(
        val maxSize: Int,
        val minIdle: Int,
        val connectionTimeout: Duration,
        val idleTimeout: Duration,
        val maxLifetime: Duration,
        val keepaliveTime: Duration,
    ) {

        init {
            require(maxSize > 0) { "pool.maxSize must be positive, was $maxSize" }
            require(minIdle in 0..maxSize) {
                "pool.minIdle must be between 0 and maxSize ($maxSize), was $minIdle"
            }
            require(connectionTimeout >= HIKARI_CONNECTION_TIMEOUT_FLOOR) {
                "pool.connectionTimeout must be at least $HIKARI_CONNECTION_TIMEOUT_FLOOR, " +
                    "was $connectionTimeout (Hikari would raise it to its 30s default)"
            }
            require(maxLifetime >= HIKARI_LIFETIME_FLOOR) {
                "pool.maxLifetime must be at least $HIKARI_LIFETIME_FLOOR, was $maxLifetime " +
                    "(Hikari would replace it with its 30min default)"
            }
            require(idleTimeout == Duration.ZERO || idleTimeout >= HIKARI_IDLE_TIMEOUT_FLOOR) {
                "pool.idleTimeout must be zero or at least $HIKARI_IDLE_TIMEOUT_FLOOR, " +
                    "was $idleTimeout"
            }
            // Hikari's own test is idleTimeout + 1s > maxLifetime, so leave that margin.
            require(idleTimeout + 1.seconds <= maxLifetime) {
                "pool.idleTimeout ($idleTimeout) must be at least a second shorter than " +
                    "maxLifetime ($maxLifetime) or Hikari disables it"
            }
            require(keepaliveTime == Duration.ZERO || keepaliveTime >= HIKARI_LIFETIME_FLOOR) {
                "pool.keepaliveTime must be zero or at least $HIKARI_LIFETIME_FLOOR, " +
                    "was $keepaliveTime (Hikari would disable it)"
            }
            require(keepaliveTime < maxLifetime) {
                "pool.keepaliveTime ($keepaliveTime) must be shorter than maxLifetime " +
                    "($maxLifetime) or Hikari disables it, leaving connections unprobed"
            }
        }

        class Builder {
            var maxSize: Int                = DEFAULT_MAX_POOL_SIZE
            var minIdle: Int                = 2
            var connectionTimeout: Duration = 30.seconds
            var idleTimeout: Duration       = 10.minutes
            var maxLifetime: Duration       = 30.minutes

            /**
             * How often to probe an idle connection. Must stay below [maxLifetime].
             * Set to [Duration.ZERO] to disable; connections are recycled at
             * [maxLifetime] regardless.
             */
            var keepaliveTime: Duration     = 5.minutes

            internal fun build() = PoolConfig(
                maxSize, minIdle, connectionTimeout, idleTimeout, maxLifetime, keepaliveTime,
            )
        }

        private companion object {
            /** Hikari clamps a shorter connectionTimeout up to its default. */
            private val HIKARI_CONNECTION_TIMEOUT_FLOOR = 250.milliseconds

            /** Hikari rejects a shorter maxLifetime and disables a shorter keepaliveTime. */
            private val HIKARI_LIFETIME_FLOOR = 30.seconds

            /** Hikari clamps a shorter idleTimeout up to its default. */
            private val HIKARI_IDLE_TIMEOUT_FLOOR = 10.seconds
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

        /** Applied to every statement. [Duration.ZERO] means no limit. */
        var queryTimeout: Duration             = 5.minutes
        var fetchSize: Int                     = DEFAULT_FETCH_SIZE
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
