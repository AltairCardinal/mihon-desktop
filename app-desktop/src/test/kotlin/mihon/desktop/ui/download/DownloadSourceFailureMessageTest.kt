package mihon.desktop.ui.download

import io.kotest.matchers.string.shouldContain
import mihon.domain.error.AppError
import org.junit.jupiter.api.Test
import java.util.Locale

class DownloadSourceFailureMessageTest {
    @Test
    fun `source payload and missing source have actionable distinct UI categories`() {
        val locale = Locale.forLanguageTag("zh-CN")
        downloadFailureMessage(AppError.MalformedData(), locale).shouldContain("\u9875\u9762\u6570\u636e")
        downloadFailureMessage(AppError.Unknown(), locale).shouldContain("\u6e90\u4e0d\u53ef\u7528")
    }
}
