package com.cycitv

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.cycitv.data.api.CycaniApi
import com.cycitv.data.repository.HomeRepository
import com.cycitv.data.room.AppDatabase
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 用户认证信息(登录 token 等),存 DataStore,不进仓库 */
private val Context.authDataStore by preferencesDataStore(name = "auth")

object AuthStore {
    val Token = stringPreferencesKey("token")
    val Username = stringPreferencesKey("username")

    suspend fun loadToken(context: Context): Pair<String?, String?> {
        val p = context.authDataStore.data.first()
        return p[Token] to p[Username]
    }

    suspend fun save(context: Context, token: String, username: String) {
        context.authDataStore.edit { it[Token] = token; it[Username] = username }
    }

    suspend fun clear(context: Context) {
        context.authDataStore.edit { it.remove(Token); it.remove(Username) }
    }
}

class App : Application() {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var api: CycaniApi
        private set
    lateinit var home: HomeRepository
        private set
    lateinit var imageLoader: ImageLoader
        private set

    /** 本进程内"硬解会崩、需软解"的集数名单(退出进程后重新积累) */
    val softDecodeSections = java.util.Collections.synchronizedSet(mutableSetOf<Long>())

    /** Room DB 在后台线程构建,不阻塞冷启动;首次被使用时(播放页)通过 await 等待 */
    private val dbReady = kotlinx.coroutines.CompletableDeferred<AppDatabase>()
    suspend fun db(): AppDatabase = dbReady.await()

    /** 当前登录用户名(供 UI 显示);无登录为 null */
    @Volatile
    var authUsername: String? = null

    override fun onCreate() {
        super.onCreate()
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        api = CycaniApi(client)
        // 扫码登录:手机端提交的注册/登录操作,落到站方 API;成功返回 token
        com.cycitv.data.PhoneInputServer.authApi = { op, u, p, e, c ->
            when (op) {
                "login" -> api.login(u, p)
                // 注册成功后自动登录,把 token 直接返回给手机端
                "register" -> { api.register(u, p, e, c); api.login(u, p) }
                "sendCode" -> api.sendCode(e, c)
                else -> throw IllegalStateException("unknown op $op")
            }
        }
        // 启动时恢复登录态:从 DataStore 读 token 注入 api,播放才带认证
        scope.launch {
            val (tok, user) = AuthStore.loadToken(this@App)
            if (!tok.isNullOrBlank()) {
                api.token = tok
                authUsername = user
            }
        }
        // Room build 较慢,放后台线程,避免冷启动卡主线程
        scope.launch {
            try {
                dbReady.complete(
                    Room.databaseBuilder(this@App, AppDatabase::class.java, "cycitv.db")
                        .fallbackToDestructiveMigration()
                        .build()
                )
            } catch (e: Throwable) {
                dbReady.completeExceptionally(e)
            }
        }
        home = HomeRepository(api, scope)
        imageLoader = ImageLoader.Builder(this)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
            .crossfade(200)
            .build()
    }
}
