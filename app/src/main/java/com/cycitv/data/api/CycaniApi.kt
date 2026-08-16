package com.cycitv.data.api

import com.cycitv.data.dto.ApiEnvelope
import com.cycitv.data.dto.HotWordsData
import com.cycitv.data.dto.PlayUrlData
import com.cycitv.data.dto.RankData
import com.cycitv.data.dto.RankDto
import com.cycitv.data.dto.RankVideosData
import com.cycitv.data.dto.RecommendData
import com.cycitv.data.dto.SectionDto
import com.cycitv.data.dto.SectionListData
import com.cycitv.data.dto.VideoCardDto
import com.cycitv.data.dto.VideoDetailDto
import com.cycitv.data.dto.VideoListData
import com.cycitv.data.dto.WeekdayData
import com.cycitv.data.dto.ZoneDto
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ApiException(val apiCode: Int, message: String) : IOException(message)

private const val UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36"

/**
 * 站点 API 客户端。
 * 使用 HttpURLConnection(而非 OkHttp):EdgeOne CDN 对 OkHttp 的 TLS 指纹
 * 在部分 IP 段直接返回 HTML,HttpURLConnection(Conscrypt)可正常通过。
 * 首次请求先访问首页获取 EdgeOne 种下的 __ct_cya_ckt cookie。
 */
