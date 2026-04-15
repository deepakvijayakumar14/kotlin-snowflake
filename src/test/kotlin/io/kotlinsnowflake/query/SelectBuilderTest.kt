package io.kotlinsnowflake.query

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.time.LocalDate

class SelectBuilderTest : DescribeSpec({

    describe("SelectBuilder") {

        it("builds a simple SELECT *") {
            val builder = SelectBuilder().apply {
                from("MY_TABLE")
            }
            builder.buildSql() shouldBe "SELECT * FROM MY_TABLE"
        }

        it("supports named columns") {
            val builder = SelectBuilder().apply {
                columns("ID", "NAME", "STATUS")
                from("CAMPAIGNS")
            }
            builder.buildSql() shouldBe "SELECT ID, NAME, STATUS FROM CAMPAIGNS"
        }

        it("supports aggregate columns") {
            val builder = SelectBuilder().apply {
                columns("CAMPAIGN_ID", "SUM(SPEND) AS TOTAL_SPEND")
                from("AD_PERFORMANCE")
                groupBy("CAMPAIGN_ID")
            }
            val sql = builder.buildSql()
            sql shouldContain "SUM(SPEND) AS TOTAL_SPEND"
            sql shouldContain "GROUP BY CAMPAIGN_ID"
        }

        it("builds a WHERE eq clause") {
            val builder = SelectBuilder().apply {
                from("KEYWORDS")
                where { "STATUS" eq "ACTIVE" }
            }
            builder.buildSql() shouldContain "WHERE STATUS = ?"
            builder.params() shouldBe listOf("ACTIVE")
        }

        it("builds a WHERE inList clause") {
            val builder = SelectBuilder().apply {
                from("CAMPAIGNS")
                where { "ID" inList listOf(1L, 2L, 3L) }
            }
            builder.buildSql() shouldContain "WHERE ID IN (?, ?, ?)"
            builder.params() shouldBe listOf(1L, 2L, 3L)
        }

        it("builds a WHERE between clause") {
            val start = LocalDate.of(2024, 1, 1)
            val end   = LocalDate.of(2024, 3, 31)
            val builder = SelectBuilder().apply {
                from("STATS")
                where { "DATE" between (start to end) }
            }
            builder.buildSql() shouldContain "WHERE DATE BETWEEN ? AND ?"
            builder.params() shouldBe listOf(start, end)
        }

        it("combines multiple WHERE predicates with AND") {
            val builder = SelectBuilder().apply {
                from("KEYWORDS")
                where {
                    "STATUS" eq "ACTIVE"
                    "BID"    gt 0.10
                }
            }
            builder.buildSql() shouldContain "WHERE STATUS = ? AND BID > ?"
        }

        it("supports ORDER BY ASC and DESC") {
            val builder = SelectBuilder().apply {
                from("CAMPAIGNS")
                orderBy("CREATED_AT" to SortOrder.DESC)
            }
            builder.buildSql() shouldContain "ORDER BY CREATED_AT DESC"
        }

        it("supports LIMIT and OFFSET") {
            val builder = SelectBuilder().apply {
                from("KEYWORDS")
                limit(100)
                offset(200)
            }
            val sql = builder.buildSql()
            sql shouldContain "LIMIT 100"
            sql shouldContain "OFFSET 200"
        }

        it("fully-qualified table name is preserved") {
            val builder = SelectBuilder().apply {
                from("MY_DB.MY_SCHEMA.MY_TABLE")
            }
            builder.buildSql() shouldContain "FROM MY_DB.MY_SCHEMA.MY_TABLE"
        }

        it("builds a complex query") {
            val start = LocalDate.of(2024, 1, 1)
            val end   = LocalDate.of(2024, 12, 31)

            val builder = SelectBuilder().apply {
                columns("ACCOUNT_ID", "SUM(SPEND) AS TOTAL")
                from("AD_PERFORMANCE")
                where {
                    "DATE"   between (start to end)
                    "STATUS" eq "ACTIVE"
                }
                groupBy("ACCOUNT_ID")
                having("SUM(SPEND) > ?", 1000.0)
                orderBy("TOTAL" to SortOrder.DESC)
                limit(50)
            }

            val sql = builder.buildSql()
            sql shouldContain "SELECT ACCOUNT_ID, SUM(SPEND) AS TOTAL"
            sql shouldContain "FROM AD_PERFORMANCE"
            sql shouldContain "WHERE DATE BETWEEN ? AND ? AND STATUS = ?"
            sql shouldContain "GROUP BY ACCOUNT_ID"
            sql shouldContain "HAVING SUM(SPEND) > ?"
            sql shouldContain "ORDER BY TOTAL DESC"
            sql shouldContain "LIMIT 50"
            sql shouldNotContain "OFFSET"
        }

        it("throws when from() is not specified") {
            val builder = SelectBuilder()
            val ex = runCatching { builder.buildSql() }.exceptionOrNull()
            ex?.message shouldContain "from() must be specified"
        }
    }

    describe("WhereBuilder OR groups") {

        it("wraps OR predicates in parentheses") {
            val builder = SelectBuilder().apply {
                from("CAMPAIGNS")
                where {
                    "ACCOUNT_ID" eq 42L
                    or {
                        "STATUS" eq "ACTIVE"
                        "STATUS" eq "PAUSED"
                    }
                }
            }
            builder.buildSql() shouldContain "AND (STATUS = ? AND STATUS = ?)"
        }
    }
})
