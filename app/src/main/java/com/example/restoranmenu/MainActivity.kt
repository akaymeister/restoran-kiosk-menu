package com.example.restoranmenu

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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
        try {
            startLockTask()
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
        val devicePolicyManager = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val componentName = ComponentName(this, KioskDeviceAdminReceiver::class.java)

        if (devicePolicyManager.isDeviceOwnerApp(packageName)) {
            devicePolicyManager.setLockTaskPackages(componentName, arrayOf(packageName))
        }

        try {
            startLockTask()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getSavedUrl(): String = prefs.getString(KEY_MENU_URL, DEFAULT_URL) ?: DEFAULT_URL
    private fun saveUrl(url: String) = prefs.edit().putString(KEY_MENU_URL, url).apply()

    private fun getSavedPin(): String = prefs.getString(KEY_ADMIN_PIN, DEFAULT_PIN) ?: DEFAULT_PIN
    private fun savePin(pin: String) = prefs.edit().putString(KEY_ADMIN_PIN, pin).apply()
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
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var hasError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var showAdminDialog by remember { mutableStateOf(false) }
    var targetUrl by remember { mutableStateOf(currentUrl) }

    Box(modifier = Modifier.fillMaxSize()) {
        if (!hasError) {
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        webViewRef = this
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )

                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.cacheMode = WebSettings.LOAD_DEFAULT
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.displayZoomControls = false
                        settings.setSupportZoom(false)

                        webViewClient = object : WebViewClient() {
                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                super.onReceivedError(view, request, error)
                                if (request?.isForMainFrame == true) {
                                    hasError = true
                                    errorMessage = "Menü sunucusuna bağlanılamadı. Lütfen internet bağlantısını kontrol edin."
                                }
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                hasError = false
                            }
                        }

                        loadUrl(targetUrl)
                    }
                },
                update = { view ->
                    if (view.url != targetUrl && !hasError) {
                        view.loadUrl(targetUrl)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
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
                        text = "Çıtır Simit Menüsüne Bağlanılamadı",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = errorMessage,
                        fontSize = 14.sp,
                        color = Color(0xFFCBD5E1),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = {
                                hasError = false
                                webViewRef?.reload()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B))
                        ) {
                            Text("Yeniden Bağlan")
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
                    webViewRef?.loadUrl(newUrl)
                },
                onSavePin = { onSavePin(it) },
                onReload = { webViewRef?.reload() },
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
