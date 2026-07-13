package mihon.desktop.ui.download

import io.kotest.matchers.string.shouldContain
import mihon.domain.error.AppError
import org.junit.jupiter.api.Test

class DownloadFailurePresentationTest {
    @Test fun `failure messages distinguish actionable categories`() {
        downloadFailureMessage(AppError.Network()).also { it shouldContain "网络"; it shouldContain "重试" }
        downloadFailureMessage(AppError.Server(500)).also { it shouldContain "服务器"; it shouldContain "重试" }
        downloadFailureMessage(AppError.Permission()).also { it shouldContain "权限"; it shouldContain "下载路径" }
        downloadFailureMessage(AppError.Storage()).also { it shouldContain "磁盘"; it shouldContain "下载路径" }
    }
}
