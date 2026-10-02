package org.aohp.driver.terminal

import android.annotation.SuppressLint
import android.util.Log
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.webkit.WebViewAssetLoader
import org.aohp.driver.DriverApp
import org.aohp.driver.ui.CenteredMessage
import org.json.JSONObject

private const val TERM_URL = "https://appassets.androidplatform.net/assets/term/index.html"

/** Extra-key row: label → bytes sent to the pty (or a modifier toggle). */
private val EXTRA_KEYS: List<Pair<String, String>> = listOf(
    "Esc" to "\u001b", "Tab" to "\t", "Ctrl" to "", "Alt" to "",
    "←" to "\u001b[D", "↓" to "\u001b[B", "↑" to "\u001b[A", "→" to "\u001b[C",
    "Home" to "\u001b[H", "End" to "\u001b[F", "PgUp" to "\u001b[5~", "PgDn" to "\u001b[6~",
    "-" to "-", "|" to "|", "/" to "/", "~" to "~",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as DriverApp
    val selected by app.settings.selectedEnv.collectAsStateWithLifecycle(initialValue = null)
    val env = selected
    var session by remember(env) { mutableStateOf<PtySession?>(null) }
    var error by remember(env) { mutableStateOf<String?>(null) }
    var status by remember(env) { mutableStateOf("") }
    var reopenTick by remember { mutableStateOf(0) }
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }

    LaunchedEffect(env, reopenTick) {
        session = null; error = null
        if (env == null) return@LaunchedEffect
        app.ptySessions.open(env).onSuccess { session = it; status = "connected" }.onFailure { error = it.message ?: it.toString() }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Terminal" + (env?.let { " · $it" } ?: "") + if (status.isNotEmpty()) "  ($status)" else "") },
                actions = {
                    IconButton(onClick = { env?.let { app.ptySessions.close(it) }; reopenTick++ }) { Icon(Icons.Filled.Refresh, "Reconnect") }
                    IconButton(onClick = { env?.let { app.ptySessions.close(it) }; session = null; status = "closed" }) { Icon(Icons.Filled.PowerSettingsNew, "Close shell") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            when {
                env == null -> CenteredMessage("Select an environment on the Runtime tab first.", Modifier.weight(1f))
                error != null -> CenteredMessage("openShell failed:\n$error", Modifier.weight(1f))
                session == null -> CenteredMessage(if (status == "closed") "Shell closed. Tap ↻ to reconnect." else "Opening shell in $env…", Modifier.weight(1f))
                else -> TerminalWebView(session!!, Modifier.weight(1f), onStatus = { status = it },
                    ctrl = ctrl, alt = alt, consumeMods = { ctrl = false; alt = false })
            }
            val s = session
            if (s != null) {
                Row(Modifier.fillMaxWidth().height(44.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    EXTRA_KEYS.forEach { (label, seq) ->
                        val active = (label == "Ctrl" && ctrl) || (label == "Alt" && alt)
                        FilledTonalButton(
                            onClick = {
                                when (label) {
                                    "Ctrl" -> ctrl = !ctrl
                                    "Alt" -> alt = !alt
                                    else -> { s.write(seq); ctrl = false; alt = false }
                                }
                            },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            colors = if (active) androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
                            else androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(),
                        ) { Text(label, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelLarge) }
                    }
                }
            }
        }
    }
}

/** Bridge object exposed to JS as window.AohpTerm. */
private class TermBridge(
    private val session: PtySession,
    private val modState: () -> Pair<Boolean, Boolean>,
    private val consumeMods: () -> Unit,
    private val onReady: () -> Unit,
) {
    @JavascriptInterface fun onInput(data: String) {
        val (ctrl, alt) = modState()
        var bytes = data.toByteArray(Charsets.UTF_8)
        if ((ctrl || alt) && data.length == 1) {
            val c = data[0]
            if (ctrl) {
                val code = when {
                    c in 'a'..'z' -> c.code - 'a'.code + 1
                    c in 'A'..'Z' -> c.code - 'A'.code + 1
                    c == '[' -> 27; c == '\\' -> 28; c == ']' -> 29; c == '^' -> 30; c == '_' -> 31
                    c == ' ' || c == '@' -> 0
                    else -> -1
                }
                if (code >= 0) bytes = byteArrayOf(code.toByte())
            }
            if (alt) bytes = byteArrayOf(0x1b) + bytes
            consumeMods()
        }
        session.write(bytes)
    }
    @JavascriptInterface fun onBinary(data: String) { session.write(ByteArray(data.length) { data[it].code.toByte() }) }
    @JavascriptInterface fun onResize(cols: Int, rows: Int) { session.resize(cols, rows) }
    @JavascriptInterface fun onReady() { onReady() }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TerminalWebView(session: PtySession, modifier: Modifier, onStatus: (String) -> Unit,
                            ctrl: Boolean, alt: Boolean, consumeMods: () -> Unit) {
    val ctx = LocalContext.current
    val mods = remember { mutableStateOf(ctrl to alt) }
    mods.value = ctrl to alt
    val webView = remember(session) {
        val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx)).build()
        WebView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.allowFileAccess = false
            setBackgroundColor(0xFF0B0F14.toInt())
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: android.webkit.WebResourceRequest) = loader.shouldInterceptRequest(request.url)
                override fun onPageFinished(view: WebView, url: String?) {
                    // page is ready: attach pty → JS pipe
                    session.attach(
                        s = { b64 -> post { evaluateJavascript("termWrite('$b64')", null) } },
                        closedCb = { post { evaluateJavascript("termWrite('" + android.util.Base64.encodeToString("\r\n[${session.exitReason ?: "closed"}]\r\n".toByteArray(), android.util.Base64.NO_WRAP) + "')", null); onStatus("exited") } },
                    )
                    post { evaluateJavascript("termFit(); termFocus();", null) }
                    onStatus("connected")
                }
            }
            addJavascriptInterface(TermBridge(session, { mods.value }, consumeMods, {}), "AohpTerm")
            loadUrl(TERM_URL)
        }
    }
    DisposableEffect(session) {
        onDispose {
            session.detach()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
            Log.d(PtySession.TAG, "terminal webview disposed (session kept alive)")
        }
    }
    AndroidView(factory = { webView }, modifier = modifier.fillMaxWidth(), update = { it.post { it.evaluateJavascript("termFit()", null) } })
}

@Suppress("unused") private fun jsString(s: String): String = JSONObject.quote(s)
