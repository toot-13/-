package com.dylr.recorder.core.resolve

import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * 只观察、不接管：shouldInterceptRequest 中把 http(s) 流地址交给 [onHit]，
 * 始终返回 null，让 WebView 按原生方式加载（不消费 body，只借用 url 做识别）。
 * [onPageReady] 在页面级导航开始/结束时回调，用于注入 JS 钩子（脚本自带幂等标记）。
 */
class LiveUrlInterceptor(
    private val onHit: (String) -> Unit = {},
    private val onPageReady: ((WebView?) -> Unit)? = null
) : WebViewClient() {

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        val url = request?.url?.toString()
        if (url != null && isCandidate(url)) {
            try {
                onHit(url)
            } catch (_: Throwable) {
                // 观察者异常不得影响页面加载
            }
        }
        return null
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        notifyPageReady(view)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        notifyPageReady(view)
    }

    private fun notifyPageReady(view: WebView?) {
        try {
            onPageReady?.invoke(view)
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val FLV = ".flv"
        private const val HLS = ".m3u8"
        private const val HLS_MASTER = "index.m3u8"

        const val SCORE_FLV = 3
        const val SCORE_HLS_PREFERRED = 2
        const val SCORE_HLS_FALLBACK = 1

        /** 达到该分值即可结束捕获：flv，或带 origin/hd 的 m3u8。 */
        const val SCORE_ENOUGH = SCORE_HLS_PREFERRED

        fun isHttpUrl(url: String): Boolean =
            url.startsWith("http://", true) || url.startsWith("https://", true)

        fun isFlv(url: String): Boolean = isHttpUrl(url) && url.contains(FLV, true)

        fun isHls(url: String): Boolean = isHttpUrl(url) && url.contains(HLS, true)

        /**
         * 0 = 非流地址；1 = 兜底（index.m3u8 这类主播放列表）；
         * 2 = 带 origin/hd 的 hls 变体；3 = flv。
         */
        fun score(url: String): Int {
            if (!isHttpUrl(url)) return 0
            val lower = url.lowercase()
            if (lower.contains(FLV)) return SCORE_FLV
            if (!lower.contains(HLS)) return 0
            if (lower.contains(HLS_MASTER)) return SCORE_HLS_FALLBACK
            if (lower.contains("origin") || lower.contains("hd")) return SCORE_HLS_PREFERRED
            return SCORE_HLS_FALLBACK
        }

        fun isCandidate(url: String): Boolean = score(url) > 0
    }
}
