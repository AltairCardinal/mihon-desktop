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
            AppError.Forbidden(),
            AppError.Challenge(),
            AppError.RateLimited(),
            AppError.Server(500),
            AppError.Permission(),
            AppError.MalformedData(),
            AppError.NoResults,
            AppError.Storage(),
            AppError.Cancelled,
            AppError.PartialFailure(emptyList()),
            AppError.Unknown(),
        )

        assertEquals(13, variants.map { it::class }.distinct().size)
        assertEquals(null, AppError.NoResults.cause)
    }

    @Test
    fun `partial failure identifies each failed unit`() {
        val failure = AppError.PartialFailure(
            failures = listOf(AppError.Network()),
            failedUnits = listOf(AppError.FailedUnit("manga:42", AppError.Network())),
        )

        assertEquals("manga:42", failure.failedUnits.single().unitId)
    }

    @Test
    fun `HTTP access denial remains distinct from login after persistence`() {
        val error = StoredAppError("Forbidden", message = "HTTP error 403").toAppError()
        assertEquals("Forbidden", error::class.simpleName)
        assertEquals("Forbidden", error.toStoredAppError().type)
        assertEquals("HTTP error 403", error.cause?.message)
    }

    @Test
    fun `shared HTTP response maps 401 to login and 403 to access denial`() {
        val unauthorized = org.junit.jupiter.api.Assertions.assertThrows(
            mihon.domain.network.AppErrorException::class.java,
        ) { mihon.domain.network.requireSuccessfulHttpResponse(401, "denied") }
        val forbidden = org.junit.jupiter.api.Assertions.assertThrows(
            mihon.domain.network.AppErrorException::class.java,
        ) { mihon.domain.network.requireSuccessfulHttpResponse(403, "denied") }
        assertInstanceOf(AppError.Authentication::class.java, unauthorized.error)
        assertEquals("Forbidden", forbidden.error::class.simpleName)
    }

    @Test
    fun `legacy persisted authentication with exact HTTP 403 message is access denial`() {
        val error = StoredAppError("Authentication", message = "HTTP 403").toAppError()
        assertInstanceOf(AppError.Forbidden::class.java, error)
        assertEquals("HTTP 403", error.cause?.message)
        assertEquals("Forbidden", error.toStoredAppError().type)
        assertInstanceOf(
            AppError.Forbidden::class.java,
            StoredAppError("Authentication", message = "HTTP error 403").toAppError(),
        )
        assertInstanceOf(
            AppError.Authentication::class.java,
            StoredAppError("Authentication", message = "HTTP 401").toAppError(),
        )
        assertInstanceOf(
            AppError.Authentication::class.java,
            StoredAppError("Authentication", message = "unexpected 403 in parser").toAppError(),
        )
    }
}
