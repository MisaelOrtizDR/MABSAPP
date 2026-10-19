package com.mabsapp.whatsappweb

import android.Manifest
import android.annotation.SuppressLint
import android.app.ProgressDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
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

    // Tracks if user is currently inside an open chat conversation
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
    ) { _ ->
        // Permissions handled
    }

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
            checkForUpdates(manual = true)
        }

        // Native Android Back button handles returning from Chat to Chat List
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isChatOpen) {
                    webView.evaluateJavascript("window.mabsappCloseChat();", null)
                } else if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        // Initial URL
        webView.loadUrl("https://web.whatsapp.com")

        // Silently check for updates on startup
        checkForUpdates(manual = false)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val settings = webView.settings

        // JavaScript & DOM Storage
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true

        // User Agent: Modern Chrome Windows Desktop
        val desktopUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        settings.userAgentString = desktopUserAgent

        // Viewport & Scaling
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.textZoom = 100

        // Storage & Cache
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        // Cookies
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        // JavaScript Interface for Native communication
        webView.addJavascriptInterface(WebAppInterface(this), "AndroidBridge")

        // WebViewClient
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.contains("whatsapp.com") || url.contains("whatsapp.net")) {
                    return false
                }
                return try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivity(intent)
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
                injectSingleColumnResponsiveUI()
            }
        }

        // WebChromeClient
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress < 100) {
                    progressBar.visibility = View.VISIBLE
                } else {
                    progressBar.visibility = View.GONE
                    injectSingleColumnResponsiveUI()
                }
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                runOnUiThread {
                    request?.grant(request.resources)
                }
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
     * Injects custom CSS & JavaScript that turns WhatsApp Web into a full-width Single Column view.
     * In Chat List mode: Chat list occupies 100% width.
     * In Conversation mode: Chat message panel occupies 100% width with a back button.
     */
    private fun injectSingleColumnResponsiveUI() {
        val js = """
            (function() {
                // 1. Force responsive viewport
                var meta = document.querySelector('meta[name="viewport"]');
                if (!meta) {
                    meta = document.createElement('meta');
                    meta.name = 'viewport';
                    document.head.appendChild(meta);
                }
                meta.content = 'width=device-width, initial-scale=1.0, maximum-scale=3.0, user-scalable=yes';

                // 2. Inject CSS rules for Single-Column Master-Detail Layout
                var style = document.getElementById('mabsapp-single-column-style');
                if (!style) {
                    style = document.createElement('style');
                    style.id = 'mabsapp-single-column-style';
                    document.head.appendChild(style);
                }

                style.innerHTML = `
                    html, body, #app {
                        width: 100% !important;
                        height: 100% !important;
                        overflow: hidden !important;
                        touch-action: manipulation !important;
                    }

                    /* State: Inside a conversation -> show #main full width, hide #side */
                    body.mabsapp-state-chat #side {
                        display: none !important;
                    }
                    body.mabsapp-state-chat #main {
                        display: flex !important;
                        width: 100vw !important;
                        max-width: 100vw !important;
                        min-width: 100vw !important;
                        flex: 1 1 100% !important;
                        z-index: 10 !important;
                    }

                    /* State: In Chat list -> show #side full width, hide #main */
                    body.mabsapp-state-list #main {
                        display: none !important;
                    }
                    body.mabsapp-state-list #side {
                        display: flex !important;
                        width: 100vw !important;
                        max-width: 100vw !important;
                        min-width: 100vw !important;
                        flex: 1 1 100% !important;
                    }

                    /* Back button injected in Chat header */
                    #mabsapp-back-btn {
                        display: inline-flex;
                        align-items: center;
                        justify-content: center;
                        width: 40px;
                        height: 40px;
                        margin-right: 6px;
                        cursor: pointer;
                        color: #00a884;
                        font-size: 26px;
                        font-weight: bold;
                        border-radius: 50%;
                        background: transparent;
                        border: none;
                        outline: none;
                        z-index: 9999;
                    }
                    #mabsapp-back-btn:active {
                        background: rgba(0, 168, 132, 0.15);
                    }
                `;

                // 3. Helper to close active chat
                window.mabsappCloseChat = function() {
                    // Send Escape key event to trigger WhatsApp Web's own close chat behavior
                    var escEvent = new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true });
                    document.dispatchEvent(escEvent);

                    document.body.classList.remove('mabsapp-state-chat');
                    document.body.classList.add('mabsapp-state-list');
                    if (window.AndroidBridge) {
                        window.AndroidBridge.onChatStateChanged(false);
                    }
                };

                // 4. Observer to detect current view state
                function checkLayoutState() {
                    var mainEl = document.getElementById('main');
                    var sideEl = document.getElementById('side');

                    if (!sideEl && !mainEl) {
                        // QR Landing or loading screen
                        document.body.classList.remove('mabsapp-state-chat', 'mabsapp-state-list');
                        return;
                    }

                    // Check if an actual chat is currently open
                    var isChatActive = false;
                    if (mainEl) {
                        var header = mainEl.querySelector('header');
                        // If header exists and conversation panel has messages/input
                        if (header) {
                            isChatActive = true;
                            // Inject back button in header if not already present
                            if (!header.querySelector('#mabsapp-back-btn')) {
                                var backBtn = document.createElement('button');
                                backBtn.id = 'mabsapp-back-btn';
                                backBtn.innerHTML = '&#8592;';
                                backBtn.title = 'Atrás';
                                backBtn.onclick = function(e) {
                                    e.preventDefault();
                                    e.stopPropagation();
                                    window.mabsappCloseChat();
                                };
                                header.insertBefore(backBtn, header.firstChild);
                            }
                        }
                    }

                    if (isChatActive) {
                        if (!document.body.classList.contains('mabsapp-state-chat')) {
                            document.body.classList.remove('mabsapp-state-list');
                            document.body.classList.add('mabsapp-state-chat');
                            if (window.AndroidBridge) {
                                window.AndroidBridge.onChatStateChanged(true);
                            }
                        }
                    } else {
                        if (!document.body.classList.contains('mabsapp-state-list')) {
                            document.body.classList.remove('mabsapp-state-chat');
                            document.body.classList.add('mabsapp-state-list');
                            if (window.AndroidBridge) {
                                window.AndroidBridge.onChatStateChanged(false);
                            }
                        }
                    }
                }

                checkLayoutState();
                if (!window.mabsappIntervalSet) {
                    window.mabsappIntervalSet = true;
                    setInterval(checkLayoutState, 350);
                }
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
                            Toast.makeText(
                                this@MainActivity,
                                "MABSAPP está al día (v${BuildConfig.VERSION_NAME})",
                                Toast.LENGTH_SHORT
                            ).show()
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
            .setPositiveButton("Actualizar Ahora") { _, _ ->
                downloadAndInstallApk(apkUrl)
            }
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
                                withContext(Dispatchers.Main) {
                                    progressDialog.progress = progress
                                }
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
                    Toast.makeText(this@MainActivity, "Error al descargar actualización: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun promptInstallApk(apkFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!packageManager.canRequestPackageInstalls()) {
                Toast.makeText(this, "Permite a MABSAPP instalar actualizaciones para continuar", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
                return
            }
        }

        try {
            val apkUri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            startActivity(installIntent)
        } catch (e: Exception) {
            Toast.makeText(this, "Error al iniciar instalador: ${e.message}", Toast.LENGTH_LONG).show()
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

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }
}
