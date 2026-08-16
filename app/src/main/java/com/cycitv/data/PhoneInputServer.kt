package com.cycitv.data

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import fi.iki.elonen.NanoWSD.WebSocket
import fi.iki.elonen.NanoWSD.WebSocketFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 手机扫码输入服务器(WebSocket 方案,参考 sakura-animation/看动漫):
 * - 电视端起局域网 HTTP+WS 服务, 搜索页显示二维码(含 URL 和 token)
 * - 手机扫码打开网页, 页面连 WebSocket 长连接
 * - 手机输入框边打字边推字(INPUT), 回车/按钮触发搜索(SUBMIT)
 * - 长连接避免 HTTP POST 的 Expect:100-continue 握手死锁, 体验更顺滑
 * 同时承载扫码登录: 手机打开 /login 页填账号密码/注册, 服务端调站方 API,
 * 成功后将 token 通过 LOGIN_RESULT WS 消息推回电视端显示。
 */
object PhoneInputServer {

    private const val TAG = "CYC-PhoneInput"
    private const val PORT = 18677

    /** 会话 token: 防局域网内其他人乱投关键词; URL 中校验 */
    private val token: String by lazy {
        val bytes = ByteArray(8)
        java.security.SecureRandom().nextBytes(bytes)
        bytes.joinToString("") { "%02x".format(it) }
    }

    private val _keyword = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** 手机实时输入的当前文本 */
    val keyword: SharedFlow<String> = _keyword

    private val _submit = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** 手机点击"搜索"提交的关键词, UI 收集后自动搜索 */
    val submit: SharedFlow<String> = _submit

    /** 扫码登录结果: Pair(成功, 消息); 成功时消息为 token, 失败时为错误描述 */
    private val _loginResult = MutableSharedFlow<LoginResult>(extraBufferCapacity = 8)
    val loginResult: SharedFlow<LoginResult> = _loginResult

    /** 需要由 App 注入的站方 API 调用(登录注册都走它),避免本 object 依赖具体实现 */
    @Volatile
    var authApi: (suspend (op: String, username: String, password: String, email: String, code: String) -> String)? = null

    /** 扫码登录结果: ok=true 时 token/username 有效 */
    data class LoginResult(val ok: Boolean, val msg: String, val token: String = "", val username: String = "")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var server: WsServer? = null
    private val started = AtomicBoolean(false)

