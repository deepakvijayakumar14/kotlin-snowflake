package io.kotlinsnowflake

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

/**
 * Smoke test that the client can authenticate against a real Snowflake account and
 * round-trip a trivial query.
 *
 * Skipped unless SNOWFLAKE_ACCOUNT / SNOWFLAKE_USERNAME / SNOWFLAKE_PASSWORD are set,
 * so a local `./gradlew integrationTest` without credentials reports skipped rather
 * than failing. See .env.example for the full set of variables.
 */
class ConnectivityIntegrationTest : DescribeSpec({

    val credentialsPresent = listOf("SNOWFLAKE_ACCOUNT", "SNOWFLAKE_USERNAME", "SNOWFLAKE_PASSWORD")
        .all { !System.getenv(it).isNullOrBlank() }

    fun client() = snowflake {
        account   = env("SNOWFLAKE_ACCOUNT")
        username  = env("SNOWFLAKE_USERNAME")
        password  = env("SNOWFLAKE_PASSWORD")
        database  = System.getenv("SNOWFLAKE_DATABASE")
        schema    = System.getenv("SNOWFLAKE_SCHEMA")
        warehouse = System.getenv("SNOWFLAKE_WAREHOUSE")
        role      = System.getenv("SNOWFLAKE_ROLE")
    }

    describe("SnowflakeClient against a live account") {

        it("round-trips a literal via query()").config(enabled = credentialsPresent) {
            client().use { c ->
                val values = c.query("SELECT 1 AS ONE") { it.int("ONE") }
                values shouldBe listOf(1)
            }
        }

        it("streams rows via the query DSL").config(enabled = credentialsPresent) {
            client().use { c ->
                val rows = c.select {
                    columns("SEQ4() AS N")
                    from("TABLE(GENERATOR(ROWCOUNT => 5))")
                }.fetch { it.long("N") }

                rows shouldHaveSize 5
            }
        }
    }
})
