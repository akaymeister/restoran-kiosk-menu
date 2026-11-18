package com.example.restoranmenu

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInstaller
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.restoranmenu.ui.theme.RestoranMenuTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private lateinit var prefs: SharedPreferences
    private var activeWebView: WebView? = null
    private var userExitedKiosk = false
    private var promptedPinOnResume = false

    companion object {
        private const val PREFS_NAME = "kiosk_config_v2"
        private const val KEY_MENU_URL = "menu_url_v2"
        private const val KEY_ADMIN_PIN = "admin_pin_v2"
        private const val KEY_UPDATE_URL = "update_apk_url_v2"
        private const val DEFAULT_URL = "https://www.simitcay.online/tablet.html"
        private const val DEFAULT_PIN = "1234"
        private const val DEFAULT_UPDATE_URL =
            "https://github.com/akaymeister/restoran-kiosk-menu/releases/latest/download/SimitCay-Menu.apk"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Geri tuşuyla uygulamadan çıkışı engelle
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (activeWebView?.canGoBack() == true) {
                    activeWebView?.goBack()
                }
            }
        })

        hideSystemUI()

        setContent {
            RestoranMenuTheme {
                KioskScreen(
                    currentUrl = getSavedUrl(),
                    currentPin = getSavedPin(),
                    currentUpdateUrl = getSavedUpdateUrl(),
                    isDeviceOwner = isDeviceOwner(),
                    onWebViewReady = { activeWebView = it },
                    onSaveUrl = { newUrl ->
                        val normalized = normalizeMenuUrl(newUrl)
                        saveUrl(normalized)
                        normalized
                    },
                    onSaveUpdateUrl = { newUpdateUrl ->
                        val clean = newUpdateUrl.trim().ifEmpty { DEFAULT_UPDATE_URL }
                        saveUpdateUrl(clean)
                        clean
                    },
                    onSavePin = { newPin -> savePin(newPin) },
                    onLockKiosk = {
                        userExitedKiosk = false
                        setupKioskMode(forcePinPrompt = true)
                    },
                    onExitKioskMode = {
                        userExitedKiosk = true
                        try {
                            stopLockTask()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
        if (!userExitedKiosk) {
            setupKioskMode(forcePinPrompt = !promptedPinOnResume)
            promptedPinOnResume = true
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        }
    }

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun isDeviceOwner(): Boolean {
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isDeviceOwnerApp(packageName)
    }

    private fun setupKioskMode(forcePinPrompt: Boolean) {
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val componentName = ComponentName(this, KioskDeviceAdminReceiver::class.java)

        if (dpm.isDeviceOwnerApp(packageName)) {
            dpm.setLockTaskPackages(componentName, arrayOf(packageName))
        }

        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val isWhitelisted = dpm.isDeviceOwnerApp(packageName) ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && dpm.isLockTaskPermitted(packageName))

        if (am.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE && (isWhitelisted || forcePinPrompt)) {
            try {
                startLockTask()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun normalizeMenuUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return DEFAULT_URL
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
        val uri = Uri.parse(withScheme)
        val host = uri.host?.lowercase() ?: ""
        val path = uri.path ?: ""
        if (host.endsWith("simitcay.online") &&
            (path.isEmpty() || path == "/" || path == "/index.html" || path == "/index.php")
        ) {
            return DEFAULT_URL
        }
        return withScheme
    }

    private fun getSavedUrl(): String = normalizeMenuUrl(prefs.getString(KEY_MENU_URL, DEFAULT_URL) ?: DEFAULT_URL)
    private fun saveUrl(url: String) = prefs.edit().putString(KEY_MENU_URL, url).apply()

    private fun getSavedPin(): String = prefs.getString(KEY_ADMIN_PIN, DEFAULT_PIN) ?: DEFAULT_PIN
    private fun savePin(pin: String) = prefs.edit().putString(KEY_ADMIN_PIN, pin).apply()

    private fun getSavedUpdateUrl(): String =
        prefs.getString(KEY_UPDATE_URL, DEFAULT_UPDATE_URL)?.takeIf { it.isNotBlank() } ?: DEFAULT_UPDATE_URL
    private fun saveUpdateUrl(url: String) = prefs.edit().putString(KEY_UPDATE_URL, url).apply()
}

private fun isNetworkAvailable(context: Context): Boolean {
    return try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (_: SecurityException) {
        true
    } catch (_: Exception) {
        true
    }
}

/**
 * Sunucudan "no-store" gelse bile tablet.html, JS, CSS, menu-data.php ve görselleri
 * tabletin yerel hafızasına kaydeder. İnternet kesildiğinde otomatik önbellekten sunar.
 */
private class OfflineMenuCache(
    context: Context,
    private val onConnectionStatusChanged: (Boolean) -> Unit
) {
    private val appContext = context.applicationContext
    private val cacheDir = File(context.filesDir, "offline_menu_cache").apply { mkdirs() }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val addresses = Dns.SYSTEM.lookup(hostname)
                return addresses.sortedBy { it !is Inet4Address }
            }
        })
        .build()

    fun cachedFileCount(): Int {
        return cacheDir.listFiles { _, name -> name.endsWith(".bin") }?.size ?: 0
    }

    fun clearCache() {
        try {
            cacheDir.listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {
        }
    }

    fun hasCachedMenu(url: String): Boolean {
        val uri = Uri.parse(url)
        val pathKey = "https://${uri.host ?: "www.simitcay.online"}${uri.path ?: "/tablet.html"}"
        return File(cacheDir, hashKey(url) + ".bin").exists() ||
            File(cacheDir, hashKey(pathKey) + ".bin").exists() ||
            File(cacheDir, hashKey("https://www.simitcay.online/tablet.html") + ".bin").exists()
    }

    fun loadCachedHtml(url: String): String? {
        val uri = Uri.parse(url)
        val pathKey = "https://${uri.host ?: "www.simitcay.online"}${uri.path ?: "/tablet.html"}"
        val candidates = listOf(url, pathKey, "https://www.simitcay.online/tablet.html")
        for (candidate in candidates) {
            val f = File(cacheDir, hashKey(candidate) + ".bin")
            if (f.exists()) {
                return injectPolyfill(f.readBytes()).toString(Charsets.UTF_8)
            }
        }
        return null
    }

    fun shouldIntercept(request: WebResourceRequest): Boolean {
        if (request.method != "GET") return false
        val uri = request.url ?: return false
        val host = uri.host ?: return false
        if (!host.endsWith("simitcay.online")) return false
        val path = uri.path?.lowercase() ?: ""
        if (path.endsWith(".mp4") || path.endsWith(".webm")) return false
        return path.endsWith(".html") ||
            path.endsWith(".css") ||
            path.endsWith(".js") ||
            path.endsWith(".json") ||
            path.endsWith(".png") ||
            path.endsWith(".jpg") ||
            path.endsWith(".jpeg") ||
            path.endsWith(".webp") ||
            path.endsWith(".svg") ||
            path.endsWith(".woff") ||
            path.endsWith(".woff2") ||
            path.endsWith(".ttf") ||
            path.contains("menu-data.php") ||
            path.contains("site-logo.php") ||
            path.contains("api.php")
    }

    fun fetchOrServeCached(request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url ?: return null
        val fullUrl = uri.toString()
        val path = uri.path?.lowercase() ?: ""
        val action = uri.getQueryParameter("action")
        val fallbackUrl = if (action != null) {
            "https://${uri.host ?: "www.simitcay.online"}${uri.path ?: ""}?action=$action"
        } else {
            "https://${uri.host ?: "www.simitcay.online"}${uri.path ?: "/tablet.html"}"
        }
        val exactKey = hashKey(fullUrl)
        val fallbackKey = hashKey(fallbackUrl)
        val isCoreMenuRequest = request.isForMainFrame ||
            path.endsWith("tablet.html") ||
            path.contains("menu-data.php") ||
            path.contains("api.php")
        val onlineNow = isNetworkAvailable(appContext)

        if (onlineNow) {
            try {
                val okReq = Request.Builder()
                    .url(fullUrl)
                    .header("Cache-Control", "no-cache, no-store, must-revalidate")
                    .header("Pragma", "no-cache")
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 14; Tablet) AppleWebKit/537.36 Chrome/128.0.0.0 Safari/537.36"
                    )
                    .build()
                httpClient.newCall(okReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val bytes = resp.body?.bytes()
                        if (bytes != null) {
                            val mime = resp.header("Content-Type")?.substringBefore(";")?.trim()
                                ?.takeIf { it.isNotBlank() } ?: guessMimeType(uri)
                            save(exactKey, bytes, mime)
                            if (isCoreMenuRequest || path.contains("site-logo.php")) {
                                save(fallbackKey, bytes, mime)
                            }
                            if (isCoreMenuRequest) {
                                mainHandler.post { onConnectionStatusChanged(true) }
                            }
                            return buildResponse(bytes, mime)
                        }
                    } else if (isCoreMenuRequest) {
                        mainHandler.post { onConnectionStatusChanged(false) }
                    }
                }
            } catch (_: Exception) {
                if (isCoreMenuRequest) {
                    mainHandler.post { onConnectionStatusChanged(false) }
                }
            }
        } else if (isCoreMenuRequest) {
            mainHandler.post { onConnectionStatusChanged(false) }
        }

        return load(exactKey) ?: load(fallbackKey)
    }

    private fun save(key: String, bytes: ByteArray, mime: String) {
        try {
            File(cacheDir, "$key.bin").writeBytes(bytes)
            File(cacheDir, "$key.mime").writeText(mime)
        } catch (_: Exception) {
        }
    }

    private fun load(key: String): WebResourceResponse? {
        val dataFile = File(cacheDir, "$key.bin")
        val mimeFile = File(cacheDir, "$key.mime")
        if (!dataFile.exists()) return null
        val bytes = dataFile.readBytes()
        val mime = if (mimeFile.exists()) mimeFile.readText().trim() else "application/octet-stream"
        return buildResponse(bytes, mime)
    }

    private fun injectPolyfill(bytes: ByteArray): ByteArray {
        val polyfill = "<script>(function(){if(!Object.hasOwn){Object.hasOwn=function(o,p){return o!=null&&Object.prototype.hasOwnProperty.call(o,p);};}if(!String.prototype.replaceAll){String.prototype.replaceAll=function(s,r){if(s instanceof RegExp)return this.replace(s,r);var v=typeof r==='function'?r(String(s)):String(r);return this.split(String(s)).join(v);};}})();</script>"
        val html = bytes.toString(Charsets.UTF_8)
        return if (!html.contains("Object.hasOwn=function") && html.contains("<head>")) {
            html.replaceFirst("<head>", "<head>$polyfill").toByteArray(Charsets.UTF_8)
        } else {
            bytes
        }
    }

    private fun buildResponse(bytes: ByteArray, mime: String): WebResourceResponse {
        val finalBytes = if (mime.startsWith("text/html")) injectPolyfill(bytes) else bytes
        val headers = mapOf(
            "Cache-Control" to "no-store, no-cache, must-revalidate",
            "Pragma" to "no-cache",
            "Expires" to "0",
            "Access-Control-Allow-Origin" to "*"
        )
        return WebResourceResponse(mime, "UTF-8", 200, "OK", headers, ByteArrayInputStream(finalBytes))
    }

    private fun guessMimeType(uri: Uri): String {
        val path = uri.path?.lowercase() ?: ""
        return when {
            path.endsWith(".html") -> "text/html"
            path.endsWith(".css") -> "text/css"
            path.endsWith(".js") || path.contains("menu-data.php") -> "application/javascript"
            path.contains("api.php") -> "application/json"
            path.endsWith(".png") -> "image/png"
            path.endsWith(".webp") || path.contains("site-logo.php") -> "image/webp"
            path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
            path.endsWith(".svg") -> "image/svg+xml"
            else -> "text/plain"
        }
    }

    private fun hashKey(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun KioskScreen(
    currentUrl: String,
    currentPin: String,
    currentUpdateUrl: String,
    isDeviceOwner: Boolean,
    onWebViewReady: (WebView) -> Unit,
    onSaveUrl: (String) -> String,
    onSaveUpdateUrl: (String) -> String,
    onSavePin: (String) -> Unit,
    onLockKiosk: () -> Unit,
    onExitKioskMode: () -> Unit
) {
    val context = LocalContext.current
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var isOnline by remember { mutableStateOf(isNetworkAvailable(context)) }
    var wasDisconnected by remember { mutableStateOf(!isNetworkAvailable(context)) }
    var hasLoadedOnce by remember { mutableStateOf(false) }
    var showInitialError by remember { mutableStateOf(false) }
    var lastErrorReason by remember { mutableStateOf<String?>(null) }
    var showAdminDialog by remember { mutableStateOf(false) }
    var targetUrl by remember { mutableStateOf(currentUrl) }
    var updateApkUrl by remember { mutableStateOf(currentUpdateUrl) }
    var lastLoadedUrl by remember { mutableStateOf(currentUrl) }
    var cachedCount by remember { mutableStateOf(0) }

    val appVersionLabel = remember {
        try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode.toLong()
            }
            "v${pInfo.versionName ?: "2.0"} (Kod: $code)"
        } catch (_: Exception) {
            "v2.0"
        }
    }

    val offlineCache = remember {
        OfflineMenuCache(context) { onlineNow ->
            isOnline = onlineNow
            if (!onlineNow) {
                wasDisconnected = true
            }
        }
    }

    LaunchedEffect(Unit) {
        cachedCount = offlineCache.cachedFileCount()
    }

    DisposableEffect(Unit) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val mainHandler = Handler(Looper.getMainLooper())
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                mainHandler.post {
                    val needsRefresh = wasDisconnected || showInitialError || !hasLoadedOnce
                    wasDisconnected = false
                    isOnline = true
                    if (needsRefresh) {
                        showInitialError = false
                        if (!hasLoadedOnce) {
                            webViewRef?.loadUrl(targetUrl)
                        } else {
                            webViewRef?.clearCache(false)
                            webViewRef?.reload()
                        }
                    }
                }
            }

            override fun onLost(network: Network) {
                mainHandler.post {
                    wasDisconnected = true
                    isOnline = false
                }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(callback)
        } catch (_: Exception) {
        }
        onDispose {
            try {
                cm.unregisterNetworkCallback(callback)
            } catch (_: Exception) {
            }
        }
    }

    LaunchedEffect(showInitialError) {
        while (showInitialError) {
            delay(8000)
            if (isNetworkAvailable(context)) {
                showInitialError = false
                webViewRef?.loadUrl(targetUrl)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    webViewRef = this
                    onWebViewReady(this)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )

                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.displayZoomControls = false
                    settings.setSupportZoom(false)

                    webChromeClient = WebChromeClient()
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            if (request?.isForMainFrame != true) return false
                            val uri = request.url ?: return false
                            val host = uri.host?.lowercase() ?: return true
                            val path = uri.path?.lowercase() ?: ""
                            if (host.endsWith("simitcay.online") && path.contains("tablet.html")) {
                                return false
                            }
                            if (host.endsWith("simitcay.online")) {
                                view?.loadUrl(targetUrl)
                            }
                            return true
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            if (request != null && offlineCache.shouldIntercept(request)) {
                                return offlineCache.fetchOrServeCached(request)
                                    ?: super.shouldInterceptRequest(view, request)
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            super.onReceivedError(view, request, error)
                            if (request?.isForMainFrame == true) {
                                val cachedHtml = offlineCache.loadCachedHtml(targetUrl)
                                if (cachedHtml != null) {
                                    isOnline = false
                                    showInitialError = false
                                    view?.post {
                                        view.loadDataWithBaseURL(
                                            targetUrl,
                                            cachedHtml,
                                            "text/html",
                                            "UTF-8",
                                            targetUrl
                                        )
                                    }
                                } else {
                                    lastErrorReason = error?.description?.toString() ?: "ERR_TIMED_OUT"
                                    showInitialError = true
                                }
                            }
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            if (url != null &&
                                url != "about:blank" &&
                                !url.startsWith("chrome-error://") &&
                                !url.startsWith("data:")
                            ) {
                                hasLoadedOnce = true
                                showInitialError = false
                                lastErrorReason = null
                                cachedCount = offlineCache.cachedFileCount()
                            }
                        }
                    }

                    loadUrl(targetUrl)
                }
            },
            update = { view ->
                view.settings.cacheMode = WebSettings.LOAD_DEFAULT
                if (targetUrl != lastLoadedUrl) {
                    lastLoadedUrl = targetUrl
                    showInitialError = false
                    view.loadUrl(targetUrl)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (showInitialError) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF092746)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp)
                ) {
                    Text(text = "📶", fontSize = 64.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Sunucuya Bağlanılamadı",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "simitcay.online sunucusuna şu an ulaşılamıyor (8 saniyede bir otomatik tekrar denenir).",
                        fontSize = 14.sp,
                        color = Color(0xFFCBD5E1),
                        textAlign = TextAlign.Center
                    )
                    if (!lastErrorReason.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Hata: $lastErrorReason",
                            fontSize = 11.sp,
                            color = Color(0xFFFBBF24),
                            textAlign = TextAlign.Center
                        )
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = {
                                showInitialError = false
                                webViewRef?.loadUrl(targetUrl)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B))
                        ) {
                            Text("Yeniden Dene")
                        }
                        OutlinedButton(
                            onClick = { showAdminDialog = true },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                        ) {
                            Text("Admin Ayarları")
                        }
                    }
                }
            }
        }

        if (!isOnline && !showInitialError) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .background(Color(0xCC0F172A), RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = "Çevrimdışı Mod • Önbellekten Gösteriliyor",
                    color = Color(0xFFFBBF24),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(100.dp)
                .background(Color.Transparent)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = {
                            cachedCount = offlineCache.cachedFileCount()
                            showAdminDialog = true
                        }
                    )
                }
        )

        if (showAdminDialog) {
            HardenedAdminDialog(
                currentPin = currentPin,
                currentUrl = targetUrl,
                currentUpdateUrl = updateApkUrl,
                cachedCount = cachedCount,
                appVersion = appVersionLabel,
                isDeviceOwner = isDeviceOwner,
                onDismiss = { showAdminDialog = false },
                onSaveUrl = { newUrl ->
                    val normalized = onSaveUrl(newUrl)
                    targetUrl = normalized
                },
                onSaveUpdateUrl = { newUpdateUrl ->
                    val clean = onSaveUpdateUrl(newUpdateUrl)
                    updateApkUrl = clean
                    clean
                },
                onSavePin = { onSavePin(it) },
                onReload = {
                    webViewRef?.clearCache(false)
                    webViewRef?.loadUrl(targetUrl)
                },
                onClearCacheAndReload = {
                    offlineCache.clearCache()
                    cachedCount = 0
                    webViewRef?.clearCache(true)
                    webViewRef?.loadUrl(targetUrl)
                },
                onLockKiosk = {
                    showAdminDialog = false
                    onLockKiosk()
                },
                onExitKiosk = {
                    showAdminDialog = false
                    onExitKioskMode()
                }
            )
        }
    }
}

