package com.mabsapp.whatsappweb

import android.Manifest
import android.annotation.SuppressLint
import android.app.ProgressDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnCheckUpdate: FloatingActionButton
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null

    @Volatile
    var isChatOpen: Boolean = false

    private val updateManifestUrl = "https://raw.githubusercontent.com/MisaelOrtizDR/MABSAPP/main/version.json"

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (fileUploadCallback == null) return@registerForActivityResult
        val results = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        fileUploadCallback?.onReceiveValue(results)
        fileUploadCallback = null
    }

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    class WebAppInterface(private val activity: MainActivity) {
        @JavascriptInterface
        fun onChatStateChanged(inChat: Boolean) {
            activity.runOnUiThread {
                activity.isChatOpen = inChat
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        btnCheckUpdate = findViewById(R.id.btnCheckUpdate)

        checkAndRequestAppPermissions()
        configureWebView()

        btnCheckUpdate.setOnClickListener {
            showActionMenu()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isChatOpen) {
                    webView.evaluateJavascript("window.mabsGoBack();", null)
                } else if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        webView.loadUrl("https://web.whatsapp.com")
        checkForUpdates(manual = false)
    }

    private fun showActionMenu() {
        val options = arrayOf("Buscar actualización", "Recargar página", "Limpiar datos y reiniciar")
        AlertDialog.Builder(this)
            .setTitle("Opciones de MABSAPP")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> checkForUpdates(manual = true)
                    1 -> webView.reload()
                    2 -> clearDataAndReload()
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun clearDataAndReload() {
        webView.clearCache(true)
        webView.clearFormData()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        webView.loadUrl("https://web.whatsapp.com")
        Toast.makeText(this, "Datos limpiados. Recargando WhatsApp...", Toast.LENGTH_SHORT).show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val settings = webView.settings

        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(false)

        // Fresh Modern Chrome Desktop User Agent
        val desktopUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
        settings.userAgentString = desktopUserAgent

        // Viewport and native sizing
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.textZoom = 100

        // Storage & Cache
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        // Cookie management
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        // JavaScript Interface
        webView.addJavascriptInterface(WebAppInterface(this), "AndroidBridge")

        // WebViewClient
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                val host = request.url?.host?.lowercase() ?: ""

                // Keep all WhatsApp, Meta, Facebook and CDN assets inside WebView
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    if (host.endsWith("whatsapp.com") || host.endsWith("whatsapp.net") ||
                        host.endsWith("facebook.com") || host.endsWith("fbcdn.net") ||
                        host.endsWith("meta.com") || host.endsWith("cdninstagram.com")) {
                        return false
                    }
                }

                // External links open in device browser
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    true
                } catch (e: Exception) {
                    false
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                injectStableLayoutUI()
            }
        }

        // WebChromeClient
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress < 100) {
                    progressBar.visibility = View.VISIBLE
                } else {
                    progressBar.visibility = View.GONE
                }
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                runOnUiThread { request?.grant(request.resources) }
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback

                val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }

                return try {
                    fileChooserLauncher.launch(intent)
                    true
                } catch (e: ActivityNotFoundException) {
                    fileUploadCallback = null
                    Toast.makeText(this@MainActivity, "No se encontró explorador de archivos", Toast.LENGTH_SHORT).show()
                    false
                }
            }
        }
    }

    /**
     * Non-destructive Single-Column UI Injection.
     * Uses CSS :has(header) selector supported natively by Chromium.
     * ZERO timers, ZERO loops, ZERO display:none on React nodes.
     * Leaves WhatsApp's encryption loading screen completely untouched!
     */
    private fun injectStableLayoutUI() {
        val js = """
            (function() {
                if (window.__mabsInit) return;
                window.__mabsInit = true;

                // 1. Mobile Viewport
                var meta = document.querySelector('meta[name="viewport"]');
                if (!meta) {
                    meta = document.createElement('meta');
                    meta.name = 'viewport';
                    document.head.appendChild(meta);
                }
                meta.content = 'width=device-width, initial-scale=1.0, maximum-scale=2.5, user-scalable=yes';

                // 2. Pure CSS Layout using native :has()
                var style = document.getElementById('mabs-clean-style');
                if (!style) {
                    style = document.createElement('style');
                    style.id = 'mabs-clean-style';
                    document.head.appendChild(style);
                }

                style.textContent = `
                    html, body, #app {
                        width: 100vw !important;
                        height: 100% !important;
                        overflow: hidden !important;
                        margin: 0 !important;
                        padding: 0 !important;
                    }

                    /* Fit Chat List across full screen minus navigation rail */
                    #side {
                        width: calc(100vw - 52px) !important;
                        max-width: calc(100vw - 52px) !important;
                        min-width: calc(100vw - 52px) !important;
                        flex: 1 1 auto !important;
                    }

                    /* Left navigation rail */
                    div[role="navigation"],
                    div._aigs {
                        width: 52px !important;
                        min-width: 52px !important;
                        max-width: 52px !important;
                    }

                    /* When an active conversation exists, #main contains a header */
                    #main:has(header) {
                        position: fixed !important;
                        top: 0 !important;
                        left: 0 !important;
                        width: 100vw !important;
                        height: 100% !important;
                        max-width: 100vw !important;
                        min-width: 100vw !important;
                        z-index: 9999 !important;
                        background: #111b21 !important;
                    }

                    /* Back button in conversation header */
                    #mabs-back-btn {
                        display: inline-flex !important;
                        align-items: center !important;
                        justify-content: center !important;
                        width: 40px !important;
                        height: 40px !important;
                        margin-right: 6px !important;
                        cursor: pointer !important;
                        color: #00a884 !important;
                        font-size: 26px !important;
                        font-weight: bold !important;
                        background: transparent !important;
                        border: none !important;
                        outline: none !important;
                        z-index: 100000 !important;
                        flex-shrink: 0 !important;
                    }
                    #mabs-back-btn:active {
                        background: rgba(0, 168, 132, 0.2) !important;
                        border-radius: 50% !important;
                    }
                `;

                // 3. Back function
                window.mabsGoBack = function() {
                    var esc = new KeyboardEvent('keydown', {
                        bubbles: true,
                        cancelable: true,
                        key: 'Escape',
                        code: 'Escape',
                        keyCode: 27,
                        which: 27
                    });
                    document.dispatchEvent(esc);
                    if (window.AndroidBridge) window.AndroidBridge.onChatStateChanged(false);
                };

                // 4. Attach Back button when header is present in conversation
                var obs = new MutationObserver(function() {
                    var mainEl = document.getElementById('main');
                    if (!mainEl) return;
                    var header = mainEl.querySelector('header');
                    if (header) {
                        if (window.AndroidBridge) window.AndroidBridge.onChatStateChanged(true);
                        if (!header.querySelector('#mabs-back-btn')) {
                            var btn = document.createElement('button');
                            btn.id = 'mabs-back-btn';
                            btn.innerHTML = '&#8592;';
                            btn.setAttribute('aria-label', 'Volver');
                            btn.onclick = function(e) {
                                e.preventDefault();
                                e.stopPropagation();
                                window.mabsGoBack();
                            };
                            header.insertBefore(btn, header.firstChild);
                        }
                    } else {
                        if (window.AndroidBridge) window.AndroidBridge.onChatStateChanged(false);
                    }
                });
                obs.observe(document.body, { childList: true, subtree: true });
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
    }

    private fun checkForUpdates(manual: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = URL(updateManifestUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 6000
                conn.readTimeout = 6000
                conn.requestMethod = "GET"
                if (conn.responseCode == 200) {
                    val jsonText = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(jsonText)
                    val remoteVersionCode = json.optInt("versionCode", 0)
                    val remoteVersionName = json.optString("versionName", "")
                    val apkUrl = json.optString("apkUrl", "")
                    val releaseNotes = json.optString("releaseNotes", "Mejoras generales y correcciones.")
                    withContext(Dispatchers.Main) {
                        if (remoteVersionCode > BuildConfig.VERSION_CODE) {
                            showUpdateDialog(remoteVersionName, releaseNotes, apkUrl)
                        } else if (manual) {
                            Toast.makeText(this@MainActivity, "MABSAPP está al día (v${BuildConfig.VERSION_NAME})", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else if (manual) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "No se pudo verificar la actualización", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                if (manual) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Error al comprobar actualización", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun showUpdateDialog(versionName: String, releaseNotes: String, apkUrl: String) {
        AlertDialog.Builder(this)
            .setTitle("Actualización disponible")
            .setMessage("¡Nueva versión de MABSAPP v$versionName disponible!\n\nNovedades:\n$releaseNotes\n\n¿Deseas actualizar ahora?")
            .setCancelable(false)
            .setPositiveButton("Actualizar Ahora") { _, _ -> downloadAndInstallApk(apkUrl) }
            .setNegativeButton("Más tarde", null)
            .show()
    }

    private fun downloadAndInstallApk(apkUrl: String) {
        val progressDialog = ProgressDialog(this).apply {
            setTitle("Descargando actualización")
            setMessage("Por favor espera...")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            isIndeterminate = false
            max = 100
            setCancelable(false)
            show()
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = URL(apkUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.connect()
                val fileLength = conn.contentLength
                val apkFile = File(cacheDir, "update.apk")
                if (apkFile.exists()) apkFile.delete()
                conn.inputStream.use { input ->
                    FileOutputStream(apkFile).use { output ->
                        val buffer = ByteArray(8192)
                        var total: Long = 0
                        var count: Int
                        while (input.read(buffer).also { count = it } != -1) {
                            total += count
                            if (fileLength > 0) {
                                val progress = ((total * 100) / fileLength).toInt()
                                withContext(Dispatchers.Main) { progressDialog.progress = progress }
                            }
                            output.write(buffer, 0, count)
                        }
                        output.flush()
                    }
                }
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    promptInstallApk(apkFile)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(this@MainActivity, "Error al descargar: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun promptInstallApk(apkFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!packageManager.canRequestPackageInstalls()) {
                Toast.makeText(this, "Permite a MABSAPP instalar actualizaciones", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:$packageName")
                })
                return
            }
        }
        try {
            val apkUri = FileProvider.getUriForFile(this, "${applicationContext.packageName}.fileprovider", apkFile)
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            })
        } catch (e: Exception) {
            Toast.makeText(this, "Error al instalar: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkAndRequestAppPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MODIFY_AUDIO_SETTINGS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            requestPermissionsLauncher.launch(needed.toTypedArray())
        }
    }

    override fun onResume() { super.onResume(); webView.onResume() }
    override fun onPause() { super.onPause(); webView.onPause() }
}
