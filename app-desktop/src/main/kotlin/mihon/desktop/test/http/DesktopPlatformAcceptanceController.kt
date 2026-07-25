package mihon.desktop.test.http

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import mihon.desktop.platform.DesktopExternalActionPolicy
import mihon.desktop.platform.DesktopShareResult
import mihon.desktop.platform.DesktopShareService
import mihon.desktop.test.TestArguments
import mihon.domain.platform.SharePayload
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO

internal enum class PlatformShareKind { TEXT, FILE }

internal const val PLATFORM_ACCEPTANCE_TOKEN_HEADER = "X-Mihon-Platform-Acceptance-Token"

internal enum class PlatformAcceptanceFailure {
    MISSING_TOKEN,
    INVALID_TOKEN,
    TOKEN_ALREADY_USED,
    TERMINAL_TIMEOUT,
}

@Serializable
internal data class PlatformShareAcceptanceResult(
    val accepted: Boolean,
    val payloadKind: String,
    val launchResult: String? = null,
    val terminalResult: String? = null,
    val failure: PlatformAcceptanceFailure? = null,
)

internal class DesktopPlatformAcceptanceController(
    expectedToken: String,
    private val shareService: DesktopShareService,
    private val evidenceRoot: Path,
    private val terminalTimeoutMillis: Long = DEFAULT_TERMINAL_TIMEOUT_MILLIS,
) {
    private val expectedTokenBytes = expectedToken.toByteArray()
    private val consumed = AtomicBoolean()

    suspend fun share(providedToken: String?, kind: PlatformShareKind): PlatformShareAcceptanceResult {
        val failure = tokenFailure(providedToken)
        if (failure != null) return rejected(kind, failure)
        if (!consumed.compareAndSet(false, true)) {
            return rejected(kind, PlatformAcceptanceFailure.TOKEN_ALREADY_USED)
        }

        return withContext(Dispatchers.IO) {
            when (kind) {
                PlatformShareKind.TEXT -> execute(kind, SharePayload.Text(TEXT_PAYLOAD))
                PlatformShareKind.FILE -> withControlledPng { source ->
                    execute(
                        kind,
                        SharePayload.Stream(
                            uri = source.toUri().toString(),
                            mimeType = "image/png",
                            message = FILE_MESSAGE,
                        ),
                    )
                }
            }
        }
    }

    private suspend fun execute(
        kind: PlatformShareKind,
        payload: SharePayload,
    ): PlatformShareAcceptanceResult {
        val terminal = CompletableDeferred<DesktopShareResult>()
        val launch = DesktopExternalActionPolicy.allowSinglePlatformAcceptance {
            shareService.share(payload) { result -> terminal.complete(result) }
        }
        val terminalResult =
            if (launch == DesktopShareResult.OpenedNatively) {
                withTimeoutOrNull(terminalTimeoutMillis) { terminal.await() }
                    ?: return PlatformShareAcceptanceResult(
                        accepted = true,
                        payloadKind = kind.name.lowercase(),
                        launchResult = launch.evidenceName(),
                        failure = PlatformAcceptanceFailure.TERMINAL_TIMEOUT,
                    )
            } else {
                launch
            }
        return PlatformShareAcceptanceResult(
            accepted = true,
            payloadKind = kind.name.lowercase(),
            launchResult = launch.evidenceName(),
            terminalResult = terminalResult.evidenceName(),
        )
    }

    private suspend fun <T> withControlledPng(block: suspend (Path) -> T): T {
        Files.createDirectories(evidenceRoot)
        val source = Files.createTempFile(evidenceRoot, "mihon-task151-share-", ".png")
        try {
            check(ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png", source.toFile()))
            return block(source)
        } finally {
            Files.deleteIfExists(source)
        }
    }

    private fun tokenFailure(providedToken: String?): PlatformAcceptanceFailure? = when {
        providedToken == null -> PlatformAcceptanceFailure.MISSING_TOKEN
        !MessageDigest.isEqual(expectedTokenBytes, providedToken.toByteArray()) ->
            PlatformAcceptanceFailure.INVALID_TOKEN
        else -> null
    }

    private fun rejected(kind: PlatformShareKind, failure: PlatformAcceptanceFailure) =
        PlatformShareAcceptanceResult(
            accepted = false,
            payloadKind = kind.name.lowercase(),
            failure = failure,
        )

    companion object {
        const val TEXT_PAYLOAD = "Mihon Task 151 platform share acceptance"
        const val FILE_MESSAGE = "Mihon Task 151 controlled PNG"
        const val TOKEN_PATTERN = "[0-9a-fA-F]{64}"
        const val DEFAULT_TERMINAL_TIMEOUT_MILLIS = 125_000L
    }
}

internal fun createPlatformAcceptanceController(
    args: TestArguments,
    evidenceRoot: Path,
    resolveShareService: () -> DesktopShareService,
): DesktopPlatformAcceptanceController? {
    val token = args.platformAcceptanceToken
        ?.takeIf { args.testMode && it.matches(Regex(DesktopPlatformAcceptanceController.TOKEN_PATTERN)) }
        ?: return null
    val service = runCatching(resolveShareService).getOrNull() ?: return null
    return DesktopPlatformAcceptanceController(token, service, evidenceRoot)
}

private fun DesktopShareResult.evidenceName(): String = when (this) {
    DesktopShareResult.OpenedNatively -> "OpenedNatively"
    DesktopShareResult.SharedNatively -> "SharedNatively"
    DesktopShareResult.CopiedToClipboard -> "CopiedToClipboard"
    is DesktopShareResult.Saved -> "Saved"
    DesktopShareResult.Cancelled -> "Cancelled"
    is DesktopShareResult.Unavailable -> "Unavailable:${reason.name}"
    is DesktopShareResult.Failed -> "Failed:${reason.name}"
}
