package io.kotlinsnowflake

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration that Hikari or the driver would silently clamp, ignore or misread is
 * rejected here instead, so it surfaces at construction rather than as a setting that
 * looks applied but never takes effect.
 */
class SnowflakeConfigTest : DescribeSpec({

    fun config(block: SnowflakeConfig.Builder.() -> Unit = {}) =
        SnowflakeConfig.Builder().apply {
            account  = "test-account"
            username = "test-user"
            password = "test-password"
        }.apply(block).build()

    fun rejects(block: SnowflakeConfig.Builder.() -> Unit) =
        shouldThrow<IllegalArgumentException> { config(block) }.message.orEmpty()

    describe("credentials") {

        it("requires an account and a username") {
            rejects { account = "" } shouldContain "account must not be blank"
            rejects { username = " " } shouldContain "username must not be blank"
        }

        it("requires one of password or privateKeyPath") {
            rejects { password = null } shouldContain "Either password or privateKeyPath"
        }

        it("treats a blank credential as absent") {
            // An unset environment variable resolving to "" would otherwise look like a
            // credential and fail at connect time instead of here.
            rejects { password = "" } shouldContain "Either password or privateKeyPath"
            rejects {
                password = null
                privateKeyPath = "  "
            } shouldContain "Either password or privateKeyPath"
        }

        it("rejects password and key-pair auth together") {
            rejects { privateKeyPath = "/keys/rsa_key.p8" } shouldContain "not both"
        }

        it("rejects a passphrase without a key") {
            rejects { privateKeyPassphrase = "secret" } shouldContain
                "privateKeyPassphrase was set without privateKeyPath"
        }

        it("accepts key-pair auth on its own") {
            val cfg = config {
                password = null
                privateKeyPath = "/keys/rsa_key.p8"
                privateKeyPassphrase = "secret"
            }
            cfg.privateKeyPath shouldBe "/keys/rsa_key.p8"
        }
    }

    describe("query timeout") {

        it("rejects a negative or infinite timeout") {
            rejects { queryTimeout = (-1).seconds } shouldContain "queryTimeout"
            rejects { queryTimeout = Duration.INFINITE } shouldContain "queryTimeout"
        }

        it("converts to whole seconds") {
            config { queryTimeout = 90.seconds }.queryTimeoutSeconds shouldBe 90
        }

        it("treats zero as no limit") {
            config { queryTimeout = Duration.ZERO }.queryTimeoutSeconds shouldBe 0
        }

        it("rounds a sub-second timeout up rather than down to no limit") {
            // Truncating 500ms to 0 would hand JDBC the code for "no limit", which is the
            // opposite of what was asked for.
            config { queryTimeout = 500.milliseconds }.queryTimeoutSeconds shouldBe 1
        }

        it("clamps a timeout too large for the int JDBC takes") {
            // inWholeSeconds.toInt() would wrap this to a negative, which JDBC rejects.
            config { queryTimeout = 1_000_000.hours }.queryTimeoutSeconds shouldBe Int.MAX_VALUE
        }
    }

    describe("fetch size") {

        it("requires a positive fetch size") {
            rejects { fetchSize = 0 } shouldContain "fetchSize must be positive"
            rejects { fetchSize = -1 } shouldContain "fetchSize must be positive"
        }
    }

    describe("pool") {

        fun rejectsPool(block: SnowflakeConfig.PoolConfig.Builder.() -> Unit) =
            shouldThrow<IllegalArgumentException> { config { pool(block) } }.message.orEmpty()

        it("requires a positive maxSize") {
            rejectsPool { maxSize = 0 } shouldContain "maxSize must be positive"
        }

        it("requires minIdle within 0..maxSize") {
            rejectsPool { minIdle = -1 } shouldContain "minIdle"
            rejectsPool {
                maxSize = 4
                minIdle = 5
            } shouldContain "minIdle"
        }

        it("rejects a connectionTimeout Hikari would clamp") {
            rejectsPool { connectionTimeout = 100.milliseconds } shouldContain "connectionTimeout"
        }

        it("rejects a maxLifetime Hikari would replace with its default") {
            rejectsPool { maxLifetime = 10.seconds } shouldContain "maxLifetime"
        }

        it("rejects an idleTimeout that reaches maxLifetime") {
            rejectsPool {
                maxLifetime = 5.minutes
                idleTimeout = 5.minutes
            } shouldContain "idleTimeout"
        }

        it("rejects a keepaliveTime at or beyond maxLifetime") {
            // The old default: a 3h keepalive against a 30min maxLifetime, which Hikari
            // disabled outright, so connections were never actually probed.
            rejectsPool { keepaliveTime = 3.hours } shouldContain "keepaliveTime"
        }

        it("allows keepalive to be turned off") {
            config { pool { keepaliveTime = Duration.ZERO } }.pool.keepaliveTime shouldBe
                Duration.ZERO
        }

        it("ships defaults that Hikari accepts as given") {
            val pool = config().pool
            pool.keepaliveTime shouldBe 5.minutes
            (pool.keepaliveTime < pool.maxLifetime) shouldBe true
            (pool.idleTimeout < pool.maxLifetime) shouldBe true
        }
    }
})
