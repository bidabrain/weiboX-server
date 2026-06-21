package com.weibox.app

import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.weibox.app.data.prefs.AppPreferences
import com.weibox.app.ui.theme.WeiboXTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject

// 与 server captcha.py 的 VIEWPORT 对齐
private const val VIEWPORT_W = 390f
private const val VIEWPORT_H = 760f

@AndroidEntryPoint
class CaptchaActivity : ComponentActivity() {

    @Inject lateinit var prefs: AppPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = prefs.darkMode.collectAsState(initial = false).value
            WeiboXTheme(darkTheme = dark) {
                CaptchaScreen(prefs = prefs, onClose = { finish() })
            }
        }
    }
}

private class CaptchaController(baseUrl: String, token: String) {
    val frame = MutableStateFlow<ImageBitmap?>(null)
    val status = MutableStateFlow("连接中…")

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private var ws: WebSocket? = null

    private val wsUrl = baseUrl.trim().trimEnd('/')
        .replaceFirst("https://", "wss://")
        .replaceFirst("http://", "ws://") + "/api/v1/captcha/ws"
    private val bearer = "Bearer $token"

    fun connect() {
        val req = Request.Builder().url(wsUrl).header("Authorization", bearer).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val o = JSONObject(text)
                    when (o.optString("type")) {
                        "frame" -> {
                            val bytes = Base64.decode(o.optString("data"), Base64.DEFAULT)
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let {
                                frame.value = it.asImageBitmap()
                                status.value = ""
                            }
                        }
                        "state" -> {
                            if (!o.optBoolean("pending")) status.value = "当前无待处理验证码"
                        }
                        else -> {}
                    }
                    Unit
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                status.value = "连接失败：${t.message}"
            }
        })
    }

    fun send(type: String, x: Float = 0f, y: Float = 0f) {
        ws?.send(JSONObject().put("type", type).put("x", x).put("y", y).toString())
    }

    fun close() {
        runCatching { ws?.close(1000, null) }
        runCatching { client.dispatcher.executorService.shutdown() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CaptchaScreen(prefs: AppPreferences, onClose: () -> Unit) {
    // 读取 server 地址 + token，构建并连接 controller
    val controller by produceState<CaptchaController?>(initialValue = null) {
        val url = prefs.serverUrl.first()
        val token = prefs.apiToken.first()
        value = if (url.isBlank() || token.isBlank()) null
        else CaptchaController(url, token).also { it.connect() }
    }
    DisposableEffect(controller) {
        onDispose { controller?.close() }
    }

    val frame = controller?.frame?.collectAsState()?.value
    val status = controller?.status?.collectAsState()?.value ?: "未配置服务器"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("人工处理验证码") },
                actions = { TextButton(onClick = onClose) { Text("关闭") } }
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "在下方画面里直接点击 / 拖动滑块完成验证，再点「完成验证」。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(12.dp))

            var boxSize by remember { mutableStateOf(IntSize.Zero) }
            val maxW = LocalConfiguration.current.screenWidthDp.dp - 32.dp

            Box(
                Modifier
                    .widthIn(max = maxW)
                    .fillMaxWidth()
                    .aspectRatio(VIEWPORT_W / VIEWPORT_H)
                    .background(Color.White)
                    .onSizeChanged { boxSize = it }
                    .pointerInput(controller) {
                        val c = controller ?: return@pointerInput
                        awaitEachGesture {
                            fun map(x: Float, y: Float): Pair<Float, Float> {
                                val w = if (boxSize.width > 0) boxSize.width.toFloat() else size.width.toFloat()
                                val h = if (boxSize.height > 0) boxSize.height.toFloat() else size.height.toFloat()
                                return (x / w * VIEWPORT_W) to (y / h * VIEWPORT_H)
                            }
                            val down = awaitFirstDown()
                            var (lx, ly) = map(down.position.x, down.position.y)
                            c.send("down", lx, ly)
                            while (true) {
                                val event = awaitPointerEvent()
                                val ch = event.changes.firstOrNull() ?: break
                                val (mx, my) = map(ch.position.x, ch.position.y)
                                lx = mx; ly = my
                                if (ch.pressed) c.send("move", mx, my) else break
                            }
                            c.send("up", lx, ly)
                        }
                    }
            ) {
                Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                    if (frame != null) {
                        Image(
                            bitmap = frame,
                            contentDescription = "验证码",
                            modifier = Modifier.matchParentSize(),
                            contentScale = ContentScale.FillBounds
                        )
                    } else {
                        Text(status, color = Color.Gray)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { controller?.send("resolve"); onClose() }) { Text("完成验证") }
                OutlinedButton(onClick = { controller?.send("reload") }) { Text("刷新") }
                OutlinedButton(onClick = { controller?.send("cancel"); onClose() }) { Text("取消") }
            }
        }
    }
}
