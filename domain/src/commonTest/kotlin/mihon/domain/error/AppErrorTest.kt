package mihon.domain.error

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class AppErrorTest {
    @Test
    fun `错误契约覆盖跨端失败类别并保留结构化原因`() {
        val cause = IllegalStateException("boom")
        val error: AppError = AppError.PartialFailure(
            failures = listOf(AppError.Network(cause = cause)),
            cause = cause,
        )

        val partial = assertInstanceOf(AppError.PartialFailure::class.java, error)
        assertEquals(cause, error.cause)
        assertInstanceOf(AppError.Network::class.java, partial.failures.single())
    }

    @Test
    fun `错误契约包含全部结构化 variant`() {
        val variants = listOf(
            AppError.Network(),
            AppError.Authentication(),
            AppError.Challenge(),
            AppError.RateLimited(),
            AppError.Server(500),
            AppError.Permission(),
            AppError.MalformedData(),
            AppError.Storage(),
            AppError.Cancelled,
            AppError.PartialFailure(emptyList()),
            AppError.Unknown(),
        )

        assertEquals(11, variants.map { it::class }.distinct().size)
    }

    @Test
    fun `partial failure identifies each failed unit`() {
        val failure = AppError.PartialFailure(
            failures = listOf(AppError.Network()),
            failedUnits = listOf(AppError.FailedUnit("manga:42", AppError.Network())),
        )

        assertEquals("manga:42", failure.failedUnits.single().unitId)
    }
}
