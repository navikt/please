package no.nav.please

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import no.nav.please.retry.Retry
import java.lang.RuntimeException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTimedValue

class RetryTest: StringSpec({
    "retry should return either with result" {
        var counter = 0
        fun inc(): Int {
            counter += 1
            return counter
        }
        val timedResult = measureTimedValue {
            Retry.withRetry {
                inc()
            }
        }
        val result = timedResult.value
        result.isRight() shouldBe true
        counter shouldBe 1
        result.getOrNull() shouldBe 1
    }

    "should contain error if max number of retries is reached" {
        var counter = 0
        fun inc(): Int {
            counter += 1
            throw RuntimeException("LOL")
        }
        val timedResult = measureTimedValue {
            Retry.withRetry {
                inc()
            }
        }
        val result = timedResult.value
        timedResult.duration shouldBeGreaterThan 60.milliseconds
        timedResult.duration shouldBeLessThan 100.milliseconds
        result.isLeft() shouldBe true
        counter shouldBe 4
    }

    "exponential backoff should be configurable" {
        var counter = 0
        fun inc(): Int {
            counter += 1
            throw RuntimeException("LOL")
        }
        val timedResult = measureTimedValue {
            Retry.withRetry(3, 1.milliseconds) {
                inc()
            }
        }
        val result = timedResult.value
        timedResult.duration shouldBeGreaterThan 5.milliseconds
        timedResult.duration shouldBeLessThan 25.milliseconds
        result.isLeft() shouldBe true
        counter shouldBe 4
    }

    "should be correct if succeeds before max number of retries" {
        var counter = 0
        fun inc(): Int {
            counter += 1
            if (counter < 2) throw RuntimeException("LOL")
            return counter
        }
        val result = Retry.withRetry {
            inc()
        }
        result.isRight() shouldBe true
        counter shouldBe 2
    }
})