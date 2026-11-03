package com.example.restoranmenu

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.restoranmenu.ui.theme.RestoranMenuTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setupKioskMode()
        
        hideSystemUI()

        setContent {
            RestoranMenuTheme {
                MainScreen(
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
        // Ensure lock task mode is started when resumed
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
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
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
}

@Composable
fun MainScreen(onExitKioskMode: () -> Unit) {
    var showDialog by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        // WebView taking the full screen
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    settings.javaScriptEnabled = true
                    webViewClient = WebViewClient()
                    loadUrl("https://example.com") // URL to load
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Hidden admin button in the top right corner
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(100.dp)
                .background(Color.Transparent)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = {
                            showDialog = true
                        }
                    )
                }
        )

        if (showDialog) {
            PasswordDialog(
                onDismiss = { showDialog = false },
                onSuccess = {
                    showDialog = false
                    onExitKioskMode()
                }
            )
        }
    }
}

@Composable
fun PasswordDialog(onDismiss: () -> Unit, onSuccess: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Admin Girişi") },
        text = {
            OutlinedTextField(
                value = password,
                onValueChange = { 
                    password = it
                    isError = false
                },
                label = { Text("Şifre") },
                visualTransformation = PasswordVisualTransformation(),
                isError = isError,
                supportingText = { if (isError) Text("Hatalı şifre!") }
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    if (password == "1234") {
                        onSuccess()
                    } else {
                        isError = true
                    }
                }
            ) {
                Text("Giriş")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("İptal")
            }
        }
    )
}