private suspend fun downloadApkFile(
    context: Context,
    apkUrl: String,
    onProgress: (Int) -> Unit
): Result<File> = withContext(Dispatchers.IO) {
    var conn: HttpURLConnection? = null
    try {
        var currentUrl = apkUrl.trim()
        var redirects = 0
        while (true) {
            conn?.disconnect()
            conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 8000
                readTimeout = 20000
                setRequestProperty("User-Agent", "SimitCayKioskUpdater/2.0")
            }
            val code = conn.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val loc = conn.getHeaderField("Location")
                    ?: return@withContext Result.failure(Exception("Yönlendirme adresi alınamadı"))
                currentUrl = URL(URL(currentUrl), loc).toString()
                redirects++
                if (redirects > 6) {
                    return@withContext Result.failure(Exception("Çok fazla yönlendirme"))
                }
                continue
            }
            if (code !in 200..299) {
                val hint = if (code == 404) {
                    "APK bulunamadı (404). GitHub deponuz Gizli (Private) ise APK dosyasını simitcay.online sunucunuza yükleyip güncelleme adresini buraya yazabilirsiniz."
                } else {
                    "Sunucu hatası: HTTP $code"
                }
                return@withContext Result.failure(Exception(hint))
            }
            break
        }

        val activeConn = conn ?: return@withContext Result.failure(Exception("Bağlantı kurulamadı"))
        val totalBytes = activeConn.contentLengthLong
        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val outFile = File(updatesDir, "SimitCay-Menu-update.apk")

        activeConn.inputStream.use { input ->
            outFile.outputStream().use { output ->
                val buffer = ByteArray(16384)
                var downloaded = 0L
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    downloaded += read
                    if (totalBytes > 0L) {
                        val pct = ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
                        withContext(Dispatchers.Main) { onProgress(pct) }
                    }
                }
            }
        }

        val header = ByteArray(2)
        outFile.inputStream().use { it.read(header) }
        if (outFile.length() < 100_000L || header[0] != 0x50.toByte() || header[1] != 0x4B.toByte()) {
            outFile.delete()
            return@withContext Result.failure(Exception("İndirilen dosya geçerli bir APK değil."))
        }
        Result.success(outFile)
    } catch (e: Exception) {
        Result.failure(e)
    } finally {
        conn?.disconnect()
    }
}

