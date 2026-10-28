package com.dylr.recorder.core.resolve

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 无界面 WebView 会话。所有 WebView 操作都在主线程（Handler(Looper.getMainLooper())）。
 *
 * 三条互相独立的抓流通道，命中任一即成功，判定与优先级完全统一：
 * 1) shouldInterceptRequest（WebView 自己发起的子资源请求）；
 * 2) 注入 JS 包裹 window.fetch / XMLHttpRequest.prototype.open；
 * 3) 每 800ms poll 一次 window.__dylrUrls（JS 用 performance 资源列表补捞，覆盖 MSE 场景）。
 * 三条通道都只往同一个 HitSink 投喂，matcher 因此永远在主线程串行执行，调用方无需线程安全。
 */
class WebViewSession(context: Context) {

    private val appContext: Context = context.applicationContext

    companion object {
        const val USER_AGENT: String =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        const val DOUYIN_LIVE_HOME: String = "https://live.douyin.com/"

        private const val POLL_INTERVAL_MS = 800L

        /**
         * 注入脚本：保存 __dylrUrls 列表，包裹 fetch 与 XMLHttpRequest.open，
         * 每 1000ms 用 performance.getEntriesByType('resource') 补捞一次；__dylrHooked 保证幂等。
         */
        private const val INJECT_JS: String = """(function () {
  if (window.__dylrHooked) { return; }
  window.__dylrHooked = true;
  window.__dylrUrls = window.__dylrUrls || [];
  function keep(u) {
    try {
      if (!u || typeof u !== 'string') { return; }
      var low = u.toLowerCase();
      if (low.indexOf('.flv') < 0 && low.indexOf('.m3u8') < 0) { return; }
      if (window.__dylrUrls.indexOf(u) < 0) { window.__dylrUrls.push(u); }
    } catch (e) {}
  }
  try {
    var rawFetch = window.fetch;
    if (rawFetch) {
      window.fetch = function (input, init) {
        try { keep(typeof input === 'string' ? input : (input && input.url)); } catch (e) {}
        return rawFetch.apply(this, arguments);
      };
    }
  } catch (e) {}
  try {
    var rawOpen = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function (method, url) {
      try { keep(url); } catch (e) {}
      return rawOpen.apply(this, arguments);
    };
  } catch (e) {}
  try {
    setInterval(function () {
      try {
        if (!window.performance || !performance.getEntriesByType) { return; }
        var list = performance.getEntriesByType('resource');
        for (var i = 0; i < list.length; i++) { keep(list[i].name); }
      } catch (e) {}
    }, 1000);
  } catch (e) {}
})();"""

        /** 收割脚本：把钩子记录的 URL 用 | 拼成字符串返回，交给轮询逐条投喂。 */
        private const val HARVEST_JS: String =
            "(function () { try { var a = window.__dylrUrls || []; return a.join('|'); } catch (e) { return ''; } })()"

        /** 全局唯一的 CookieManager：probe 与 WebView 共用同一份登录态。 */
        fun cookieStore(): CookieManager = CookieManager.getInstance()

        fun cookieFor(url: String): String = try {
            cookieStore().getCookie(url) ?: ""
        } catch (_: Throwable) {
            ""
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 轮询专用 Handler：销毁时对它 removeCallbacksAndMessages(null)，不会误伤别的会话。 */
    private val pollHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var webView: WebView? = null

    @Volatile
    private var hitSink: HitSink? = null

    @Volatile
    private var capture: CompletableDeferred<String?>? = null

    @Volatile
    private var destroyed: Boolean = false

    private val pollTask: Runnable = object : Runnable {
        override fun run() {
            val wv = webView
            val sink = hitSink
            val done = capture
            if (wv == null || sink == null || done == null || !done.isActive) return
            try {
                wv.evaluateJavascript(HARVEST_JS) { raw ->
                    runOnMain { sink.offerAll(decodeJsList(raw)) }
                }
            } catch (_: Throwable) {
            }
            pollHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    init {
        try {
            cookieStore().setAcceptCookie(true)
        } catch (_: Throwable) {
            // CookieManager 在极端情况下不可用，降级为无 cookie 抓流
        }
    }

    /**
     * 在主线程创建 WebView 并打开 [url]，任一通道抓到满足 [matcher] 的流地址即返回，超时返回 null。
     * WebView 初始化/加载失败转为带中文信息的 IOException。
     */
    suspend fun openAndCapture(
        url: String,
        matcher: (String) -> Boolean,
        timeoutMs: Long = 20000L
    ): String? {
        val hit = CompletableDeferred<String?>()
        mainHandler.post {
            try {
                createWebView(url, matcher, hit)
            } catch (io: IOException) {
                hit.completeExceptionally(io)
            } catch (t: Throwable) {
                hit.completeExceptionally(
                    IOException("解析失败：WebView 初始化异常（${t.message ?: t.javaClass.simpleName}）", t)
                )
            }
        }
        return try {
            withTimeoutOrNull(timeoutMs) { hit.await() }
        } catch (c: CancellationException) {
            throw c
        } catch (io: IOException) {
            throw io
        } catch (t: Throwable) {
            throw IOException("解析失败：${t.message ?: "WebView 加载失败"}", t)
        } finally {
            destroy()
        }
    }

    /** 释放 WebView 并停掉轮询；重复调用安全。 */
    fun destroy() {
        destroyed = true
        pollHandler.removeCallbacksAndMessages(null)
        hitSink?.close()
        // 结算未完成的 deferred：让已排队的投递/回调因 !hit.isActive 直接早退；
        // 对已完成的 deferred 再 complete(null) 返回 false 且不抛，所以这里幂等。
        capture?.complete(null)
        capture = null
        hitSink = null
        val wv = webView ?: return
        webView = null
        runOnMain {
            try {
                wv.stopLoading()
                wv.webViewClient = WebViewClient()
                wv.destroy()
            } catch (_: Throwable) {
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(
        url: String,
        matcher: (String) -> Boolean,
        hit: CompletableDeferred<String?>
    ) {
        if (destroyed) {
            hit.completeExceptionally(IOException("解析失败：WebView 会话已销毁"))
            return
        }
        val sink = HitSink(matcher, hit)
        // WebView 必须在主线程创建；部分 OEM 上 application context 构造即抛，交由 newWebView 兜底
        val wv = newWebView()
        webView = wv
        hitSink = sink
        capture = hit
        try {
            val cookies = cookieStore()
            cookies.setAcceptCookie(true)
            cookies.setAcceptThirdPartyCookies(wv, true)
        } catch (_: Throwable) {
        }
        val settings: WebSettings = wv.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.userAgentString = USER_AGENT
        // 允许自动播放，否则 h5 播放器不会发起真正的流请求
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        wv.webChromeClient = WebChromeClient()
        wv.webViewClient = LiveUrlInterceptor(
            onHit = { streamUrl -> runOnMain { sink.offer(streamUrl) } },
            onPageReady = { page ->
                try {
                    page?.evaluateJavascript(INJECT_JS, null)
                } catch (_: Throwable) {
                }
            }
        )
        wv.loadUrl(url, mapOf("User-Agent" to USER_AGENT))
        // 通道 3：轮询收割 JS 钩子；与 shouldInterceptRequest 并行，命中任一即完成
        pollHandler.removeCallbacksAndMessages(null)
        pollHandler.postDelayed(pollTask, POLL_INTERVAL_MS)
    }

    /** 部分 OEM 用 application context 直接 new WebView 会抛，套一层主题包装再试一次，两次都失败才抛中文异常。 */
    private fun newWebView(): WebView {
        var failure: Throwable? = null
        try {
            return WebView(appContext)
        } catch (t: Throwable) {
            failure = t
        }
        val themed = ContextThemeWrapper(appContext, android.R.style.Theme_DeviceDefault)
        return try {
            WebView(themed)
        } catch (t: Throwable) {
            throw IOException(
                "解析失败：WebView 初始化异常（${t.message ?: failure?.message ?: "无法创建 WebView"}）", t
            )
        }
    }

    /**
     * 三条通道唯一的投喂口：只在主线程调用（shouldInterceptRequest 经 runOnMain，轮询本就在主线程），
     * 因此 matcher 不需要线程安全，重复 URL 只喂一次。
     */
    private class HitSink(
        private val matcher: (String) -> Boolean,
        private val hit: CompletableDeferred<String?>
    ) {
        private val seen = HashSet<String>()

        /** 由 destroy() 写（调用方线程），主线程只读 → volatile 保证可见性。 */
        @Volatile
        private var closed = false

        fun close() {
            closed = true
        }

        fun offerAll(urls: List<String>) {
            for (url in urls) offer(url)
        }

        fun offer(url: String) {
            if (closed) return
            if (!hit.isActive) return
            if (!seen.add(url)) return
            val accepted = try {
                matcher(url)
            } catch (_: Throwable) {
                false
            }
            if (accepted) hit.complete(url)
        }
    }

    /** 轮询也需要停：deferred 结束后不再自我重排，销毁/超时再 removeCallbacksAndMessages(null)。 */
    private fun decodeJsList(raw: String?): List<String> {
        val text = decodeJsString(raw)
        if (text.isEmpty()) return emptyList()
        return text.split('|').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** evaluateJavascript 回调给的是 JSON 字面量（带外层引号、内部转义），这里还原成普通字符串。 */
    private fun decodeJsString(raw: String?): String {
        if (raw == null) return ""
        val text = raw.trim()
        if (text.isEmpty() || text == "null") return ""
        if (!text.startsWith("\"")) return text
        val builder = StringBuilder()
        var index = 1
        while (index < text.length) {
            val ch = text[index]
            if (ch == '"') break
            if (ch == '\\' && index + 1 < text.length) {
                val next = text[index + 1]
                when (next) {
                    'n' -> builder.append('\n')
                    't' -> builder.append('\t')
                    'r' -> builder.append('\r')
                    '"' -> builder.append('"')
                    '\\' -> builder.append('\\')
                    '/' -> builder.append('/')
                    'u' -> {
                        if (index + 5 < text.length) {
                            val code = text.substring(index + 2, index + 6).toIntOrNull(16)
                            if (code != null) builder.append(code.toChar())
                            index += 4
                        }
                    }
                    else -> builder.append(next)
                }
                index += 2
                continue
            }
            builder.append(ch)
            index++
        }
        return builder.toString()
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post { block() }
        }
    }
}
