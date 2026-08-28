package io.kotlinsnowflake

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotlinsnowflake.query.SortOrder
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList

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

        it("fetches rows via the query DSL").config(enabled = credentialsPresent) {
            client().use { c ->
                val rows = c.select {
                    columns("SEQ4() AS N")
                    from("TABLE(GENERATOR(ROWCOUNT => 5))")
                }.fetch { it.long("N") }

                rows shouldHaveSize 5
            }
        }

        it("streams rows through a Flow").config(enabled = credentialsPresent) {
            client().use { c ->
                val rows = c.stream("SELECT SEQ4() AS N FROM TABLE(GENERATOR(ROWCOUNT => 5))") {
                    it.long("N")
                }.toList()

                rows shouldBe listOf(0L, 1L, 2L, 3L, 4L)
            }
        }

        it("streams via the query DSL").config(enabled = credentialsPresent) {
            client().use { c ->
                val rows = c.select {
                    columns("SEQ4() AS N")
                    from("TABLE(GENERATOR(ROWCOUNT => 5))")
                    orderBy("N" to SortOrder.ASC)
                }.stream { it.long("N") }.toList()

                rows shouldHaveSize 5
            }
        }

        it("releases the connection when a collector stops early")
            .config(enabled = credentialsPresent) {
                // A leaked connection here would exhaust the pool rather than fail loudly,
                // so run more rows than the pool can hold connections.
                client().use { c ->
                    repeat(POOL_EXHAUSTION_ROUNDS) {
                        val first = c.stream(
                            "SELECT SEQ4() AS N FROM TABLE(GENERATOR(ROWCOUNT => 100000))"
                        ) { row -> row.long("N") }.take(1).toList()

                        first shouldHaveSize 1
                    }
                }
            }

        it("applies OR semantics in the query DSL").config(enabled = credentialsPresent) {
            client().use { c ->
                val statuses = c.select {
                    columns("STATUS")
                    from("(SELECT 'ACTIVE' AS STATUS UNION ALL SELECT 'PAUSED' UNION ALL SELECT 'ENDED')")
                    where {
                        or {
                            "STATUS" eq "ACTIVE"
                            "STATUS" eq "PAUSED"
                        }
                    }
                    orderBy("STATUS" to SortOrder.ASC)
                }.fetch { it.string("STATUS") }

                // An AND here would match nothing, which is what the DSL used to generate.
                statuses shouldBe listOf("ACTIVE", "PAUSED")
            }
        }

        it("addresses a computed column by its alias").config(enabled = credentialsPresent) {
            client().use { c ->
                val rows = c.query("SELECT SUM(SEQ4()) AS TOTAL FROM TABLE(GENERATOR(ROWCOUNT => 3))")

                rows shouldBe listOf(mapOf("TOTAL" to "3"))
            }
        }
    }
}) {
    private companion object {
        /** More rounds than the default pool has connections, so a leak shows up. */
        private const val POOL_EXHAUSTION_ROUNDS = 12
    }
}
