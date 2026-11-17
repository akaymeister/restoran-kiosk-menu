package com.example.restoranmenu

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.restoranmenu.ui.theme.RestoranMenuTheme
import kotlinx.coroutines.delay
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class MainActivity : ComponentActivity() {

    private lateinit var prefs: SharedPreferences

    companion object {
        private const val PREFS_NAME = "kiosk_config"
        private const val KEY_MENU_URL = "menu_url"
        private const val KEY_ADMIN_PIN = "admin_pin"
        private const val DEFAULT_URL = "https://www.simitcay.online/tablet.html"
        private const val DEFAULT_PIN = "1234"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        setupKioskMode()
        hideSystemUI()

        setContent {
            RestoranMenuTheme {
                KioskScreen(
                    currentUrl = getSavedUrl(),
                    currentPin = getSavedPin(),
                    onSaveUrl = { newUrl -> saveUrl(newUrl) },
                    onSavePin = { newPin -> savePin(newPin) },
                    onExitKioskMode = {
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
        tryStartLockTaskSafely()
        hideSystemUI()
    }

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun setupKioskMode() {
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val componentName = ComponentName(this, KioskDeviceAdminReceiver::class.java)

        if (dpm.isDeviceOwnerApp(packageName)) {
            dpm.setLockTaskPackages(componentName, arrayOf(packageName))
        }

        tryStartLockTaskSafely()
    }

    private fun tryStartLockTaskSafely() {
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val permitted = dpm.isDeviceOwnerApp(packageName) ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && dpm.isLockTaskPermitted(packageName))
        if (permitted) {
            try {
                startLockTask()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun getSavedUrl(): String = prefs.getString(KEY_MENU_URL, DEFAULT_URL) ?: DEFAULT_URL
    private fun saveUrl(url: String) = prefs.edit().putString(KEY_MENU_URL, url).apply()

    private fun getSavedPin(): String = prefs.getString(KEY_ADMIN_PIN, DEFAULT_PIN) ?: DEFAULT_PIN
    private fun savePin(pin: String) = prefs.edit().putString(KEY_ADMIN_PIN, pin).apply()
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
 * Sunucudan "no-store" gelse bile menü HTML, JS, CSS, menu-data.php ve görselleri
 * tabletin yerel hafızasına kaydeder. İnternet kesildiğinde anında önbellekten sunar.
 */
private class OfflineMenuCache(
    context: Context,
    private val onConnectionStatusChanged: (Boolean) -> Unit
) {
    private val cacheDir = File(context.filesDir, "offline_menu_cache").apply { mkdirs() }
    private val mainHandler = Handler(Looper.getMainLooper())

    fun hasCachedMenu(url: String): Boolean {
        val uri = Uri.parse(url)
        val pathKey = "https://${uri.host ?: "www.simitcay.online"}${uri.path ?: "/tablet.html"}"
        return File(cacheDir, hashKey(url) + ".bin").exists() ||
            File(cacheDir, hashKey(pathKey) + ".bin").exists() ||
            File(cacheDir, hashKey("https://www.simitcay.online/tablet.html") + ".bin").exists()
    }

    fun shouldIntercept(request: WebResourceRequest): Boolean {
        if (request.method != "GET") return false
        val uri = request.url ?: return false
        val host = uri.host ?: return false
        if (!host.endsWith("simitcay.online")) return false
        val path = uri.path?.lowercase() ?: ""
        if (path.endsWith(".mp4") || path.endsWith(".webm")) return false
        return path == "" ||
            path == "/" ||
            path.endsWith(".html") ||
            path.endsWith(".css") ||
            path.endsWith(".js") ||
            path.endsWith(".png") ||
            path.endsWith(".jpg") ||
            path.endsWith(".jpeg") ||
            path.endsWith(".webp") ||
            path.endsWith(".svg") ||
            path.endsWith(".woff2") ||
            path.contains("menu-data.php") ||
            path.contains("site-logo.php") ||
            (path.contains("api.php") && uri.getQueryParameter("action") == "menu")
    }

    fun fetchOrServeCached(request: WebResourceRequest, online: Boolean): WebResourceResponse? {
        val uri = request.url ?: return null
        val fullUrl = uri.toString()
        val pathKey = "https://${uri.host}${uri.path ?: "/"}"
        val exactKey = hashKey(fullUrl)
        val fallbackKey = hashKey(pathKey)

        if (online) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(fullUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3500
                    readTimeout = 7000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "RestoranKioskTablet/1.0")
                }
                if (conn.responseCode in 200..299) {
                    val bytes = conn.inputStream.readBytes()
                    val mime = conn.contentType?.substringBefore(";")?.trim()
                        ?.takeIf { it.isNotBlank() } ?: guessMimeType(uri)
                    save(exactKey, bytes, mime)
                    if (uri.path?.endsWith("tablet.html") == true || uri.path?.endsWith(".php") == true) {
                        save(fallbackKey, bytes, mime)
                    }
                    mainHandler.post { onConnectionStatusChanged(true) }
                    return buildResponse(bytes, mime)
                }
            } catch (_: Exception) {
                mainHandler.post { onConnectionStatusChanged(false) }
            } finally {
                conn?.disconnect()
            }
        } else {
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

    private fun buildResponse(bytes: ByteArray, mime: String): WebResourceResponse {
        val headers = mapOf(
            "Cache-Control" to "public, max-age=86400",
            "Access-Control-Allow-Origin" to "*"
        )
        return WebResourceResponse(mime, "UTF-8", 200, "OK", headers, ByteArrayInputStream(bytes))
    }

    private fun guessMimeType(uri: Uri): String {
        val path = uri.path?.lowercase() ?: ""
        return when {
            path.endsWith(".html") || path == "" || path == "/" -> "text/html"
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
    onSaveUrl: (String) -> Unit,
    onSavePin: (String) -> Unit,
    onExitKioskMode: () -> Unit
) {
    val context = LocalContext.current
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var isOnline by remember { mutableStateOf(isNetworkAvailable(context)) }
    var hasLoadedOnce by remember { mutableStateOf(false) }
    var showInitialError by remember { mutableStateOf(false) }
    var showAdminDialog by remember { mutableStateOf(false) }
    var targetUrl by remember { mutableStateOf(currentUrl) }
    var lastLoadedUrl by remember { mutableStateOf(currentUrl) }

    val offlineCache = remember {
        OfflineMenuCache(context) { onlineNow ->
            isOnline = onlineNow
        }
    }

    DisposableEffect(Unit) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val mainHandler = Handler(Looper.getMainLooper())
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                mainHandler.post {
                    isOnline = true
                    if (showInitialError || !hasLoadedOnce) {
                        showInitialError = false
                        webViewRef?.loadUrl(targetUrl)
                    }
                }
            }

            override fun onLost(network: Network) {
                mainHandler.post {
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
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )

                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.cacheMode = if (isOnline) WebSettings.LOAD_DEFAULT else WebSettings.LOAD_CACHE_ELSE_NETWORK
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.displayZoomControls = false
                    settings.setSupportZoom(false)

                    webChromeClient = WebChromeClient()
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            if (request != null && offlineCache.shouldIntercept(request)) {
                                return offlineCache.fetchOrServeCached(request, isNetworkAvailable(ctx))
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
                            if (request?.isForMainFrame == true &&
                                !hasLoadedOnce &&
                                !offlineCache.hasCachedMenu(targetUrl)
                            ) {
                                showInitialError = true
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
                            }
                        }
                    }

                    loadUrl(targetUrl)
                }
            },
            update = { view ->
                view.settings.cacheMode = if (isOnline) WebSettings.LOAD_DEFAULT else WebSettings.LOAD_CACHE_ELSE_NETWORK
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
                        text = "İnternet Bağlantısı Bekleniyor",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "İlk kurulum için internet bağlantısı gerekiyor. Wi-Fi bağlandığında menü otomatik açılacaktır.",
                        fontSize = 14.sp,
                        color = Color(0xFFCBD5E1),
                        textAlign = TextAlign.Center
                    )
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
                            showAdminDialog = true
                        }
                    )
                }
        )

        if (showAdminDialog) {
            HardenedAdminDialog(
                currentPin = currentPin,
                currentUrl = targetUrl,
                onDismiss = { showAdminDialog = false },
                onSaveUrl = { newUrl ->
                    targetUrl = newUrl
                    onSaveUrl(newUrl)
                },
                onSavePin = { onSavePin(it) },
                onReload = { webViewRef?.loadUrl(targetUrl) },
                onExitKiosk = {
                    showAdminDialog = false
                    onExitKioskMode()
                }
            )
        }
    }
}

@Composable
fun HardenedAdminDialog(
    currentPin: String,
    currentUrl: String,
    onDismiss: () -> Unit,
    onSaveUrl: (String) -> Unit,
    onSavePin: (String) -> Unit,
    onReload: () -> Unit,
    onExitKiosk: () -> Unit
) {
    var isAuthenticated by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    var urlInput by remember { mutableStateOf(currentUrl) }
    var newPinInput by remember { mutableStateOf("") }

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
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        label = { Text("Menü Web Adresi (URL)") },
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
                                if (newPinInput.isNotBlank()) onSavePin(newPinInput)
                                onDismiss()
                            }
                        ) {
                            Text("Kaydet & Yenile")
                        }
                        OutlinedButton(onClick = { onReload(); onDismiss() }) {
                            Text("Yenile")
                        }
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