package io.kotlinsnowflake.query

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

/**
 * Every operator, rendered on its own. These are one-liners individually, but each one is a
 * chance to emit the wrong SQL text or bind the wrong number of parameters, and neither shows
 * up until a query reaches Snowflake.
 */
class WhereBuilderTest : DescribeSpec({

    fun clause(block: WhereBuilder.() -> Unit): Pair<String, List<Any?>> {
        val predicate = WhereBuilder().apply(block).predicate()
        return (predicate?.render() ?: "") to predicate?.params.orEmpty()
    }

    describe("comparison operators") {

        it("renders each operator with a single bound value") {
            clause { "A" eq 1 } shouldBe ("A = ?" to listOf(1))
            clause { "A" neq 1 } shouldBe ("A != ?" to listOf(1))
            clause { "A" gt 1 } shouldBe ("A > ?" to listOf(1))
            clause { "A" gte 1 } shouldBe ("A >= ?" to listOf(1))
            clause { "A" lt 1 } shouldBe ("A < ?" to listOf(1))
            clause { "A" lte 1 } shouldBe ("A <= ?" to listOf(1))
            clause { "A" like "x%" } shouldBe ("A LIKE ?" to listOf("x%"))
            clause { "A" ilike "x%" } shouldBe ("A ILIKE ?" to listOf("x%"))
        }

        it("binds a null value rather than rendering IS NULL") {
            // `A = NULL` is never true in SQL. Binding null is what the caller asked for, and
            // silently rewriting it to IS NULL would change the meaning behind their back.
            clause { "A" eq null } shouldBe ("A = ?" to listOf(null))
        }

        it("renders BETWEEN with two bound values") {
            clause { "A" between (1 to 9) } shouldBe ("A BETWEEN ? AND ?" to listOf(1, 9))
        }
    }

    describe("null checks") {

        it("render without binding anything") {
            clause { "A".isNull() } shouldBe ("A IS NULL" to emptyList())
            clause { "A".isNotNull() } shouldBe ("A IS NOT NULL" to emptyList())
        }
    }

    describe("list membership") {

        it("emits one placeholder per value") {
            clause { "A" inList listOf(1, 2, 3) } shouldBe ("A IN (?, ?, ?)" to listOf(1, 2, 3))
            clause { "A" notInList listOf(1, 2) } shouldBe ("A NOT IN (?, ?)" to listOf(1, 2))
        }

        it("rejects an empty collection") {
            // `IN ()` is a syntax error, so failing here beats failing in Snowflake.
            shouldThrow<IllegalArgumentException> { clause { "A" inList emptyList<Int>() } }
            shouldThrow<IllegalArgumentException> { clause { "A" notInList emptyList<Int>() } }
        }
    }

    describe("raw") {

        it("passes SQL through while still binding values") {
            clause { raw("A % ? = ?", 2, 0) } shouldBe ("A % ? = ?" to listOf(2, 0))
        }
    }

    describe("grouping") {

        it("combines top-level predicates with AND") {
            clause {
                "A" eq 1
                "B" eq 2
            } shouldBe ("A = ? AND B = ?" to listOf(1, 2))
        }

        it("parenthesizes a nested group and keeps its combinator") {
            clause {
                "A" eq 1
                or {
                    "B" eq 2
                    "C" eq 3
                }
            } shouldBe ("A = ? AND (B = ? OR C = ?)" to listOf(1, 2, 3))
        }

        it("drops the parentheses when a group holds a single predicate") {
            clause {
                "A" eq 1
                or { "B" eq 2 }
            } shouldBe ("A = ? AND B = ?" to listOf(1, 2))
        }

        it("nests to arbitrary depth") {
            clause {
                or {
                    and {
                        "A" eq 1
                        or {
                            "B" eq 2
                            "C" eq 3
                        }
                    }
                    "D" eq 4
                }
            } shouldBe ("(A = ? AND (B = ? OR C = ?)) OR D = ?" to listOf(1, 2, 3, 4))
        }

        it("yields no predicate at all for an empty block") {
            WhereBuilder().apply { }.predicate() shouldBe null
        }
    }
})