class CycaniApi(
    private val client: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val base = "https://www.cycani.org/api"
    private val homeUrl = "https://www.cycani.org/"
    private var bootstrapped = false
    private var cookie: String? = null
    private val bootstrapLock = Any()

    // 认证 token 由用户登录后注入(存 DataStore,不进仓库);为空则不发 Authorization
    var token: String? = null

    // ---- 用户认证(每个用户自行登录,不内置任何人 token) ----
    private fun openPost(urlStr: String, body: String): HttpURLConnection {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("X-App-Name", "cyc_android")
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toByteArray()) }
        return conn
    }

    private fun postText(urlStr: String, body: String): String {
        val conn = openPost(urlStr, body)
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        return text
    }

    /** 发送邮箱验证码:type = register | reset | bind */
    suspend fun sendCode(email: String, type: String): String = withContext(Dispatchers.IO) {
        val body = postText("https://mapi.cycback.org/auth/send-code", json.encodeToString<CodeReq>(CodeReq(email, type)))
        val env = json.decodeFromString<Envelope>(body)
        if (env.code != 0) throw ApiException(env.code, env.msg)
        env.msg
    }

    /** 注册:需要邮箱验证码(先 sendCode type=register) */
    suspend fun register(username: String, password: String, email: String, emailCode: String): String = withContext(Dispatchers.IO) {
        val body = postText(
            "https://mapi.cycback.org/v2/auth/register",
            json.encodeToString<RegisterReq>(RegisterReq(username, password, email, emailCode))
        )
        val env = json.decodeFromString<Envelope>(body)
        if (env.code != 0) throw ApiException(env.code, env.msg)
        env.msg
    }

    /** 登录成功返回 token(需自己注册的账号);失败抛 ApiException */
    suspend fun login(username: String, password: String): String = withContext(Dispatchers.IO) {
        val body = postText(
            "https://mapi.cycback.org/auth/login",
            json.encodeToString<LoginReq>(LoginReq(username, password))
        )
        // 宽解析:token 可能位于 data.token / token / data.access_token, 用 org.json 盲找
        try {
            val o = org.json.JSONObject(body)
            if (o.optInt("code") != 0) {
                throw ApiException(o.optInt("code"), o.optString("msg").ifBlank { "登录失败" })
            }
            val data = o.optJSONObject("data")
            val tok = data?.optString("token")?.takeIf { it.isNotBlank() }
                ?: data?.optString("access_token")?.takeIf { it.isNotBlank() }
                ?: o.optString("token").takeIf { it.isNotBlank() }
                ?: throw ApiException(0, "登录成功但未返回 token: ${body.take(100)}")
            // 站方 login 返回的 token 自带 "Bearer " 前缀; 归一化去掉, 统一由 open() 拼
            tok.removePrefix("Bearer ").trim()
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw ApiException(0, "登录返回异常: ${body.take(120)}")
        }
    }

    private fun open(urlStr: String): HttpURLConnection {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("X-App-Name", "cyc_android")
        conn.setRequestProperty("X-App-Version", "5.5.3")
        conn.setRequestProperty("X-Time-Zone", java.util.TimeZone.getDefault().getDisplayName())
        token?.let { conn.setRequestProperty("Authorization", if (it.startsWith("Bearer ") || it.startsWith("bearer ")) it else "Bearer $it") }
        cookie?.let { conn.setRequestProperty("Cookie", it) }
        return conn
    }

    private fun readBody(conn: HttpURLConnection): String {
        val code = conn.responseCode
        conn.getHeaderField("Set-Cookie")?.let { sc ->
            val c = sc.substringBefore(";")
            if (c.contains("=")) cookie = c
        }
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        if (code !in 200..299) throw ApiException(code, "HTTP $code")
        return body
    }

    private suspend fun bootstrap() = withContext(Dispatchers.IO) {
        synchronized(bootstrapLock) {
            if (bootstrapped) return@synchronized
            try {
                val conn = URL(homeUrl).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("User-Agent", UA)
                val code = conn.responseCode
                conn.getHeaderField("Set-Cookie")?.let { sc ->
                    val c = sc.substringBefore(";")
                    if (c.contains("=")) cookie = c
                }
                android.util.Log.e("CYC", "bootstrap code=$code cookie=$cookie")
                conn.disconnect()
            } catch (e: Exception) {
                android.util.Log.e("CYC", "bootstrap fail", e)
            }
            bootstrapped = true
        }
    }

    suspend fun <T> get(
        path: String,
        query: Map<String, Any?> = emptyMap(),
        parse: (String) -> T,
    ): T = withContext(Dispatchers.IO) {
        bootstrap()
        val qs = query.entries
            .filter { it.value != null }
            .joinToString("&") { "${it.key}=${URLEncoder.encode(it.value.toString(), "UTF-8")}" }
        val urlStr = base + path + if (qs.isEmpty()) "" else "?$qs"
        val conn = open(urlStr)
        val body = readBody(conn)
        if (!body.trimStart().startsWith("{")) {
            throw IOException("NOT-JSON code=${conn.responseCode} ct=${conn.contentType} body=${body.take(150)}")
        }
        try {
            val envelope = json.decodeFromString<Envelope>(body)
            if (envelope.code != 0) throw ApiException(envelope.code, envelope.msg)
            parse(body)
        } catch (e: Exception) {
            if (e is ApiException) throw e
            val head = body.take(300).replace("\n", " ")
            throw IOException("${e.message} | body: $head", e)
        }
    }

    suspend fun recommend(): RecommendData =
        get("/index/recommend") { json.decodeFromString<ApiEnvelope<RecommendData>>(it).data ?: RecommendData() }

    suspend fun zones(): List<ZoneDto> =
        cached("zones", TTL_10MIN) {
            get("/video-zones") { json.decodeFromString<ApiEnvelope<ZoneListData>>(it).data?.list ?: emptyList() }
        }

    suspend fun videos(zoneId: Int, page: Int, pageSize: Int = 48, orderBy: String = "hits"): VideoListData =
        cached("videos:$zoneId:$page:$pageSize:$orderBy", TTL_5M) {
            get("/videos", mapOf("zone_id" to zoneId, "page" to page, "page_size" to pageSize, "order_by" to orderBy)) {
                json.decodeFromString<ApiEnvelope<VideoListData>>(it).data ?: VideoListData()
            }
        }

    suspend fun search(q: String, page: Int, pageSize: Int = 48, zoneId: Int? = null): List<VideoCardDto> =
        get("/videos/search", mapOf("q" to q, "page" to page, "page_size" to pageSize, "zone_id" to zoneId)) {
            json.decodeFromString<ApiEnvelope<VideoListData>>(it).data?.list ?: emptyList()
        }

    /** 站方热门搜索词 */
    suspend fun hotKeywords(): List<String> =
        cached("search-hot", TTL_10MIN) {
            get("/videos/search/hot") { json.decodeFromString<ApiEnvelope<HotWordsData>>(it).data?.keywords ?: emptyList() }
        }

    suspend fun videoDetail(id: Long): VideoDetailDto =
        cached("vd:$id", TTL_5M) {
            get("/videos/$id") { json.decodeFromString<ApiEnvelope<VideoDetailDto>>(it).data ?: error("empty") }
        }

    suspend fun sections(id: Long, playerCode: String, page: Int, pageSize: Int = 100): List<SectionDto> =
        cached("sec:$id:$playerCode:$page", TTL_5M) {
            get("/videos/$id/sections", mapOf("player_code" to playerCode, "page" to page, "page_size" to pageSize)) {
                json.decodeFromString<ApiEnvelope<SectionListData>>(it).data?.list ?: emptyList()
            }
        }

    suspend fun allSections(id: Long, playerCode: String): List<SectionDto> {
        val out = mutableListOf<SectionDto>()
        var page = 1
        while (true) {
            val data = sections(id, playerCode, page, 100)
            out += data
            if (data.size < 100) break
            page++
        }
        return out
    }

    suspend fun playUrl(sectionId: Long): PlayUrlData = withContext(Dispatchers.IO) {
        val conn = open("https://mapi.cycback.org/v2/sections/$sectionId/play-url")
        val body = readBody(conn)
        val envelope = json.decodeFromString<ApiEnvelope<PlayUrlData>>(body)
        if (envelope.code != 0) throw ApiException(envelope.code, envelope.msg)
        envelope.data ?: PlayUrlData()
    }

    suspend fun weekday(weekday: Int? = null): WeekdayData =
        cached("weekday:${weekday ?: "all"}", TTL_5M) {
            get("/index/weekday", mapOf("weekday" to weekday)) {
                json.decodeFromString<ApiEnvelope<WeekdayData>>(it).data ?: WeekdayData()
            }
        }

    suspend fun ranks(): List<RankDto> =
        cached("ranks", TTL_10MIN) {
            get("/ranks") { json.decodeFromString<ApiEnvelope<RankData>>(it).data?.list ?: emptyList() }
        }

    suspend fun rankVideos(rankId: Long): List<VideoCardDto> =
        cached("rv:$rankId", TTL_10MIN) {
            get("/ranks/$rankId/videos") { json.decodeFromString<ApiEnvelope<RankVideosData>>(it).data?.list ?: emptyList() }
        }

    // ---- 内存缓存(短视频,不缓存播放地址) ----
    private companion object {
        const val TTL_5M = 5 * 60 * 1000L
        const val TTL_10MIN = 10 * 60 * 1000L
    }
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Any>>()

    private suspend fun <T> cached(key: String, ttlMs: Long, load: suspend () -> T): T {
        val now = System.currentTimeMillis()
        cache[key]?.let { (exp, v) -> if (now < exp) return v as T }
        val v = load() // 失败抛异常时不会写入缓存
        cache[key] = now + ttlMs to (v as Any)
        return v
    }
}

@kotlinx.serialization.Serializable
internal data class Envelope(val code: Int, val msg: String = "")

@kotlinx.serialization.Serializable
internal data class CodeReq(val email: String, val type: String)

@kotlinx.serialization.Serializable
internal data class RegisterReq(val username: String, val password: String, val email: String, val email_code: String)

@kotlinx.serialization.Serializable
internal data class LoginReq(val username: String, val password: String)

@kotlinx.serialization.Serializable
internal data class ZoneListData(val list: List<ZoneDto> = emptyList())
