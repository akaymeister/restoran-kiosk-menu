package com.example.restoranmenu

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageInstaller
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.http.SslError
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
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
import okhttp3.Protocol
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL
import java.net.UnknownHostException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class MainActivity : ComponentActivity() {

    private lateinit var prefs: SharedPreferences
    private var activeWebView: WebView? = null
    private var userExitedKiosk = false
    private var promptedPinOnResume = false
    private var lastInteractionElapsedMs by mutableStateOf(SystemClock.elapsedRealtime())
    private var isScreenSleepingState by mutableStateOf(false)
    private var swallowWakeGestureUntilUp = false

    companion object {
        private const val PREFS_NAME = "kiosk_config_v2"
        private const val KEY_MENU_URL = "menu_url_v2"
        private const val KEY_ADMIN_PIN = "admin_pin_v2"
        private const val KEY_UPDATE_URL = "update_apk_url_v2"
        private const val KEY_SCREEN_TIMEOUT_SEC = "screen_timeout_sec_v2"
        private const val DEFAULT_URL = "https://www.simitcay.online/tablet.html"
        private const val DEFAULT_PIN = "1234"
        private const val DEFAULT_SCREEN_TIMEOUT_SEC = 30
        private const val DEFAULT_UPDATE_URL =
            "https://github.com/akaymeister/restoran-kiosk-menu/releases/latest/download/SimitCay-Menu.apk"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        lastInteractionElapsedMs = SystemClock.elapsedRealtime()
        setScreenSleeping(false)

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
                    initialScreenTimeoutSec = getSavedScreenTimeoutSec(),
                    isDeviceOwner = isDeviceOwner(),
                    lastInteractionElapsedMs = lastInteractionElapsedMs,
                    isScreenSleeping = isScreenSleepingState,
                    onIdleSleep = { setScreenSleeping(true) },
                    onWakeFromIdle = { wakeScreenFromIdle() },
                    onSaveScreenTimeoutSec = { sec -> saveScreenTimeoutSec(sec) },
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
                        setScreenSleeping(false)
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

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev != null) {
            lastInteractionElapsedMs = SystemClock.elapsedRealtime()
            if (swallowWakeGestureUntilUp) {
                if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                    swallowWakeGestureUntilUp = false
                }
                return true
            }
            if (isScreenSleepingState) {
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                    swallowWakeGestureUntilUp = true
                    setScreenSleeping(false)
                }
                return true
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        if (event != null) {
            lastInteractionElapsedMs = SystemClock.elapsedRealtime()
            if (isScreenSleepingState) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    setScreenSleeping(false)
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        wakeScreenFromIdle()
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

    private fun setScreenSleeping(sleep: Boolean) {
        isScreenSleepingState = sleep
        runOnUiThread {
            try {
                val attrs = window.attributes
                if (sleep) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    attrs.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
                } else {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    attrs.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
                window.attributes = attrs
                if (!sleep) {
                    hideSystemUI()
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun wakeScreenFromIdle() {
        lastInteractionElapsedMs = SystemClock.elapsedRealtime()
        if (isScreenSleepingState) {
            setScreenSleeping(false)
        }
    }

    private fun hideSystemUI() {
        try {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } catch (_: Exception) {
        }
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

    private fun getSavedScreenTimeoutSec(): Int =
        prefs.getInt(KEY_SCREEN_TIMEOUT_SEC, DEFAULT_SCREEN_TIMEOUT_SEC)

    private fun saveScreenTimeoutSec(sec: Int) {
        prefs.edit().putInt(KEY_SCREEN_TIMEOUT_SEC, sec.coerceAtLeast(0)).apply()
    }
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

private object KioskTlsHelper {
    @Volatile
    var simitcayVerifiedByOkHttp: Boolean = false

    // Sectigo Public Server Authentication Root R46 (simitcay.online için eski Android 7-13 uyumu)
    private const val SECTIGO_ROOT_R46_PEM =
        "-----BEGIN CERTIFICATE-----\n" +
        "MIIFijCCA3KgAwIBAgIQdY39i658BwD6qSWn4cetFDANBgkqhkiG9w0BAQwFADBf\n" +
        "MQswCQYDVQQGEwJHQjEYMBYGA1UEChMPU2VjdGlnbyBMaW1pdGVkMTYwNAYDVQQD\n" +
        "Ey1TZWN0aWdvIFB1YmxpYyBTZXJ2ZXIgQXV0aGVudGljYXRpb24gUm9vdCBSNDYw\n" +
        "HhcNMjEwMzIyMDAwMDAwWhcNNDYwMzIxMjM1OTU5WjBfMQswCQYDVQQGEwJHQjEY\n" +
        "MBYGA1UEChMPU2VjdGlnbyBMaW1pdGVkMTYwNAYDVQQDEy1TZWN0aWdvIFB1Ymxp\n" +
        "YyBTZXJ2ZXIgQXV0aGVudGljYXRpb24gUm9vdCBSNDYwggIiMA0GCSqGSIb3DQEB\n" +
        "AQUAA4ICDwAwggIKAoICAQCTvtU2UnXYASOgHEdCSe5jtrch/cSV1UgrJnwUUxDa\n" +
        "ef0rty2k1Cz66jLdScK5vQ9IPXtamFSvnl0xdE8H/FAh3aTPaE8bEmNtJZlMKpn\n" +
        "zSDBh+oF8HqcIStw+KxwfGExxqjWMrfhu6DtK2eWUAtaJhBOqbchPM8xQljeSM9x\n" +
        "fiOefVNlI8JhD1mb9nxc4Q8UBUQvX4yMPFF1bFOdLvt30yNoDN9HWOaEhUTCDsG3\n" +
        "XME6WW5HwcCSrv0WBZEMNvSE6Lzzpng3LILVCJ8zab5vuZDCQOc2TZYEhMbUjUDM\n" +
        "3IuM47fgxMMxF/mL50V0yeUKH32rMVhlATc6qu/m1dkmU8Sf4kaWD5QazYw6A3OA\n" +
        "SVYCmO2a0OYctyPDQ0RTp5A1NDvZdV3LFOxxHVp3i1fuBYYzMTYCQNFu31xR13Ng\n" +
        "ESJ/AwSiItOkcyqex8Va3e0lMWeUgFaiEAin6OJRpmkkGj80feRQXEgyDet4fsZf\n" +
        "u+Zd4KKTIRJLpfSYFplhym3kT2BFfrsU4YjRosoYwjviQYZ4ybPUHNs2iTG7sijb\n" +
        "t8uaZFURww3y8nDnAtOFr94MlI1fZEoDlSfB1D++N6xybVCi0ITz8fAr/73trdf+\n" +
        "LHaAZBav6+CuBQug4urv7qv094PPK306Xlynt8xhW6aWWrL3DkJiy4Pmi1KZHQ3x\n" +
        "tzwIDAQABo0IwQDAdBgNVHQ4EFgQUVnNYZJX5khqwEioEYnmhQBWIIUkwDgYDVR0P\n" +
        "AQH/BAQDAgGGMA8GA1UdEwEB/wQFMAMBAf8wDQYJKoZIhvcNAQEMBQADggIBAC9c\n" +
        "mTz8Bl6MlC5w6tIyMY208FHVvArzZJ8HXtXBc2hkeqK5Duj5XYUtqDdFqij0lgVQ\n" +
        "YKlJfp/imTYpE0RHap1VIDzYm/EDMrraQKFz6oOht0SmDpkBm+S8f74TlH7Kph52\n" +
        "gDY9hAaLMyZlbcp+nv4fjFg4exqDsQ+8FxG75gbMY/qB8oFM2gsQa6H61SilzwZA\n" +
        "Fv97fRheORKkU55+MkIQpiGRqRxOF3yEvJ+M0ejf5lG5Nkc/kLnHvALcWxxPDkjB\n" +
        "JYOcCj+esQMzEhonrPcibCTRAUH4WAP+JWgiH5paPHxsnnVI84HxZmduTILA7rpX\n" +
        "DhjvLpr3Etiga+kFpaHpaPi8TD8SHkXoUsCjvxInebnMMTzD9joiFgOgyY9mpFui\n" +
        "TdaBJQbpdqQACj7LzTWb4OE4y2BThihCQRxEV+ioratF4yUQvNs+ZUH7G6aXD+u5\n" +
        "dHn5HrwdVw1Hr8Mvn4dGp+smWg9WY7ViYG4A++MnESLn/pmPNPW56MORcr3Ywx65\n" +
        "LvKRRFHQV80MNNVIIb/bE/FmJUNS0nAiNs2fxBx1IK1jcmMGDw4nztJqDby1ORrp\n" +
        "0XZ60Vzk50lJLVU3aPAaOpg+VBeHVOmmJ1CJeyAvP/+/oYtKR5j/K3tJPsMpRmAY\n" +
        "QqszKbrAKbkTidOIijlBO8n9pu0f9GBj39ItVQGL\n" +
        "-----END CERTIFICATE-----"

    // Let's Encrypt ISRG Root X1 (GitHub Release APK indirme uyumu)
    private const val ISRG_ROOT_X1_PEM =
        "-----BEGIN CERTIFICATE-----\n" +
        "MIIFazCCA1OgAwIBAgIRAIIQz7DSQONZRGPgu2OCiwAwDQYJKoZIhvcNAQELBQAw\n" +
        "TzELMAkGA1UEBhMCVVMxKTAnBgNVBAoTIEludGVybmV0IFNlY3VyaXR5IFJlc2Vh\n" +
        "cmNoIEdyb3VwMRUwEwYDVQQDEwxJU1JHIFJvb3QgWDEwHhcNMTUwNjA0MTEwNDM4\n" +
        "WhcNMzUwNjA0MTEwNDM4WjBPMQswCQYDVQQGEwJVUzEpMCcGA1UEChMgSW50ZXJu\n" +
        "ZXQgU2VjdXJpdHkgUmVzZWFyY2ggR3JvdXAxFTATBgNVBAMTDElTUkcgUm9vdCBY\n" +
        "MTCCAiIwDQYJKoZIhvcNAQEBBQADggIPADCCAgoCggIBAK3oJHP0FDfzm54rVygc\n" +
        "h77ct984kIxuPOZXoHj3dcKi/vVqbvYATyjb3miGbESTtrFj/RQSa78f0uoxmyF\n" +
        "+0TM8ukj13Xnfs7j/EvEhmkvBioZxaUpmZmyPfjxwv60pIgbz5MDmgK7iS4+3mX\n" +
        "6UA5/TR5d8mUgjU+g4rk8Kb4Mu0UlXjIB0ttov0DiNewNwIRt18jA8+o+u3dpjq+\n" +
        "sWT8KOEUt+zwvo/7V3LvSye0rgTBIlDHCNAymg4VMk7BPZ7hm/ELNKjD+Jo2FR3\n" +
        "qyHB5T0Y3HsLuJvW5iB4YlcNHlsdu87kGJ55tukmi8mxdAQ4Q7e2RCOFvu396j3\n" +
        "x+UCB5iPNgiV5+I3lg02dZ77DnKxHZu8A/lJBdiB3QW0KtZB6awBdpUKD9jf1b0\n" +
        "SHzUvKBds0pjBqAlkd25HN7rOrFleaJ1/ctaJxQZBKT5ZPt0m9STJEadao0xAH0\n" +
        "ahmbWnOlFuhjuefXKnEgV4We0+UXgVCwOPjdAvBbI+e0ocS3MFEvzG6uBQE3xDk\n" +
        "3SzynTnjh8BCNAw1FtxNrQHusEwMFxIt4I7mKZ9YIqioymCzLq9gwQbooMDQaHW\n" +
        "BfEbwrbwqHyGO0aoSCqI3Haadr8faqU9GY/rOPNk3sgrDQoo//fb4hVC1CLQJ13\n" +
        "hef4Y53CIrU7m2Ys6xt0nUW7/vGT1M0NPAgMBAAGjQjBAMA4GA1UdDwEB/wQEAwIB\n" +
        "BjAPBgNVHRMBAf8EBTADAQH/MB0GA1UdDgQWBBR5tFnme7bl5AFzgAiIyBpY9umb\n" +
        "bjANBgkqhkiG9w0BAQsFAAOCAgEAVR9YqbyyqFDQDLHYGmkgJykIrGF1XIpu+ILl\n" +
        "aS/V9lZLubhzEFnTIZd+50xx+7LSYK05qAvqFyFWhfFQDlnrzuBZ6brJFe+GnY+\n" +
        "EgPbk6ZGQ3BebYhtF8GaV0nxvwuo77x/Py9auJ/GpsMiu/X1+mvoiBOv/2X/qkS\n" +
        "sisRcOj/KKNFtY2PwByVS5uCbMiogziUwthDyC3+6WVwW6LLv3xLfHTjuCvjHII\n" +
        "nNzktHCgKQ5ORAzI4JMPJ+GslWYHb4phowim57iaztXOoJwTdwJx4nLCgdNbOhdj\n" +
        "snvzqvHu7UrTkXWStAmzOVyyghqpZXjFaH3pO3JLF+l+/+sKAIuvtd7u+Nxe5AW0\n" +
        "wdeRlN8NwdCjNPElpzVmbUq4JUagEiuTDkHzsxHpFKVK7q4+63SM1N95R1NbdWhs\n" +
        "cdCb+ZAJzVcoyi3B43njTOQ5yOf+1CceWxG1bQVs5ZufpsMljq4Ui0/1lvh+wjC\n" +
        "hP4kqKOJ2qxq4RgqsahDYVvTH9w7jXbyLeiNdd8XM2w9U/t7y0Ff/9yi0GE44Za4\n" +
        "rF2LN9d11TPAmRGunUHBcnWEvgJBQl9nJEiU0Zsnvgc/ubhPgXRR4Xq37Z0j4r7\n" +
        "g1SgEEzwxA57demyPxgcYxn/eR44/KJ4EBs+lVDR3veyJm+kXQ99b21/+jh5Xos\n" +
        "1AnX5iItreGCc=\n" +
        "-----END CERTIFICATE-----"

    val trustManager: X509TrustManager? by lazy { buildCombinedTrustManager() }
    val sslSocketFactory: SSLSocketFactory? by lazy {
        val tm = trustManager ?: return@lazy null
        try {
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf<TrustManager>(tm), SecureRandom())
            ctx.socketFactory
        } catch (_: Exception) {
            null
        }
    }

    private fun buildCombinedTrustManager(): X509TrustManager? {
        return try {
            val sysTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            sysTmf.init(null as KeyStore?)
            val sysTm = sysTmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()

            val ks = KeyStore.getInstance(KeyStore.getDefaultType())
            ks.load(null, null)
            val cf = CertificateFactory.getInstance("X.509")
            listOf(SECTIGO_ROOT_R46_PEM, ISRG_ROOT_X1_PEM).forEachIndexed { idx, pem ->
                val cert = cf.generateCertificate(ByteArrayInputStream(pem.toByteArray(Charsets.UTF_8))) as X509Certificate
                ks.setCertificateEntry("kiosk_$idx", cert)
            }
            val extraTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            extraTmf.init(ks)
            val extraTm = extraTmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()

            object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                    sysTm?.checkClientTrusted(chain, authType)
                }

                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    if (sysTm != null) {
                        try {
                            sysTm.checkServerTrusted(chain, authType)
                            return
                        } catch (_: CertificateException) {
                        }
                    }
                    if (extraTm != null) {
                        extraTm.checkServerTrusted(chain, authType)
                        return
                    }
                    throw CertificateException("Güvenilir kök sertifika bulunamadı")
                }

                override fun getAcceptedIssuers(): Array<X509Certificate> {
                    val sys = sysTm?.acceptedIssuers ?: emptyArray()
                    val extra = extraTm?.acceptedIssuers ?: emptyArray()
                    return sys + extra
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}

private fun detectChromeMajor(context: Context): Int {
    return try {
        val ua = WebSettings.getDefaultUserAgent(context)
        val match = Regex("Chrome/(\\d+)").find(ua)
        match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    } catch (_: Exception) {
        0
    }
}

private fun resolveViaGoogleDoh(hostname: String): List<InetAddress> {
    var conn: HttpURLConnection? = null
    return try {
        val url = URL("https://8.8.8.8/resolve?name=$hostname&type=A")
        conn = (url.openConnection() as HttpURLConnection).apply {
            if (this is HttpsURLConnection) {
                KioskTlsHelper.sslSocketFactory?.let { this.sslSocketFactory = it }
            }
            connectTimeout = 5000
            readTimeout = 5000
            setRequestProperty("Accept", "application/dns-json")
        }
        if (conn.responseCode !in 200..299) return emptyList()
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val answers = JSONObject(body).optJSONArray("Answer") ?: return emptyList()
        val result = mutableListOf<InetAddress>()
        for (i in 0 until answers.length()) {
            val item = answers.optJSONObject(i) ?: continue
            if (item.optInt("type") == 1) {
                val ip = item.optString("data").trim()
                if (ip.isNotEmpty()) {
                    result.add(InetAddress.getByName(ip))
                }
            }
        }
        result
    } catch (_: Exception) {
        emptyList()
    } finally {
        conn?.disconnect()
    }
}

private fun createResilientOkHttpClient(
    connectSec: Long = 12,
    readSec: Long = 20
): OkHttpClient {
    val builder = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(connectSec, TimeUnit.SECONDS)
        .readTimeout(readSec, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                return try {
                    Dns.SYSTEM.lookup(hostname).sortedBy { it !is Inet4Address }
                } catch (e: UnknownHostException) {
                    val doh = resolveViaGoogleDoh(hostname)
                    if (doh.isNotEmpty()) doh else throw e
                }
            }
        })

    val tm = KioskTlsHelper.trustManager
    val ssf = KioskTlsHelper.sslSocketFactory
    if (tm != null && ssf != null) {
        builder.sslSocketFactory(ssf, tm)
    }
    return builder.build()
}

/**
 * Sunucudan "no-store" gelse bile tablet.html, JS, CSS, menu-data.php ve görselleri
 * tabletin yerel hafızasına kaydeder. Eski Android 7-10 WebView uyumluluğu sağlar ve
 * internet kesildiğinde otomatik önbellekten sunar.
 */
private class OfflineMenuCache(
    context: Context,
    private val chromeMajor: Int,
    private val onConnectionStatusChanged: (Boolean) -> Unit,
    private val onCoreError: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val cacheDir = File(context.filesDir, "offline_menu_cache").apply { mkdirs() }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val httpClient = createResilientOkHttpClient(connectSec = 12, readSec = 20)
    private val fontsClient = createResilientOkHttpClient(connectSec = 4, readSec = 5)
    @Volatile var lastMainFrameLoaded: Boolean = false
        private set

    fun cachedFileCount(): Int {
        return cacheDir.listFiles { _, name -> name.endsWith(".bin") }?.size ?: 0
    }

    fun clearCache() {
        try {
            cacheDir.listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {
        }
    }

    fun hasCompleteOfflineBundle(): Boolean {
        val required = listOf(
            "https://www.simitcay.online/tablet.html",
            "https://www.simitcay.online/menu-data.php",
            "https://www.simitcay.online/menu-ui.js",
            "https://www.simitcay.online/tablet.js",
            "https://www.simitcay.online/tablet.css"
        )
        return required.all { File(cacheDir, hashKey(it) + ".bin").exists() }
    }

    fun hasCachedMenu(url: String): Boolean {
        return hasCompleteOfflineBundle()
    }

    fun loadCachedHtml(url: String): String? {
        if (!hasCompleteOfflineBundle()) return null
        val uri = Uri.parse(url)
        val pathKey = "https://${uri.host ?: "www.simitcay.online"}${uri.path ?: "/tablet.html"}"
        val candidates = listOf(url, pathKey, "https://www.simitcay.online/tablet.html")
        for (candidate in candidates) {
            val f = File(cacheDir, hashKey(candidate) + ".bin")
            if (f.exists()) {
                lastMainFrameLoaded = true
                return injectPolyfill(f.readBytes()).toString(Charsets.UTF_8)
            }
        }
        return null
    }

    fun shouldIntercept(request: WebResourceRequest): Boolean {
        if (request.method != "GET") return false
        val uri = request.url ?: return false
        val host = uri.host?.lowercase() ?: return false
        if (host == "fonts.googleapis.com" || host == "fonts.gstatic.com") return true
        if (!host.endsWith("simitcay.online")) return false
        val path = uri.path?.lowercase() ?: ""
        if (path.endsWith(".mp4") || path.endsWith(".webm")) return false
        return true
    }

    fun fetchOrServeCached(request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url ?: return null
        val host = uri.host?.lowercase() ?: ""
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
        val isFontHost = host == "fonts.googleapis.com" || host == "fonts.gstatic.com"
        val isCoreMenuRequest = !isFontHost && (
            request.isForMainFrame ||
            path.endsWith("tablet.html") ||
            path.contains("menu-data.php") ||
            path.contains("api.php")
        )
        val cachedBefore = load(exactKey, path) ?: load(fallbackKey, path)
        val onlineNow = isNetworkAvailable(appContext)
        var lastErrMsg: String? = null

        // Google Fonts hızlı önbellekten veya ağdan çekilir; ulaşılamazsa sayfa çizimini kilitlememesi için boş CSS döner.
        if (isFontHost) {
            if (cachedBefore != null) return cachedBefore
            if (onlineNow) {
                try {
                    val fontReq = Request.Builder().url(fullUrl).build()
                    fontsClient.newCall(fontReq).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val bytes = resp.body?.bytes()
                            if (bytes != null) {
                                val mime = resp.header("Content-Type")?.substringBefore(";")?.trim()
                                    ?.takeIf { it.isNotBlank() } ?: guessMimeType(uri)
                                save(exactKey, bytes, mime)
                                return buildResponse(bytes, mime, path)
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
            val emptyMime = if (host == "fonts.googleapis.com") "text/css" else "application/octet-stream"
            return WebResourceResponse(emptyMime, "UTF-8", 200, "OK", mapOf("Access-Control-Allow-Origin" to "*"), ByteArrayInputStream(ByteArray(0)))
        }

        // İnternet görünüyorsa VEYA henüz önbellekte hiç kayıt yoksa ağdan dene (eski tabletlerde ConnectivityManager bazen false döner)
        if (onlineNow || cachedBefore == null) {
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
                            if (host.endsWith("simitcay.online")) {
                                KioskTlsHelper.simitcayVerifiedByOkHttp = true
                            }
                            if (request.isForMainFrame) {
                                lastMainFrameLoaded = true
                            }
                            val mime = resp.header("Content-Type")?.substringBefore(";")?.trim()
                                ?.takeIf { it.isNotBlank() } ?: guessMimeType(uri)
                            save(exactKey, bytes, mime)
                            if (isCoreMenuRequest || path.endsWith(".js") || path.endsWith(".css") || path.contains("site-logo.php")) {
                                save(fallbackKey, bytes, mime)
                            }
                            if (isCoreMenuRequest) {
                                mainHandler.post { onConnectionStatusChanged(true) }
                            }
                            return buildResponse(bytes, mime, path)
                        }
                    } else {
                        lastErrMsg = "HTTP ${resp.code}"
                        if (isCoreMenuRequest) {
                            mainHandler.post { onConnectionStatusChanged(false) }
                        }
                    }
                }
            } catch (e: Exception) {
                lastErrMsg = "${e.javaClass.simpleName}: ${e.message ?: "Bağlantı hatası"}"
                if (isCoreMenuRequest) {
                    mainHandler.post { onConnectionStatusChanged(false) }
                }
            }
        } else if (isCoreMenuRequest) {
            mainHandler.post { onConnectionStatusChanged(false) }
        }

        if (cachedBefore != null && (!request.isForMainFrame || hasCompleteOfflineBundle())) {
            if (request.isForMainFrame) {
                lastMainFrameLoaded = true
            }
            return cachedBefore
        }

        if (request.isForMainFrame) {
            lastMainFrameLoaded = false
            val err = lastErrMsg ?: if (!onlineNow) "İnternet bağlantısı yok (Çevrimdışı önbellek henüz tam değil)" else "Sunucuya erişilemedi"
            mainHandler.post { onCoreError(err) }
            val placeholder = "<!doctype html><html><body style='margin:0;background:#092746'></body></html>".toByteArray(Charsets.UTF_8)
            return WebResourceResponse("text/html", "UTF-8", 200, "OK", emptyMap(), ByteArrayInputStream(placeholder))
        }
        return null
    }

    private fun save(key: String, bytes: ByteArray, mime: String) {
        try {
            File(cacheDir, "$key.bin").writeBytes(bytes)
            File(cacheDir, "$key.mime").writeText(mime)
        } catch (_: Exception) {
        }
    }

    private fun load(key: String, path: String = ""): WebResourceResponse? {
        val dataFile = File(cacheDir, "$key.bin")
        val mimeFile = File(cacheDir, "$key.mime")
        if (!dataFile.exists()) return null
        val bytes = dataFile.readBytes()
        val mime = if (mimeFile.exists()) mimeFile.readText().trim() else "application/octet-stream"
        return buildResponse(bytes, mime, path)
    }

    private fun injectPolyfill(bytes: ByteArray): ByteArray {
        val polyfill = "<script>var __oc,__nc;(function(){" +
            "window.__kioskJsErr='';window.addEventListener('error',function(e){if(!window.__kioskJsErr)window.__kioskJsErr=(e.message||'JS Hatasi')+' ('+(e.filename||'').split('/').pop()+':'+(e.lineno||0)+')';});" +
            "if(!Object.hasOwn){Object.hasOwn=function(o,p){return o!=null&&Object.prototype.hasOwnProperty.call(o,p);};}" +
            "if(!Object.fromEntries){Object.fromEntries=function(it){var o={};for(var x of it){o[x[0]]=x[1];}return o;};}" +
            "if(!String.prototype.replaceAll){String.prototype.replaceAll=function(s,r){if(s instanceof RegExp)return this.replace(s,r);var v=typeof r==='function'?r(String(s)):String(r);return this.split(String(s)).join(v);};}" +
            "if(!Array.prototype.at){Array.prototype.at=function(n){n=Math.trunc(n)||0;if(n<0)n+=this.length;if(n<0||n>=this.length)return undefined;return this[n];};}" +
            "if(!Array.prototype.flat){Array.prototype.flat=function(d){var dp=d===undefined?1:Number(d);var fl=function(a,l){var r=[];for(var i=0;i<a.length;i++){if(Array.isArray(a[i])&&l>0){r=r.concat(fl(a[i],l-1));}else{r.push(a[i]);}}return r;};return fl(this,dp);};}" +
            "if(!Array.prototype.flatMap){Array.prototype.flatMap=function(fn,th){return this.map(fn,th).flat(1);};}" +
            "var rc=function(){while(this.firstChild)this.removeChild(this.firstChild);for(var i=0;i<arguments.length;i++){var n=arguments[i];this.appendChild(typeof n==='string'?document.createTextNode(n):n);}};" +
            "if(typeof Element!=='undefined'&&!Element.prototype.replaceChildren){Element.prototype.replaceChildren=rc;}" +
            "if(typeof DocumentFragment!=='undefined'&&!DocumentFragment.prototype.replaceChildren){DocumentFragment.prototype.replaceChildren=rc;}" +
            "if(typeof HTMLDialogElement!=='undefined'&&!HTMLDialogElement.prototype.showModal){HTMLDialogElement.prototype.showModal=function(){this.setAttribute('open','');};HTMLDialogElement.prototype.close=function(){this.removeAttribute('open');};}" +
            "try{document.querySelectorAll(':has(*)');}catch(_e){" +
            "var wrapQsa=function(orig){return function(sel){try{return orig.call(this,sel);}catch(err){if(typeof sel==='string'&&sel.indexOf(':has(')!==-1){var parts=sel.split(','),out=[],seen=[];for(var i=0;i<parts.length;i++){var p=parts[i].trim();if(p==='header>a:has(img[data-brand-logo])'){var imgs=this.querySelectorAll('header > a > img[data-brand-logo]');for(var k=0;k<imgs.length;k++){var pa=imgs[k].parentNode;if(pa&&seen.indexOf(pa)===-1){seen.push(pa);out.push(pa);}}}else if(p.indexOf(':has(')===-1){try{var list=orig.call(this,p);for(var j=0;j<list.length;j++){if(seen.indexOf(list[j])===-1){seen.push(list[j]);out.push(list[j]);}}}catch(_x){}}}return out;}return [];}};};" +
            "if(typeof Document!=='undefined')Document.prototype.querySelectorAll=wrapQsa(Document.prototype.querySelectorAll);" +
            "if(typeof Element!=='undefined')Element.prototype.querySelectorAll=wrapQsa(Element.prototype.querySelectorAll);" +
            "}" +
            "})();</script>"
        val html = bytes.toString(Charsets.UTF_8)
        val headIdx = html.indexOf("<head>")
        return if (!html.contains("var __oc,__nc;") && headIdx >= 0) {
            (html.substring(0, headIdx + 6) + polyfill + html.substring(headIdx + 6)).toByteArray(Charsets.UTF_8)
        } else {
            bytes
        }
    }

    private fun downlevelLegacyJs(bytes: ByteArray): ByteArray {
        var out = bytes.toString(Charsets.UTF_8)
        out = out.replace(Regex("\\bcatch\\s*\\{"), "catch(__kioskErr){")
        out = out.replace(
            Regex("([A-Za-z_\\$][\\w\\$.]*)\\s*\\?\\?\\s*('[^']*'|\"[^\"]*\"|[A-Za-z0-9_\\$.]+)"),
            "((__nc=\$1)!=null?__nc:\$2)"
        )
        out = out.replace(
            Regex("([A-Za-z_\\$][\\w\\$.]*(?:\\([^()]*\\)|\\[[^\\]]+\\])*)\\?\\.\\[([^\\]]+)\\]"),
            "((__oc=\$1)!=null?__oc[\$2]:undefined)"
        )
        out = out.replace(
            Regex("([A-Za-z_\\$][\\w\\$.]*(?:\\([^()]*\\)|\\[[^\\]]+\\])*)\\?\\.([A-Za-z_\\$][\\w\\$]*)\\(([^()]*)\\)"),
            "((__oc=\$1)!=null?__oc.\$2(\$3):undefined)"
        )
        out = out.replace(
            Regex("([A-Za-z_\\$][\\w\\$.]*(?:\\([^()]*\\)|\\[[^\\]]+\\])*)\\?\\.([A-Za-z_\\$][\\w\\$]*)"),
            "((__oc=\$1)!=null?__oc.\$2:undefined)"
        )
        return ("var __oc,__nc;\n" + out).toByteArray(Charsets.UTF_8)
    }

    private fun downlevelLegacyCss(bytes: ByteArray): ByteArray {
        val css = bytes.toString(Charsets.UTF_8)
        return css.replace(Regex("([0-9.]+)dvh"), "\$1vh").toByteArray(Charsets.UTF_8)
    }

    private fun buildResponse(bytes: ByteArray, mime: String, path: String): WebResourceResponse {
        val isJs = mime.contains("javascript") || path.endsWith(".js") || path.contains("menu-data.php")
        val isCss = mime.startsWith("text/css") || path.endsWith(".css")
        val finalBytes = when {
            mime.startsWith("text/html") -> injectPolyfill(bytes)
            isJs && (chromeMajor == 0 || chromeMajor < 86) -> downlevelLegacyJs(bytes)
            isCss && (chromeMajor == 0 || chromeMajor < 109) -> downlevelLegacyCss(bytes)
            else -> bytes
        }
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
            path.endsWith(".ico") -> "image/x-icon"
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
    initialScreenTimeoutSec: Int,
    isDeviceOwner: Boolean,
    lastInteractionElapsedMs: Long,
    isScreenSleeping: Boolean,
    onIdleSleep: () -> Unit,
    onWakeFromIdle: () -> Unit,
    onSaveScreenTimeoutSec: (Int) -> Unit,
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
    var lastJsError by remember { mutableStateOf<String?>(null) }
    var showAdminDialog by remember { mutableStateOf(false) }
    var targetUrl by remember { mutableStateOf(currentUrl) }
    var updateApkUrl by remember { mutableStateOf(currentUpdateUrl) }
    var screenTimeoutSec by remember { mutableStateOf(initialScreenTimeoutSec) }
    var lastLoadedUrl by remember { mutableStateOf(currentUrl) }
    var cachedCount by remember { mutableStateOf(0) }
    var batteryPercent by remember { mutableStateOf(-1) }
    var isCharging by remember { mutableStateOf(false) }

    val chromeMajor = remember { detectChromeMajor(context) }

    val appVersionLabel = remember {
        try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode.toLong()
            }
            "v${pInfo.versionName ?: "2.0"} (Kod: $code) • Android ${Build.VERSION.RELEASE} • WebView ${if (chromeMajor > 0) "Chrome/$chromeMajor" else "?"}"
        } catch (_: Exception) {
            "v2.0 • Android ${Build.VERSION.RELEASE}"
        }
    }

    val offlineCache = remember {
        OfflineMenuCache(
            context = context,
            chromeMajor = chromeMajor,
            onConnectionStatusChanged = { onlineNow ->
                isOnline = onlineNow
                if (!onlineNow) {
                    wasDisconnected = true
                }
            },
            onCoreError = { err ->
                lastErrorReason = err
                showInitialError = true
            }
        )
    }

    LaunchedEffect(Unit) {
        cachedCount = offlineCache.cachedFileCount()
    }

    // Batarya seviyesi ve şarj durumunu canlı dinle
    DisposableEffect(Unit) {
        try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val cap = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            if (cap in 0..100 && batteryPercent < 0) {
                batteryPercent = cap
            }
        } catch (_: Exception) {
        }

        val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent == null) return
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                if (level >= 0 && scale > 0) {
                    batteryPercent = ((level * 100f) / scale).toInt().coerceIn(0, 100)
                }
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL ||
                    plugged != 0
            }
        }
        try {
            val sticky = ContextCompat.registerReceiver(
                context,
                batteryReceiver,
                batteryFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            if (sticky != null) {
                batteryReceiver.onReceive(context, sticky)
            }
        } catch (_: Exception) {
        }
        onDispose {
            try {
                context.unregisterReceiver(batteryReceiver)
            } catch (_: Exception) {
            }
        }
    }

    // 30 saniye hareketsizlikte ekranı uyku moduna al
    LaunchedEffect(screenTimeoutSec, showAdminDialog) {
        if (screenTimeoutSec <= 0) {
            onWakeFromIdle()
            return@LaunchedEffect
        }
        while (true) {
            if (!showAdminDialog && !isScreenSleeping) {
                val elapsedMs = SystemClock.elapsedRealtime() - lastInteractionElapsedMs
                val timeoutMs = screenTimeoutSec * 1000L
                if (elapsedMs >= timeoutMs) {
                    onIdleSleep()
                }
            }
            delay(1000)
        }
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
            showInitialError = false
            webViewRef?.loadUrl(targetUrl)
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

                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                            if (consoleMessage?.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                                val src = consoleMessage.sourceId()?.substringAfterLast('/') ?: ""
                                lastJsError = "${consoleMessage.message()} ($src:${consoleMessage.lineNumber()})"
                            }
                            return super.onConsoleMessage(consoleMessage)
                        }
                    }
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

                        @SuppressLint("WebViewClientOnReceivedSslError")
                        override fun onReceivedSslError(
                            view: WebView?,
                            handler: SslErrorHandler?,
                            error: SslError?
                        ) {
                            val errHost = try { Uri.parse(error?.url ?: "").host?.lowercase() ?: "" } catch (_: Exception) { "" }
                            if (errHost.endsWith("simitcay.online") &&
                                error?.primaryError == SslError.SSL_UNTRUSTED &&
                                KioskTlsHelper.simitcayVerifiedByOkHttp
                            ) {
                                handler?.proceed()
                            } else {
                                handler?.cancel()
                            }
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
                                    lastErrorReason = lastErrorReason ?: error?.description?.toString() ?: "ERR_TIMED_OUT"
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
                                if (offlineCache.lastMainFrameLoaded) {
                                    hasLoadedOnce = true
                                    showInitialError = false
                                    lastErrorReason = null
                                    view?.postDelayed({
                                        view.evaluateJavascript(
                                            "(function(){var g=document.getElementById('gate'),b=document.getElementById('board'),i=document.getElementById('intro');var ok=(g&&g.children.length>0)||(b&&!b.hidden)||(i&&!i.hidden);return (ok?'OK':'EMPTY')+'|'+(window.__kioskJsErr||'');})()"
                                        ) { raw ->
                                            val clean = raw?.trim('"') ?: ""
                                            if (clean.startsWith("EMPTY")) {
                                                val jsErr = clean.substringAfter('|', "").ifBlank { lastJsError ?: "Menü scripti yüklenemedi" }
                                                lastErrorReason = "Sayfa boş kaldı ($jsErr)"
                                                showInitialError = true
                                            }
                                        }
                                    }, 3500)
                                }
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
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = appVersionLabel,
                        fontSize = 10.sp,
                        color = Color(0xFF94A3B8),
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

        if (!isOnline && !showInitialError && !isScreenSleeping) {
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

        if (!isScreenSleeping && batteryPercent >= 0) {
            KioskBatteryIndicator(
                batteryPercent = batteryPercent,
                isCharging = isCharging,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
            )
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

        if (isScreenSleeping) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.BottomCenter
            ) {
                Text(
                    text = "Ekran uyku modunda • Açmak için dokunun",
                    color = Color(0xFF334155),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 24.dp)
                )
            }
        }

        if (showAdminDialog) {
            HardenedAdminDialog(
                currentPin = currentPin,
                currentUrl = targetUrl,
                currentUpdateUrl = updateApkUrl,
                currentScreenTimeoutSec = screenTimeoutSec,
                batteryPercent = batteryPercent,
                isCharging = isCharging,
                cachedCount = cachedCount,
                appVersion = appVersionLabel,
                isDeviceOwner = isDeviceOwner,
                onDismiss = {
                    onWakeFromIdle()
                    showAdminDialog = false
                },
                onSaveUrl = { newUrl ->
                    val normalized = onSaveUrl(newUrl)
                    targetUrl = normalized
                },
                onSaveUpdateUrl = { newUpdateUrl ->
                    val clean = onSaveUpdateUrl(newUpdateUrl)
                    updateApkUrl = clean
                    clean
                },
                onSaveScreenTimeoutSec = { sec ->
                    screenTimeoutSec = sec
                    onSaveScreenTimeoutSec(sec)
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

@Composable
private fun KioskBatteryIndicator(
    batteryPercent: Int,
    isCharging: Boolean,
    modifier: Modifier = Modifier
) {
    val accentColor = when {
        isCharging -> Color(0xFF22C55E)
        batteryPercent <= 15 -> Color(0xFFEF4444)
        batteryPercent <= 30 -> Color(0xFFF59E0B)
        else -> Color(0xFF4ADE80)
    }
    val borderColor = when {
        batteryPercent <= 15 && !isCharging -> Color(0x99EF4444)
        isCharging -> Color(0x8822C55E)
        else -> Color(0x33FFFFFF)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .background(Color(0xCC091B30), RoundedCornerShape(999.dp))
            .border(1.dp, borderColor, RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Canvas(modifier = Modifier.size(width = 24.dp, height = 12.dp)) {
            val strokePx = 1.4.dp.toPx()
            val nubWidth = 2.5.dp.toPx()
            val bodyWidth = size.width - nubWidth - 1.dp.toPx()
            val bodyHeight = size.height

            drawRoundRect(
                color = Color(0xFFE2E8F0),
                topLeft = Offset(0f, 0f),
                size = Size(bodyWidth, bodyHeight),
                cornerRadius = CornerRadius(2.5.dp.toPx(), 2.5.dp.toPx()),
                style = Stroke(width = strokePx)
            )

            drawRoundRect(
                color = Color(0xFFE2E8F0),
                topLeft = Offset(bodyWidth + 1.dp.toPx(), bodyHeight * 0.28f),
                size = Size(nubWidth, bodyHeight * 0.44f),
                cornerRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx())
            )

            val pad = strokePx + 1.dp.toPx()
            val maxFillW = (bodyWidth - pad * 2).coerceAtLeast(0f)
            val fillRatio = (batteryPercent.coerceIn(0, 100) / 100f)
            val fillW = (maxFillW * fillRatio).coerceAtLeast(if (batteryPercent > 0) 2f else 0f)
            if (fillW > 0f) {
                drawRoundRect(
                    color = accentColor,
                    topLeft = Offset(pad, pad),
                    size = Size(fillW, (bodyHeight - pad * 2).coerceAtLeast(0f)),
                    cornerRadius = CornerRadius(1.2.dp.toPx(), 1.2.dp.toPx())
                )
            }

            if (isCharging) {
                val cx = bodyWidth * 0.5f
                val bolt = Path().apply {
                    moveTo(cx + 1.5.dp.toPx(), 1.5.dp.toPx())
                    lineTo(cx - 2.2.dp.toPx(), bodyHeight * 0.55f)
                    lineTo(cx + 0.3.dp.toPx(), bodyHeight * 0.55f)
                    lineTo(cx - 1.5.dp.toPx(), bodyHeight - 1.5.dp.toPx())
                    lineTo(cx + 2.4.dp.toPx(), bodyHeight * 0.45f)
                    lineTo(cx - 0.2.dp.toPx(), bodyHeight * 0.45f)
                    close()
                }
                drawPath(path = bolt, color = Color.White)
            }
        }

        Text(
            text = if (isCharging) "%$batteryPercent ⚡" else "%$batteryPercent",
            color = if (batteryPercent <= 15 && !isCharging) Color(0xFFFCA5A5) else Color(0xFFF8FAFC),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

private suspend fun downloadApkFile(
    context: Context,
    apkUrl: String,
    onProgress: (Int) -> Unit
): Result<File> = withContext(Dispatchers.IO) {
    try {
        val client = createResilientOkHttpClient(connectSec = 15, readSec = 60)
        val req = Request.Builder()
            .url(apkUrl.trim())
            .header("User-Agent", "SimitCayKioskUpdater/2.0")
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val hint = if (resp.code == 404) {
                    "APK bulunamadı (404). GitHub Release henüz hazır olmayabilir veya depo Gizli (Private) olabilir."
                } else {
                    "Sunucu hatası: HTTP ${resp.code}"
                }
                return@withContext Result.failure(Exception(hint))
            }

            val body = resp.body ?: return@withContext Result.failure(Exception("APK verisi boş"))
            val totalBytes = body.contentLength()
            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val outFile = File(updatesDir, "SimitCay-Menu-update.apk")

            body.byteStream().use { input ->
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
        }
    } catch (e: Exception) {
        Result.failure(e)
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
    currentScreenTimeoutSec: Int,
    batteryPercent: Int,
    isCharging: Boolean,
    cachedCount: Int,
    appVersion: String,
    isDeviceOwner: Boolean,
    onDismiss: () -> Unit,
    onSaveUrl: (String) -> Unit,
    onSaveUpdateUrl: (String) -> String,
    onSaveScreenTimeoutSec: (Int) -> Unit,
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
    var timeoutSecInput by remember { mutableStateOf(currentScreenTimeoutSec) }
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
                    if (batteryPercent >= 0) {
                        Text(
                            text = if (isCharging) "Pil: %$batteryPercent (Şarj Oluyor ⚡)" else "Pil: %$batteryPercent",
                            fontSize = 12.sp,
                            color = if (batteryPercent <= 15 && !isCharging) Color(0xFFDC2626) else Color(0xFF0F766E),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
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

                    Text(
                        text = "Hareketsizlik Ekran Uyku Süresi:",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF0F172A)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            15 to "15 sn",
                            30 to "30 sn",
                            60 to "60 sn",
                            0 to "Açık"
                        ).forEach { (sec, label) ->
                            val selected = timeoutSecInput == sec
                            OutlinedButton(
                                onClick = {
                                    timeoutSecInput = sec
                                    onSaveScreenTimeoutSec(sec)
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                colors = if (selected) {
                                    ButtonDefaults.outlinedButtonColors(
                                        containerColor = Color(0xFF092746),
                                        contentColor = Color.White
                                    )
                                } else {
                                    ButtonDefaults.outlinedButtonColors()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(label, fontSize = 11.sp, maxLines = 1)
                            }
                        }
                    }

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
                                onSaveScreenTimeoutSec(timeoutSecInput)
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