    /** 局域网 IPv4, 用于拼二维码 URL; 无网络时返回 null */
    fun lanIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { ni ->
            if (!ni.isUp || ni.isLoopback) return@forEach
            ni.inetAddresses.toList().forEach { addr ->
                if (addr is Inet4Address && addr.isSiteLocalAddress) return addr.hostAddress
            }
        }
        null
    }.getOrNull()

    /** 二维码内容 / 手机访问地址 */
    fun url(): String? = lanIpv4()?.let { "http://$it:$PORT/?t=$token" }

    /** 扫码登录页地址(设置页显示此二维码) */
    fun loginUrl(): String? = lanIpv4()?.let { "http://$it:$PORT/login?t=$token" }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        try {
            server = WsServer(PORT).apply { start(5_000, false) }
            Log.i(TAG, "started at ${url()}")
        } catch (e: Exception) {
            Log.e(TAG, "start fail", e)
            started.set(false)
        }
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        runCatching { server?.stop() }
        server = null
    }

    // ---------- 手机网页端 ----------

    internal class WsServer(port: Int) : NanoWSD(port) {
        private val sessions = ConcurrentHashMap.newKeySet<WsHandler>()

        override fun openWebSocket(handshake: NanoHTTPD.IHTTPSession): WebSocket {
            // WS 握手同样校验 token, 防止绕过 HTTP 层
            if (handshake.parms["t"] != token) {
                throw NanoWSD.WebSocketException(
                    NanoWSD.WebSocketFrame.CloseCode.PolicyViolation, "forbidden"
                )
            }
            return WsHandler(handshake, sessions)
        }

        override fun serveHttp(session: NanoHTTPD.IHTTPSession): Response {
            val uri = session.uri
            val params = session.parms
            // token 校验: 网页和 WS 握手都要求带正确 token
            if (params["t"] != token) {
                return newFixedLengthResponse(Response.Status.FORBIDDEN, NanoHTTPD.MIME_PLAINTEXT, "forbidden")
            }
            return when {
                uri == "/" || uri == "/index.html" -> newFixedLengthResponse(
                    Response.Status.OK, NanoHTTPD.MIME_HTML, htmlPage()
                )
                uri == "/login" -> newFixedLengthResponse(
                    Response.Status.OK, NanoHTTPD.MIME_HTML, loginHtmlPage()
                )
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "not found")
            }
        }
    }

    internal class WsHandler(
        handshake: NanoHTTPD.IHTTPSession,
        private val sessions: MutableSet<WsHandler>,
    ) : NanoWSD.WebSocket(handshake) {

        override fun onOpen() {
            sessions.add(this)
            // 欢迎消息: 让页面知道连接已建立
            send(jsonOf("ok" to true, "msg" to "connected"))
        }

        override fun onClose(code: WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
            sessions.remove(this)
        }

        override fun onMessage(message: WebSocketFrame) {
            try {
                val payload = message.textPayload
                val obj = JSONObject(payload)
                when (obj.optString("operation").uppercase()) {
                    "INPUT" -> {
                        val content = obj.optString("content", "")
                        scope.launch { _keyword.emit(content) }
                        send(jsonOf("ok" to true))
                    }
                    "SUBMIT" -> {
                        val content = obj.optString("content", "").trim()
                        if (content.isNotEmpty()) {
                            scope.launch { _submit.emit(content) }
                        }
                        send(jsonOf("ok" to true))
                    }
                    // ---- 扫码登录 ----
                    "LOGIN" -> handleLogin(obj) { send(it) }
                    "REGISTER" -> handleRegister(obj) { send(it) }
                    "SEND_CODE" -> handleSendCode(obj) { send(it) }
                    "PING" -> send(jsonOf("ok" to true))
                    else -> send(jsonOf("ok" to false, "msg" to "unknown operation"))
                }
            } catch (e: Exception) {
                Log.w(TAG, "ws message: $e")
                send(jsonOf("ok" to false, "msg" to "bad message"))
            }
        }

        private fun handleLogin(obj: JSONObject, reply: (String) -> Unit) {
            val username = obj.optString("username", "").trim()
            val password = obj.optString("password", "")
            val worker: suspend () -> String = {
                val api = authApi ?: throw IllegalStateException("认证服务未就绪")
                api("login", username, password, "", "")
            }
            // 注意: send() 是阻塞网络 IO, 必须在 IO 线程调, 否则主线程 StrictMode 崩溃
            scope.launch {
                val r = runCatching { worker() }
                val ok = r.isSuccess
                val token = r.getOrNull()
                val err = r.exceptionOrNull()?.message ?: "登录失败"
                if (ok) _loginResult.emit(LoginResult(true, "登录成功", token ?: "", username))
                withContext(Dispatchers.IO) { reply(jsonOf("ok" to ok, "msg" to (if (ok) "登录成功" else err), "token" to (token ?: ""))) }
            }
        }

        private fun handleRegister(obj: JSONObject, reply: (String) -> Unit) {
            val username = obj.optString("username", "").trim()
            val password = obj.optString("password", "")
            val email = obj.optString("email", "").trim()
            val code = obj.optString("code", "").trim()
            scope.launch {
                val worker: suspend () -> String = {
                    val api = authApi ?: throw IllegalStateException("认证服务未就绪")
                    api("register", username, password, email, code)
                }
                val r = runCatching { worker() }
                val ok = r.isSuccess
                val token = r.getOrNull()
                val err = r.exceptionOrNull()?.message ?: "注册失败"
                if (ok) _loginResult.emit(LoginResult(true, "注册成功", token ?: "", username))
                withContext(Dispatchers.IO) { reply(jsonOf(
                    "ok" to ok,
                    "msg" to (if (ok) "注册成功" else err),
                    "token" to (token ?: "")
                )) }
            }
        }

        private fun handleSendCode(obj: JSONObject, reply: (String) -> Unit) {
            val email = obj.optString("email", "").trim()
            val type = obj.optString("type", "register")
            scope.launch {
                val api = authApi ?: run {
                    withContext(Dispatchers.IO) { reply(jsonOf("ok" to false, "msg" to "认证服务未就绪")) }
                    return@launch
                }
                val r = runCatching { api("sendCode", "", "", email, type) }
                val ok = r.isSuccess
                withContext(Dispatchers.IO) { reply(jsonOf("ok" to ok, "msg" to r.getOrElse { it.message ?: "发送失败" }.toString())) }
            }
        }

        override fun onPong(pong: WebSocketFrame?) {}
        override fun onException(exception: java.io.IOException) {
            sessions.remove(this)
        }

        private fun jsonOf(vararg pairs: Pair<String, Any?>): String {
            val o = JSONObject()
            pairs.forEach { (k, v) -> o.put(k, v) }
            return o.toString()
        }
    }

    // ---------- 手机端网页 ----------

    private fun htmlPage(): String = """
        <!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>阿拉蕾TV 搜索</title>
        <style>
          body{background:#0f1115;color:#e6e6e6;font-family:-apple-system,"PingFang SC","Microsoft YaHei",sans-serif;
               display:flex;flex-direction:column;align-items:center;justify-content:center;min-height:100vh;margin:0;padding:24px;box-sizing:border-box}
          .card{width:100%;max-width:520px}
          h1{font-size:22px;color:#7aa2ff;margin:0 0 6px}
          .tip{color:#8a8f98;font-size:13px;margin:0 0 24px}
          input{width:100%;box-sizing:border-box;padding:14px 16px;font-size:18px;border-radius:12px;
                border:1px solid #333;background:#1a1d24;color:#fff;outline:none;margin-bottom:14px}
          input:focus{border-color:#7aa2ff}
          button{width:100%;padding:14px;font-size:18px;border:none;border-radius:12px;background:#7aa2ff;color:#0f1115;
                 font-weight:600;cursor:pointer}
          button:disabled{opacity:.5}
          #msg{margin-top:14px;font-size:14px;min-height:20px;color:#8a8f98;text-align:center}
          .ok{color:#7ee787!important}
        </style></head><body>
        <div class="card">
          <h1>阿拉蕾TV · 发送搜索词</h1>
          <p class="tip">输入要搜索的番剧名，边打字电视同步显示，点按钮或回车即搜索</p>
          <input id="q" type="text" placeholder="例如：咒术回战" autocomplete="off" enterkeyhint="search">
          <button id="btn" disabled>发送到电视</button>
          <div id="msg">连接电视中…</div>
        </div>
        <script>
          var ws = null;
          function connect(){
            var proto = location.protocol === 'https:' ? 'wss://' : 'ws://';
            ws = new WebSocket(proto + location.host + '/?t=' + location.search.replace(/^\?t=/, ''));
            ws.onopen = function(){ document.getElementById('msg').textContent='已连接电视 ✓'; document.getElementById('btn').disabled=false; };
            ws.onclose = function(){ document.getElementById('msg').textContent='连接已断开，正在重连…'; document.getElementById('btn').disabled=true; setTimeout(connect, 2000); };
            ws.onerror = function(){ document.getElementById('msg').textContent='连接失败，请确认电视已开机且在同一WiFi'; };
          }
          connect();
          function send(){
            var q = document.getElementById('q').value.trim();
            if(!q) return;
            if(ws && ws.readyState === 1){
              ws.send(JSON.stringify({operation:'SUBMIT', content:q}));
              document.getElementById('msg').textContent='已发送 ✓ 电视正在搜索「'+q+'」';
              document.getElementById('msg').className='ok';
            } else {
              document.getElementById('msg').textContent='未连接电视，请重试';
            }
          }
          var inputEl = document.getElementById('q');
          inputEl.addEventListener('input', function(){
            if(ws && ws.readyState === 1){
              ws.send(JSON.stringify({operation:'INPUT', content:this.value}));
            }
          });
          inputEl.addEventListener('keydown', function(e){ if(e.key==='Enter') send(); });
          document.getElementById('btn').addEventListener('click', send);
        </script></body></html>
    """.trimIndent()

    /** 手机端扫码登录页:登录 / 注册(邮箱验证码) 双标签 */
    private fun loginHtmlPage(): String = """
        <!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>阿拉蕾TV 登录</title>
        <style>
          body{background:#0f1115;color:#e6e6e6;font-family:-apple-system,"PingFang SC","Microsoft YaHei",sans-serif;
               display:flex;flex-direction:column;align-items:center;justify-content:center;min-height:100vh;margin:0;padding:24px;box-sizing:border-box}
          .card{width:100%;max-width:480px}
          h1{font-size:22px;color:#7aa2ff;margin:0 0 6px}
          .tip{color:#8a8f98;font-size:13px;margin:0 0 20px}
          .tabs{display:flex;gap:10px;margin-bottom:18px}
          .tab{flex:1;text-align:center;padding:10px;border-radius:10px;background:#1a1d24;color:#8a8f98;cursor:pointer;font-size:15px}
          .tab.on{background:#7aa2ff;color:#0f1115;font-weight:600}
          .field{display:none}
          .field.on{display:block}
          input{width:100%;box-sizing:border-box;padding:13px 16px;font-size:17px;border-radius:12px;
                border:1px solid #333;background:#1a1d24;color:#fff;outline:none;margin-bottom:12px}
          input:focus{border-color:#7aa2ff}
          .row{display:flex;gap:10px}
          .row input{flex:1}
          .row button{width:120px;flex:none;padding:13px;font-size:14px}
          button{width:100%;padding:14px;font-size:17px;border:none;border-radius:12px;background:#7aa2ff;color:#0f1115;
                 font-weight:600;cursor:pointer}
          button:disabled{opacity:.5}
          .mini{background:#2a2d36;color:#fff;border-radius:10px}
          #msg{margin-top:14px;font-size:14px;min-height:20px;color:#8a8f98;text-align:center;white-space:pre-line}
          .ok{color:#7ee787!important}
          .err{color:#ff8080!important}
        </style></head><body>
        <div class="card">
          <h1>阿拉蕾TV · 账号登录</h1>
          <p class="tip">登录后电视端即可正常播放。没有账号可直接注册(邮箱收验证码)。</p>
          <div class="tabs">
            <div class="tab on" id="tabLogin" onclick="switchTab('login')">登录</div>
            <div class="tab" id="tabReg" onclick="switchTab('reg')">注册</div>
          </div>

          <!-- 登录表单 -->
          <div class="field on" id="fLogin">
            <input id="lu" type="text" placeholder="用户名" autocomplete="off">
            <input id="lp" type="password" placeholder="密码">
            <button id="btnLogin" onclick="doLogin()">登 录</button>
          </div>

          <!-- 注册表单 -->
          <div class="field" id="fReg">
            <input id="ru" type="text" placeholder="用户名(字母数字, 4-20位)" autocomplete="off">
            <input id="rp" type="password" placeholder="密码(至少6位)">
            <input id="re" type="email" placeholder="邮箱(QQ/163/Gmail等)" autocomplete="off">
            <div class="row">
              <input id="rc" type="text" placeholder="邮箱验证码" autocomplete="off">
              <button class="mini" id="btnCode" onclick="sendCode()">发验证码</button>
            </div>
            <button id="btnReg" onclick="doRegister()">注 册 并 登 录</button>
          </div>

          <div id="msg"></div>
        </div>
        <script>
          var ws = null;
          var account = ''; // 登录成功后的 token, 注册则自动登录
          function connect(){
            var proto = location.protocol === 'https:' ? 'wss://' : 'ws://';
            ws = new WebSocket(proto + location.host + '/?t=' + location.search.replace(/^\?t=/, ''));
            ws.onopen = function(){ /* 已连接 */ };
            ws.onclose = function(){ setTimeout(connect, 2000); };
          }
          connect();
          function msg(t, cls){ var el=document.getElementById('msg'); el.textContent=t; el.className=cls||''; }
          function sendOp(op, data, cb){
            if(!ws || ws.readyState !== 1){ msg('未连接电视, 请确认电视已开机且在同一 WiFi'); return; }
            data.operation = op;
            ws.send(JSON.stringify(data));
            ws.onmessage = function(e){
              var r = JSON.parse(e.data);
              if(cb) cb(r);
            };
          }
          function switchTab(t){
            var login = t==='login';
            document.getElementById('tabLogin').className = 'tab'+(login?' on':'');
            document.getElementById('tabReg').className = 'tab'+(login?'':' on');
            document.getElementById('fLogin').className = 'field'+(login?' on':'');
            document.getElementById('fReg').className = 'field'+(login?'':' on');
          }
          var _cd = null;
          function sendCode(){
            var email = document.getElementById('re').value.trim();
            if(!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)){ msg('请输入正确的邮箱'); return; }
            sendOp('SEND_CODE', {email:email, type:'register'}, function(r){
              msg(r.ok ? '验证码已发送, 请查收邮箱' : (r.msg||'发送失败'), r.ok?'ok':'err');
            });
            clearTimeout(_cd);
            var btn = document.getElementById('btnCode');
            var n = 60; btn.disabled = true;
            _cd = setInterval(function(){ btn.textContent = n>0 ? (n+'s') : '发验证码'; if(n<=0){clearInterval(_cd); btn.disabled=false;} n--; }, 1000);
          }
          function doLogin(){
            var u = document.getElementById('lu').value.trim();
            var p = document.getElementById('lp').value;
            if(!u || !p){ msg('请输入用户名和密码'); return; }
            sendOp('LOGIN', {username:u, password:p}, function(r){
              if(r.ok && r.token){
                account = r.token;
                msg('登录成功 ✓ 电视端已同步, 可返回电视操作', 'ok');
              } else msg(r.msg||'登录失败, 请检查用户名密码', 'err');
            });
          }
          function doRegister(){
            var u = document.getElementById('ru').value.trim();
            var p = document.getElementById('rp').value;
            var e = document.getElementById('re').value.trim();
            var c = document.getElementById('rc').value.trim();
            if(!u || !p || !e || !c){ msg('请填写完整注册信息'); return; }
            sendOp('REGISTER', {username:u, password:p, email:e, code:c}, function(r){
              if(r.ok && r.token){
                account = r.token;
                msg('注册成功并已登录 ✓ 电视端已同步', 'ok');
              } else msg(r.msg||'注册失败', 'err');
            });
          }
        </script></body></html>
    """.trimIndent()
}