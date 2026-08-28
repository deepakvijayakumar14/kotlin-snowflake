package io.kotlinsnowflake

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class EnvTest : DescribeSpec({

    describe("env") {

        it("returns the value of a variable that is set") {
            // PATH is the one variable that is present on every platform this runs on.
            env("PATH") shouldBe System.getenv("PATH")
        }

        it("names the missing variable rather than returning null") {
            val ex = shouldThrow<IllegalStateException> {
                env("KOTLIN_SNOWFLAKE_DEFINITELY_NOT_SET")
            }

            ex.message shouldContain "KOTLIN_SNOWFLAKE_DEFINITELY_NOT_SET"
        }
    }
})
