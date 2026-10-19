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

        // Native Android Back button / Gesture handling
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isChatOpen) {
                    webView.evaluateJavascript("window.mabsappShowList();", null)
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

        // Viewport settings
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

        // JavaScript Interface
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
                injectEventDrivenSingleColumnUI()
            }
        }

        // WebChromeClient
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress < 100) {
                    progressBar.visibility = View.VISIBLE
                } else {
                    progressBar.visibility = View.GONE
                    injectEventDrivenSingleColumnUI()
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
     * Event-Driven Single-Column Architecture.
     * Never battles React's virtual DOM:
     * - In List View: Chats list occupies 100vw, conversation is hidden.
     * - In Chat View: Conversation occupies 100vw, chat list is hidden.
     * State changes occur strictly via user interactions (clicking a chat or pressing Back).
     */
    private fun injectEventDrivenSingleColumnUI() {
        val js = """
            (function() {
                // 1. Mobile viewport
                var meta = document.querySelector('meta[name="viewport"]');
                if (!meta) {
                    meta = document.createElement('meta');
                    meta.name = 'viewport';
                    document.head.appendChild(meta);
                }
                meta.content = 'width=device-width, initial-scale=1.0, maximum-scale=2.5, user-scalable=yes';

                // 2. Inject Solid CSS
                var style = document.getElementById('mabsapp-event-style');
                if (!style) {
                    style = document.createElement('style');
                    style.id = 'mabsapp-event-style';
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

                    #app > div > div {
                        display: flex !important;
                        flex-direction: row !important;
                        width: 100vw !important;
                        height: 100% !important;
                        overflow: hidden !important;
                        position: relative !important;
                    }

                    /* --- STATE: CHAT LIST VIEW --- */
                    body.mabs-show-list #main {
                        display: none !important;
                    }
                    body.mabs-show-list #side {
                        display: flex !important;
                        width: calc(100vw - 52px) !important;
                        max-width: calc(100vw - 52px) !important;
                        flex: 1 1 auto !important;
                    }
                    body.mabs-show-list div[role="navigation"],
                    body.mabs-show-list div._aigs {
                        display: flex !important;
                        width: 52px !important;
                        min-width: 52px !important;
                        max-width: 52px !important;
                    }

                    /* --- STATE: CONVERSATION VIEW --- */
                    body.mabs-show-chat div[role="navigation"],
                    body.mabs-show-chat div._aigs,
                    body.mabs-show-chat #side {
                        display: none !important;
                    }
                    body.mabs-show-chat #main {
                        display: flex !important;
                        position: fixed !important;
                        top: 0 !important;
                        left: 0 !important;
                        width: 100vw !important;
                        height: 100% !important;
                        max-width: 100vw !important;
                        min-width: 100vw !important;
                        z-index: 9999 !important;
                    }

                    /* Back Button in Chat Header */
                    #mabsapp-back-btn {
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
                    #mabsapp-back-btn:active {
                        background: rgba(0, 168, 132, 0.2) !important;
                        border-radius: 50% !important;
                    }
                `;

                // 3. Navigation Actions
                window.mabsappShowList = function() {
                    document.body.classList.remove('mabs-show-chat');
                    document.body.classList.add('mabs-show-list');
                    if (window.AndroidBridge) {
                        window.AndroidBridge.onChatStateChanged(false);
                    }

                    // Dispatch Escape to close active chat in WhatsApp Web
                    var escEvent = new KeyboardEvent('keydown', {
                        bubbles: true,
                        cancelable: true,
                        key: 'Escape',
                        code: 'Escape',
                        keyCode: 27,
                        which: 27
                    });
                    document.dispatchEvent(escEvent);
                };

                window.mabsappShowChat = function() {
                    document.body.classList.remove('mabs-show-list');
                    document.body.classList.add('mabs-show-chat');
                    if (window.AndroidBridge) {
                        window.AndroidBridge.onChatStateChanged(true);
                    }

                    // Attach back button to header
                    function attachBtn() {
                        var mainEl = document.getElementById('main');
                        if (!mainEl) return;
                        var header = mainEl.querySelector('header');
                        if (header && !header.querySelector('#mabsapp-back-btn')) {
                            var btn = document.createElement('button');
                            btn.id = 'mabsapp-back-btn';
                            btn.innerHTML = '&#8592;';
                            btn.setAttribute('aria-label', 'Volver');
                            btn.onclick = function(e) {
                                e.preventDefault();
                                e.stopPropagation();
                                window.mabsappShowList();
                            };
                            header.insertBefore(btn, header.firstChild);
                        }
                    }

                    setTimeout(attachBtn, 100);
                    setTimeout(attachBtn, 300);
                    setTimeout(attachBtn, 600);
                    setTimeout(attachBtn, 1200);
                };

                // 4. Initial state: Start in Chat List
                if (!document.body.classList.contains('mabs-show-chat')) {
                    document.body.classList.add('mabs-show-list');
                }

                // 5. Event Delegation with CAPTURE phase:
                // Intercept any click inside the chat list to immediately switch view
                if (!window.mabsappEventsRegistered) {
                    window.mabsappEventsRegistered = true;

                    document.addEventListener('click', function(e) {
                        var paneSide = document.getElementById('pane-side');
                        var side = document.getElementById('side');

                        // If user clicked inside the chat list area (#pane-side)
                        if (paneSide && paneSide.contains(e.target)) {
                            // Ignore if clicked on search or filter bar
                            window.mabsappShowChat();
                        } else if (side && side.contains(e.target)) {
                            // Check if clicked element looks like a chat row
                            var row = e.target.closest('[role="listitem"]') || e.target.closest('[data-testid="cell-frame-container"]');
                            if (row) {
                                window.mabsappShowChat();
                            }
                        }
                    }, true); // Capture phase is essential!
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
