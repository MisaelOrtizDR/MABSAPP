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

        // Initial URL strictly pointed to WhatsApp Web
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

        // User Agent: Modern Chrome Windows Desktop (needed for WhatsApp Web access)
        val desktopUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        settings.userAgentString = desktopUserAgent

        // Viewport and native sizing
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

        // Cookie management
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        // JavaScript Interface for Native communication
        webView.addJavascriptInterface(WebAppInterface(this), "AndroidBridge")

        // Custom WebViewClient
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
     * Robust Single-Column Master-Detail Layout Injection.
     * Prevents overlapping, unmounting, or glitching between Chat List and Conversation.
     */
    private fun injectSingleColumnResponsiveUI() {
        val js = """
            (function() {
                // 1. Set clean mobile viewport
                var meta = document.querySelector('meta[name="viewport"]');
                if (!meta) {
                    meta = document.createElement('meta');
                    meta.name = 'viewport';
                    document.head.appendChild(meta);
                }
                meta.content = 'width=device-width, initial-scale=1.0, maximum-scale=2.5, user-scalable=yes';

                // 2. Inject Solid Single-Column CSS
                var style = document.getElementById('mabsapp-solid-style');
                if (!style) {
                    style = document.createElement('style');
                    style.id = 'mabsapp-solid-style';
                    document.head.appendChild(style);
                }

                style.innerHTML = `
                    html, body, #app {
                        width: 100vw !important;
                        height: 100% !important;
                        overflow: hidden !important;
                        margin: 0 !important;
                        padding: 0 !important;
                    }

                    /* --- STATE: CHAT LIST VIEW --- */
                    body.mabsapp-list-mode #main {
                        display: none !important;
                    }
                    body.mabsapp-list-mode #side {
                        display: flex !important;
                        width: 100% !important;
                        max-width: 100% !important;
                        min-width: 0 !important;
                        flex: 1 1 auto !important;
                    }
                    body.mabsapp-list-mode #app > div > div {
                        width: 100vw !important;
                        max-width: 100vw !important;
                    }

                    /* --- STATE: CONVERSATION VIEW --- */
                    body.mabsapp-chat-mode #side,
                    body.mabsapp-chat-mode div[role="navigation"],
                    body.mabsapp-chat-mode div._aigs {
                        display: none !important;
                    }
                    body.mabsapp-chat-mode #main {
                        display: flex !important;
                        position: fixed !important;
                        top: 0 !important;
                        left: 0 !important;
                        width: 100vw !important;
                        height: 100% !important;
                        max-width: 100vw !important;
                        min-width: 100vw !important;
                        z-index: 100 !important;
                    }

                    /* Back button in Conversation header */
                    #mabsapp-back-btn {
                        display: inline-flex !important;
                        align-items: center !important;
                        justify-content: center !important;
                        width: 40px !important;
                        height: 40px !important;
                        margin-right: 6px !important;
                        margin-left: 2px !important;
                        cursor: pointer !important;
                        color: #00a884 !important;
                        font-size: 26px !important;
                        font-weight: bold !important;
                        border-radius: 50% !important;
                        background: transparent !important;
                        border: none !important;
                        outline: none !important;
                        z-index: 9999 !important;
                        flex-shrink: 0 !important;
                    }
                    #mabsapp-back-btn:active {
                        background: rgba(0, 168, 132, 0.2) !important;
                    }
                `;

                // 3. Close chat helper
                window.mabsappCloseChat = function() {
                    // Send Escape key event to trigger WhatsApp Web's own chat deselect
                    var escEvent = new KeyboardEvent('keydown', {
                        bubbles: true,
                        cancelable: true,
                        key: 'Escape',
                        code: 'Escape',
                        keyCode: 27,
                        which: 27
                    });
                    document.dispatchEvent(escEvent);

                    var closeBtn = document.querySelector('#main [data-testid="btn-close"]') || document.querySelector('#main [data-icon="x"]');
                    if (closeBtn) closeBtn.click();

                    document.body.classList.remove('mabsapp-chat-mode');
                    document.body.classList.add('mabsapp-list-mode');
                    if (window.AndroidBridge) {
                        window.AndroidBridge.onChatStateChanged(false);
                    }
                };

                // 4. Stable State Detector using footer/composer presence
                function syncLayoutState() {
                    var mainEl = document.getElementById('main');
                    var sideEl = document.getElementById('side');

                    if (!sideEl && !mainEl) {
                        // QR Landing screen -> do not alter
                        document.body.classList.remove('mabsapp-chat-mode', 'mabsapp-list-mode');
                        return;
                    }

                    // A conversation is open IF AND ONLY IF #main has an active footer / message input
                    var hasFooter = !!(mainEl && mainEl.querySelector('footer'));
                    var hasInput = !!(mainEl && (mainEl.querySelector('[data-testid="conversation-compose-box-input"]') || mainEl.querySelector('[contenteditable="true"]')));
                    var isChatActive = hasFooter || hasInput;

                    if (isChatActive) {
                        if (!document.body.classList.contains('mabsapp-chat-mode')) {
                            document.body.classList.remove('mabsapp-list-mode');
                            document.body.classList.add('mabsapp-chat-mode');
                            if (window.AndroidBridge) {
                                window.AndroidBridge.onChatStateChanged(true);
                            }
                        }

                        // Inject back button in header
                        var header = mainEl.querySelector('header');
                        if (header && !header.querySelector('#mabsapp-back-btn')) {
                            var backBtn = document.createElement('button');
                            backBtn.id = 'mabsapp-back-btn';
                            backBtn.innerHTML = '&#8592;';
                            backBtn.setAttribute('aria-label', 'Volver');
                            backBtn.onclick = function(e) {
                                e.preventDefault();
                                e.stopPropagation();
                                window.mabsappCloseChat();
                            };
                            header.insertBefore(backBtn, header.firstChild);
                        }
                    } else {
                        if (!document.body.classList.contains('mabsapp-list-mode')) {
                            document.body.classList.remove('mabsapp-chat-mode');
                            document.body.classList.add('mabsapp-list-mode');
                            if (window.AndroidBridge) {
                                window.AndroidBridge.onChatStateChanged(false);
                            }
                        }
                    }
                }

                syncLayoutState();
                if (!window.mabsappSyncInterval) {
                    window.mabsappSyncInterval = setInterval(syncLayoutState, 300);
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
