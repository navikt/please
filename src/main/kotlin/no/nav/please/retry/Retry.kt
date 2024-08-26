package no.nav.please.retry

import arrow.core.Either
import arrow.resilience.Schedule
import arrow.resilience.retry
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class MaxRetryError(val latestException: Throwable)

class Retry {
    companion object {
        suspend fun <Result> withRetry(retries: Long = 3, exponentialBackoff: Duration = 10.milliseconds, block: () -> Result): Either<MaxRetryError, Result> {
            val policy: Schedule<Throwable, *> = Schedule.recurs<Throwable>(retries) and Schedule.exponential(exponentialBackoff)
            return Either.catch {
                policy.retry {
                    block()
                }
            }
                .mapLeft { MaxRetryError(it) }
        }
    }
}