private fun installApkFile(
    context: Context,
    apkFile: File,
    isDeviceOwner: Boolean,
    onExitKiosk: () -> Unit
): String {
    if (isDeviceOwner) {
        try {
            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)
            session.openWrite("SimitCayUpdate", 0, apkFile.length()).use { out ->
                apkFile.inputStream().use { input -> input.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent("com.example.restoranmenu.INSTALL_COMPLETE").setPackage(context.packageName)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val sender = PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender
            session.commit(sender)
            session.close()
            return "Sessiz güncelleme başlatıldı, uygulama güncellenip yeniden açılacak..."
        } catch (_: Exception) {
            // Normal kurulum ekranına düş
        }
    }

    onExitKiosk()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
        val permIntent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(permIntent)
        return "Açılan ekrandan 'Bu kaynaktan izin ver' seçeneğini açın, sonra tekrar 'Uygulamayı Güncelle'ye basın."
    }
    val apkUri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        apkFile
    )
    val installIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(apkUri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(installIntent)
    return "Kurulum ekranı açıldı. 'Güncelle'ye basarak silmeden kurabilirsiniz."
}

@Composable
fun HardenedAdminDialog(
    currentPin: String,
    currentUrl: String,
    currentUpdateUrl: String,
    cachedCount: Int,
    appVersion: String,
    isDeviceOwner: Boolean,
    onDismiss: () -> Unit,
    onSaveUrl: (String) -> Unit,
    onSaveUpdateUrl: (String) -> String,
    onSavePin: (String) -> Unit,
    onReload: () -> Unit,
    onClearCacheAndReload: () -> Unit,
    onLockKiosk: () -> Unit,
    onExitKiosk: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isAuthenticated by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    var urlInput by remember { mutableStateOf(currentUrl) }
    var updateUrlInput by remember { mutableStateOf(currentUpdateUrl) }
    var newPinInput by remember { mutableStateOf("") }
    var isUpdatingApk by remember { mutableStateOf(false) }
    var updateStatus by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (!isAuthenticated) "Yönetici Girişi" else "Kiosk Yapılandırması") },
        text = {
            if (!isAuthenticated) {
                Column {
                    OutlinedTextField(
                        value = password,
                        onValueChange = {
                            password = it
                            isError = false
                        },
                        label = { Text("Yönetici PIN (Varsayılan: 1234)") },
                        visualTransformation = PasswordVisualTransformation(),
                        isError = isError,
                        supportingText = { if (isError) Text("Hatalı PIN!") }
                    )
                }
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = "Sürüm: $appVersion",
                        fontSize = 11.sp,
                        color = Color(0xFF475569),
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = if (cachedCount > 0) {
                            "Çevrimdışı Önbellek: Hazır ($cachedCount dosya yedekli)"
                        } else {
                            "Çevrimdışı Önbellek: Henüz yok (Wi-Fi ile 1 kez menüyü açın)"
                        },
                        fontSize = 12.sp,
                        color = if (cachedCount > 0) Color(0xFF15803D) else Color(0xFFB45309),
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (isDeviceOwner) {
                            "Tam Kiosk Kilidi (Device Owner): Aktif"
                        } else {
                            "Ekran Sabitleme Modu: Tam kilit için ADB Device Owner veya Ekran Sabitlemeyi onaylayın"
                        },
                        fontSize = 11.sp,
                        color = Color.Gray
                    )

                    Button(
                        onClick = {
                            if (isUpdatingApk) return@Button
                            val cleanUrl = onSaveUpdateUrl(updateUrlInput)
                            isUpdatingApk = true
                            updateStatus = "APK indiriliyor..."
                            scope.launch {
                                val res = downloadApkFile(context, cleanUrl) { pct ->
                                    updateStatus = "APK indiriliyor (%$pct)..."
                                }
                                isUpdatingApk = false
                                res.onSuccess { apkFile ->
                                    try {
                                        updateStatus = installApkFile(
                                            context = context,
                                            apkFile = apkFile,
                                            isDeviceOwner = isDeviceOwner,
                                            onExitKiosk = onExitKiosk
                                        )
                                    } catch (e: Exception) {
                                        updateStatus = "Kurulum başlatılamadı: ${e.message}"
                                    }
                                }.onFailure { err ->
                                    updateStatus = err.message ?: "APK indirilemedi"
                                }
                            }
                        },
                        enabled = !isUpdatingApk,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isUpdatingApk) "Güncelleme İndiriliyor..." else "Uygulamayı Güncelle (APK)")
                    }

                    if (!updateStatus.isNullOrBlank()) {
                        Text(
                            text = updateStatus!!,
                            fontSize = 11.sp,
                            color = Color(0xFF0F172A),
                            fontWeight = FontWeight.Medium
                        )
                    }

                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        label = { Text("Menü Web Adresi (URL)") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = updateUrlInput,
                        onValueChange = { updateUrlInput = it },
                        label = { Text("APK Güncelleme Adresi (URL)") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = newPinInput,
                        onValueChange = { newPinInput = it },
                        label = { Text("Yeni PIN (Boş bırakılırsa değişmez)") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Button(
                            onClick = {
                                onSaveUrl(urlInput)
                                onSaveUpdateUrl(updateUrlInput)
                                if (newPinInput.isNotBlank()) onSavePin(newPinInput)
                                onDismiss()
                            }
                        ) {
                            Text("Kaydet")
                        }
                        OutlinedButton(onClick = { onReload(); onDismiss() }) {
                            Text("Yenile")
                        }
                        OutlinedButton(onClick = onLockKiosk) {
                            Text("Kilitle")
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            onClearCacheAndReload()
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Önbelleği Temizle & Yenile")
                    }
                }
            }
        },
        confirmButton = {
            if (!isAuthenticated) {
                Button(
                    onClick = {
                        if (password == currentPin) {
                            isAuthenticated = true
                        } else {
                            isError = true
                        }
                    }
                ) {
                    Text("Giriş Yap")
                }
            } else {
                Button(
                    onClick = onExitKiosk,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Kiosk'tan Çık")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Kapat")
            }
        }
    )
}