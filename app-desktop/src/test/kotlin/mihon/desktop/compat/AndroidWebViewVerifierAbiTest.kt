package mihon.desktop.compat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier

class AndroidWebViewVerifierAbiTest {

    @Test
    fun `WebView verifier tokens keep exact shapes while engine methods fail fast`() {
        val context = Class.forName("android.content.Context")
        val view = Class.forName("android.view.View")
        val viewGroup = Class.forName("android.view.ViewGroup")
        val bitmap = Class.forName("android.graphics.Bitmap")
        val uri = Class.forName("android.net.Uri")
        val inputStream = Class.forName("java.io.InputStream")
        val webView = Class.forName("android.webkit.WebView")
        val webSettings = Class.forName("android.webkit.WebSettings")
        val webViewClient = Class.forName("android.webkit.WebViewClient")
        val webResourceRequest = Class.forName("android.webkit.WebResourceRequest")
        val webResourceResponse = Class.forName("android.webkit.WebResourceResponse")

        listOf(webView, webSettings, webViewClient, webResourceRequest, webResourceResponse).forEach {
            assertEquals(null, it.enclosingClass, "${it.name} must remain a top-level verifier type")
        }
        assertTrue(Modifier.isAbstract(uri.modifiers))
        assertTrue(Modifier.isAbstract(webSettings.modifiers))
        val uriToString = uri.getMethod("toString")
        assertTrue(Modifier.isAbstract(uriToString.modifiers))
        assertEquals(String::class.java, uriToString.returnType)
        assertEquals(setOf("toString"), uri.declaredMethods.map { it.name }.toSet())
        assertEquals(viewGroup, webView.superclass)
        assertTrue(webResourceRequest.isInterface)
        webView.getConstructor(context)
        val getSettings = webView.getMethod("getSettings")
        val addJavascriptInterface = webView.getMethod("addJavascriptInterface", Any::class.java, String::class.java)
        val setWebViewClient = webView.getMethod("setWebViewClient", webViewClient)
        val loadDataWithBaseUrl = webView.getMethod(
            "loadDataWithBaseURL",
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
        )
        val stopLoading = webView.getMethod("stopLoading")
        val destroy = webView.getMethod("destroy")
        assertEquals(webSettings, getSettings.returnType)
        listOf(addJavascriptInterface, setWebViewClient, loadDataWithBaseUrl, stopLoading, destroy)
            .forEach { assertEquals(Void.TYPE, it.returnType) }

        val booleanSettingMethods = listOf(
            "setJavaScriptEnabled",
            "setDomStorageEnabled",
            "setDatabaseEnabled",
            "setLoadWithOverviewMode",
            "setUseWideViewPort",
            "setBlockNetworkImage",
        ).map { webSettings.getMethod(it, Boolean::class.javaPrimitiveType) }
        val setUserAgentString = webSettings.getMethod("setUserAgentString", String::class.java)
        (booleanSettingMethods + setUserAgentString).forEach { assertEquals(Void.TYPE, it.returnType) }

        webViewClient.getConstructor()
        val onPageFinished = webViewClient.getMethod("onPageFinished", webView, String::class.java)
        val onPageStarted = webViewClient.getMethod("onPageStarted", webView, String::class.java, bitmap)
        val shouldInterceptRequest = webViewClient.getMethod("shouldInterceptRequest", webView, webResourceRequest)
        assertEquals(Void.TYPE, onPageFinished.returnType)
        assertEquals(Void.TYPE, onPageStarted.returnType)
        assertEquals(webResourceResponse, shouldInterceptRequest.returnType)
        assertEquals(uri, webResourceRequest.getMethod("getUrl").returnType)
        val responseConstructor = webResourceResponse.getConstructor(String::class.java, String::class.java, inputStream)

        val contextInstance = context.getConstructor().newInstance()
        val webViewInstance = webView.getConstructor(context).newInstance(contextInstance)
        assertEquals(webView, webViewInstance.javaClass)
        val storedContext = view.getDeclaredField("context").apply { isAccessible = true }
        assertSame(contextInstance, storedContext.get(webViewInstance))
        assertUnsupported { getSettings.invoke(webViewInstance) }
        val clientInstance = webViewClient.getConstructor().newInstance()
        listOf(
            addJavascriptInterface to arrayOf(Any(), "bridge"),
            setWebViewClient to arrayOf(clientInstance),
            loadDataWithBaseUrl to arrayOf("https://example.com", "data", "text/html", "UTF-8", "history"),
            stopLoading to emptyArray(),
            destroy to emptyArray(),
        ).forEach { (method, arguments) ->
            assertUnsupported { method.invoke(webViewInstance, *arguments) }
        }

        assertNull(onPageFinished.invoke(clientInstance, webViewInstance, "https://example.com"))
        assertNull(onPageStarted.invoke(clientInstance, webViewInstance, "https://example.com", null))
        assertNull(shouldInterceptRequest.invoke(clientInstance, webViewInstance, null))

        val responseData = ByteArrayInputStream(byteArrayOf(1, 2, 3))
        val response = responseConstructor.newInstance("image/png", "binary", responseData)
        assertEquals("image/png", webResourceResponse.getMethod("getMimeType").invoke(response))
        assertEquals("binary", webResourceResponse.getMethod("getEncoding").invoke(response))
        assertEquals(responseData, webResourceResponse.getMethod("getData").invoke(response))
    }

    private fun assertUnsupported(call: () -> Unit) {
        val failure = assertThrows(InvocationTargetException::class.java) { call() }
        assertTrue(failure.targetException is UnsupportedOperationException)
        assertEquals("Desktop WebView engine unavailable", failure.targetException.message)
    }
}
