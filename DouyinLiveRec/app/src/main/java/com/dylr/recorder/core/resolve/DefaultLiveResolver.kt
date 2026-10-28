package com.dylr.recorder.core.resolve

import android.content.Context
import com.dylr.recorder.core.LiveResolver
import com.dylr.recorder.core.LiveSource
import com.dylr.recorder.core.RoomStatus
import com.dylr.recorder.core.StreamType
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class DefaultLiveResolver(context: Context) : LiveResolver {

    private val appContext: Context = context.applicationContext

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    override suspend fun resolve(input: String): LiveSource {
        val text = input.trim()
        if (text.isEmpty()) throw IOException("解析失败：输入内容为空")
        val roomId = resolveRoomId(text)
        val streamUrl = captureStream(roomId)
            ?: throw IOException("解析超时：未捕获到流地址，可能未开播或需要人机验证")
        return LiveSource(roomId = roomId, url = streamUrl, type = streamTypeOf(streamUrl))
    }

    override suspend fun probe(roomId: String): RoomStatus = withContext(Dispatchers.IO) {
        val rid = roomId.trim()
        if (rid.isEmpty()) return@withContext RoomStatus(roomId = roomId, isLive = false)
        try {
            ensureCookieJar()
            val cookie = WebViewSession.cookieFor(LIVE_HOME)
            val builder = Request.Builder()
                .url(ENTER_API + ENTER_QUERY + rid)
                .header("User-Agent", WebViewSession.USER_AGENT)
                .header("Referer", LIVE_HOME)
                .header("Accept", "application/json, text/plain, */*")
                .get()
            if (cookie.isNotBlank()) builder.header("Cookie", cookie)
            client.newCall(builder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) {
                    RoomStatus(roomId = roomId, isLive = false)
                } else {
                    parseRoomStatus(roomId, body)
                }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            // 网络、JSON、cookie 任何异常都降级为“未开播”，绝不抛给调用方
            RoomStatus(roomId = roomId, isLive = false)
        }
    }

    private suspend fun resolveRoomId(text: String): String {
        RoomIdParser.parse(text)?.let { return it }
        if (!RoomIdParser.isShortLink(text)) {
            throw IOException("无法识别的输入：请粘贴直播间链接、v.douyin.com 短链或纯数字房间号")
        }
        val shortUrl = shortLinkOf(text)
            ?: throw IOException("短链解析失败：未能从输入中提取 v.douyin.com 链接")
        val landed = followRedirect(shortUrl)
            ?: throw IOException("短链解析失败：网络请求失败，请检查网络后重试")
        return RoomIdParser.parse(landed)
            ?: throw IOException("短链解析失败：跳转结果中未找到直播房间号：$landed")
    }

    private suspend fun followRedirect(shortUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(shortUrl)
                .header("User-Agent", WebViewSession.USER_AGENT)
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                // followRedirects(true) 已自动跟随 302，最终请求 URL 就是落地页
                response.request.url.toString()
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            null
        }
    }

    private suspend fun captureStream(roomId: String): String? {
        val session = WebViewSession(appContext)
        val watch = PreferredHit()
        return try {
            session.openAndCapture(
                url = LIVE_HOME + roomId,
                matcher = { url -> watch.accept(url) },
                timeoutMs = CAPTURE_TIMEOUT_MS
            ) ?: watch.bestFallback()
        } finally {
            session.destroy()
        }
    }

    /**
     * 三条通道（shouldInterceptRequest / fetch·XHR 钩子 / performance 收割）共用的判定器：
     * 唯一打分函数是 [LiveUrlInterceptor.score]，优先级由此统一（flv 3 > origin·hd m3u8 2 > 兜底 1）。
     *
     * 线程契约（正视跨线程事实）：accept() 由 WebViewSession.HitSink 在主线程串行调用（写）；
     * bestFallback() 由 captureStream 在 openAndCapture 返回后在 IO 线程读一次。两者之间没有
     * happens-before 边，所以用一个 @Volatile 引用持不可变快照（score+url）：读到的要么是旧快照、
     * 要么是新快照，不会读到撕裂的 score/url 组合，也不需要锁。
     */
    private class PreferredHit {

        private class Candidate(val score: Int, val url: String)

        @Volatile
        private var best: Candidate? = null

        fun accept(url: String): Boolean {
            val score = LiveUrlInterceptor.score(url)
            if (score <= 0) return false
            val current = best
            if (current == null || score > current.score) best = Candidate(score, url)
            return score >= LiveUrlInterceptor.SCORE_ENOUGH
        }

        fun bestFallback(): String? = best?.url
    }

    private fun parseRoomStatus(roomId: String, body: String): RoomStatus {
        return try {
            val root = JSONObject(body)
            val data = root.optJSONObject("data") ?: return RoomStatus(roomId = roomId, isLive = false)
            val rooms: JSONArray = data.optJSONArray("data")
                ?: return RoomStatus(roomId = roomId, isLive = false)
            if (rooms.length() == 0) return RoomStatus(roomId = roomId, isLive = false)
            val room = rooms.optJSONObject(0) ?: return RoomStatus(roomId = roomId, isLive = false)
            val owner = room.optJSONObject("owner")
            RoomStatus(
                roomId = roomId,
                isLive = room.optInt("status", 0) == 2,
                title = room.stringOrNull("title"),
                anchor = owner?.stringOrNull("nickname"),
                cover = owner?.firstUrlOf("avatar_thumb")
            )
        } catch (t: Throwable) {
            RoomStatus(roomId = roomId, isLive = false)
        }
    }

    /** 抖音接口里缺失字段常是 JSON null，optString 会返回字符串 "null"，这里统一归一化。 */
    private fun JSONObject.stringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val value = optString(key, "").trim()
        return if (value.isEmpty() || value == "null") null else value
    }

    private fun JSONObject.firstUrlOf(objectKey: String): String? {
        val holder = optJSONObject(objectKey) ?: return null
        val urls = holder.optJSONArray("url_list") ?: return null
        if (urls.length() == 0) return null
        val value = urls.optString(0, "").trim()
        return if (value.isEmpty() || value == "null") null else value
    }

    /** 没有 ttwid 时先用首页请求把服务端下发的 cookie 写回 CookieManager，保证 probe 带全身份。 */
    private fun ensureCookieJar() {
        if (WebViewSession.cookieFor(LIVE_HOME).contains("ttwid")) return
        try {
            val request = Request.Builder()
                .url(LIVE_HOME)
                .header("User-Agent", WebViewSession.USER_AGENT)
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val headers = response.headers
                for (index in 0 until headers.size) {
                    if (!headers.name(index).equals("Set-Cookie", ignoreCase = true)) continue
                    val cookieLine = headers.value(index).substringBefore(';').trim()
                    if (cookieLine.isEmpty()) continue
                    try {
                        WebViewSession.cookieStore().setCookie(LIVE_HOME, cookieLine)
                    } catch (_: Throwable) {
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun shortLinkOf(text: String): String? {
        val match = SHORT_LINK_URL.find(text) ?: return null
        val raw = match.value
        return if (raw.startsWith("http", ignoreCase = true)) raw else "https://$raw"
    }

    private fun streamTypeOf(url: String): StreamType {
        val lower = url.lowercase()
        return when {
            lower.contains(".flv") -> StreamType.FLV
            lower.contains(".m3u8") -> StreamType.HLS
            else -> StreamType.UNKNOWN
        }
    }

    companion object {
        private const val CAPTURE_TIMEOUT_MS = 20000L
        private const val LIVE_HOME = "https://live.douyin.com/"
        private const val ENTER_API = "https://live.douyin.com/webcast/room/web/enter/"
        private const val ENTER_QUERY =
            "?aid=6383&app_name=douyin_web&live_id=1&device_platform=web&language=zh-CN" +
                "&enter_from=web_live&cookie_enabled=true&browser_language=zh-CN" +
                "&browser_platform=Win32&browser_name=Mozilla&browser_version=5.0&web_rid="
        private val SHORT_LINK_URL =
            Regex("""(?:https?://)?v\.douyin\.com/[A-Za-z0-9_\-]+""", RegexOption.IGNORE_CASE)
    }
}
