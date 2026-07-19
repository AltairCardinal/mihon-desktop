package android.webkit

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import java.io.InputStream

open class WebView(context: Context) : ViewGroup() {
    open fun getSettings(): WebSettings = unavailable()

    open fun addJavascriptInterface(instance: Any, name: String) {
        unavailable()
    }

    open fun setWebViewClient(client: WebViewClient) {
        unavailable()
    }

    open fun loadDataWithBaseURL(
        baseUrl: String?,
        data: String,
        mimeType: String?,
        encoding: String?,
        historyUrl: String?,
    ) {
        unavailable()
    }

    open fun stopLoading() {
        unavailable()
    }

    open fun destroy() {
        unavailable()
    }
}

open class WebSettings {
    open fun setJavaScriptEnabled(enabled: Boolean) {
        unavailable()
    }

    open fun setDomStorageEnabled(enabled: Boolean) {
        unavailable()
    }

    open fun setDatabaseEnabled(enabled: Boolean) {
        unavailable()
    }

    open fun setLoadWithOverviewMode(enabled: Boolean) {
        unavailable()
    }

    open fun setUseWideViewPort(enabled: Boolean) {
        unavailable()
    }

    open fun setBlockNetworkImage(block: Boolean) {
        unavailable()
    }

    open fun setUserAgentString(userAgent: String) {
        unavailable()
    }
}

open class WebViewClient {
    open fun onPageFinished(view: WebView?, url: String?) = Unit

    open fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) = Unit

    open fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? = null
}

interface WebResourceRequest {
    val url: Uri
}

open class WebResourceResponse(
    val mimeType: String?,
    val encoding: String?,
    val data: InputStream?,
)

private fun unavailable(): Nothing {
    throw UnsupportedOperationException("Desktop WebView engine unavailable")
}
