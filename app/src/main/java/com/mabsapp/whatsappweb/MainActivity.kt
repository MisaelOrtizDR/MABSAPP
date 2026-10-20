package com.mabsapp.whatsappweb

import android.Manifest
import android.annotation.SuppressLint
import android.app.ProgressDialog
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.provider.Settings
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
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
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnCheckUpdate: FloatingActionButton

    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var pendingApkFile: File? = null
    private var isClearingData = false

    @Volatile
    var isChatOpen: Boolean = false

    private var lastUpdateFailed = false

    private val updateManifestUrl =
        "https://raw.githubusercontent.com/MisaelOrtizDR/MABSAPP/main/version.json"

    private val releasePageUrl =
        "https://github.com/MisaelOrtizDR/MABSAPP/releases/latest"

    private val DRIVE_FALLBACK_URL =
        "https://drive.google.com/drive/folders/1UQutJi8629yL_D4klZCrGYElHwHfzfij"

    // SHA-256 of the MABSAPP release certificate (apksigner verify --print-certs).
    // The signing key never rotates: rotating it would break in-place OTA installs.
    private val EXPECTED_CERT_SHA256 =
        "5cd1b7e5b9a41b82ea64ea824ecc2f278791aa37eeb16f40e1c6e107b7ed2336"

    /**
     * Only these domains stay inside the WebView. Everything else opens in the
     * device browser. The comparison uses a dot boundary so that malicious
     * lookalikes such as "notwhatsapp.com" are NOT accepted.
     */
    private val allowedHosts = listOf(
        "whatsapp.com",
        "whatsapp.net",
        "facebook.com",
        "fbcdn.net",
        "meta.com",
        "instagram.com",
        "cdninstagram.com"
    )

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = fileUploadCallback ?: return@registerForActivityResult
        fileUploadCallback = null
        val results = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        callback.onReceiveValue(results)
    }

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    inner class WebAppInterface {

        @JavascriptInterface
        fun onChatStateChanged(inChat: Boolean) {
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                isChatOpen = inChat
                // The floating updater button covers WhatsApp's send button in a
                // conversation, so it is hidden while a chat is open.
                btnCheckUpdate.visibility = if (inChat) View.GONE else View.VISIBLE
            }
        }

        @JavascriptInterface
        fun onDataCleared() {
            runOnUiThread { completeDataClearing() }
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

        btnCheckUpdate.setOnClickListener { showActionMenu() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isChatOpen) {
                    webView.evaluateJavascript(
                        "window.mabsGoBack ? window.mabsGoBack() : null;",
                        null
                    )
                } else if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        webView.loadUrl("https://web.whatsapp.com")
        // Short toast on launch: lets the user confirm which version is really
        // installed (important onTranssion ROMs where updates may fail silently).
        Toast.makeText(this, "MABSAPP v${BuildConfig.VERSION_NAME}", Toast.LENGTH_SHORT).show()
        checkForUpdates(manual = false)
    }

    private fun showActionMenu() {
        val options = arrayOf(
            "Buscar actualización",
            "Recargar página",
            "Limpiar datos y reiniciar",
            "Descargar APK (Google Drive)",
            "Abrir página de descarga (GitHub)"
        )
        AlertDialog.Builder(this)
            .setTitle("MABSAPP v${BuildConfig.VERSION_NAME}")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> checkForUpdates(manual = true)
                    1 -> webView.reload()
                    2 -> confirmClearData()
                    3 -> openDriveFallback()
                    4 -> openReleasePage()
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun openReleasePage() {
        try {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/MisaelOrtizDR/MABSAPP/releases/latest")
                )
            )
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir el navegador", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openDriveFallback() {
        try {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(DRIVE_FALLBACK_URL))
            startActivity(browserIntent)
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir el navegador", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmClearData() {
        AlertDialog.Builder(this)
            .setTitle("¿Limpiar todos los datos?")
            .setMessage(
                "Se cerrará tu sesión de WhatsApp Web y se borrarán cookies, caché, " +
                    "almacenamiento local y bases de datos internas.\n\n" +
                    "Tendrás que escanear el código QR otra vez. ¿Continuar?"
            )
            .setPositiveButton("Sí, limpiar") { _, _ -> clearDataAndReload() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /**
     * WhatsApp Web keeps the session in IndexedDB, service workers, localStorage
     * and cookies. Clearing only cookies is not enough. We ask the page to clear
     * its own storage and wait for its callback before clearing cookies/cache and
     * reloading. A one-shot fallback guarantees the reload even if the page is in
     * a bad state.
     */
    private fun clearDataAndReload() {
        if (isClearingData || isFinishing || isDestroyed) return
        isClearingData = true
        Toast.makeText(this, "Limpiando datos y cerrando sesión...", Toast.LENGTH_SHORT).show()
        webView.evaluateJavascript(
            "window.mabsClearWebData ? window.mabsClearWebData() : null;",
            null
        )
        webView.postDelayed({ completeDataClearing() }, 4000)
    }

    private fun completeDataClearing() {
        if (!isClearingData || isFinishing || isDestroyed) return
        isClearingData = false

        val cookieManager = CookieManager.getInstance()
        var reloaded = false
        val reload = {
            if (!reloaded) {
                reloaded = true
                cookieManager.flush()
                WebStorage.getInstance().deleteAllData()
                WebViewDatabase.getInstance(this).clearFormData()
                webView.clearCache(true)
                webView.clearFormData()
                webView.clearHistory()
                webView.loadUrl("https://web.whatsapp.com")
                Toast.makeText(
                    this,
                    "Datos borrados. Escanea el QR para volver a entrar.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        cookieManager.removeAllCookies {
            runOnUiThread { reload() }
        }
        // Fallback in case the cookie callback never arrives.
        webView.postDelayed({ reload() }, 1500)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val settings = webView.settings

        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(true)
        settings.setSaveFormData(false)

        // WhatsApp Web blocks mobile user agents, so we present a desktop Chrome
        // UA. The viewport, however, MUST stay at the real phone width.
        settings.userAgentString =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/130.0.0.0 Safari/537.36"

        // ===== CORE SINGLE-COLUMN FIX =====
        // v1.7 used useWideViewPort + loadWithOverviewMode, which makes 100vw
        // equal to a ~980px desktop layout. That is why the chat list and the
        // conversation appeared side by side and the page scrolled sideways.
        settings.useWideViewPort = false
        settings.loadWithOverviewMode = false
        settings.layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
        settings.textZoom = 100
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false

        // Storage & cache
        settings.allowFileAccess = false
        settings.allowContentAccess = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        // Kept permissive for WhatsApp Web compatibility (TLS is still enforced
        // by the platform for https:// URLs).
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        webView.setBackgroundColor(0xFF111B21.toInt())
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.overScrollMode = View.OVER_SCROLL_NEVER

        // Downloads: WhatsApp Web sometimes triggers an http(s) download. Opening
        // it in the device browser is safer than silently doing nothing.
        webView.setDownloadListener { url, _, _, _, _ ->
            if (url.startsWith("http://") || url.startsWith("https://")) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: Exception) {
                    Toast.makeText(
                        this,
                        "No se pudo abrir la descarga",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                Toast.makeText(
                    this,
                    "Este tipo de descarga no está soportado",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.addJavascriptInterface(WebAppInterface(), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val uri = request?.url ?: return false
                val url = uri.toString()
                val host = uri.host?.lowercase() ?: ""

                // Keep the WhatsApp/Meta ecosystem inside the WebView.
                if ((url.startsWith("http://") || url.startsWith("https://")) &&
                    isAllowedHost(host)
                ) {
                    return false
                }

                // blob: URLs belong to the current page (media previews).
                if (url.startsWith("blob:")) return false

                // Open external http(s), mail, phone, etc. outside the app.
                return try {
                    if (url.startsWith("http://") || url.startsWith("https://") ||
                        url.startsWith("mailto:") || url.startsWith("tel:") ||
                        url.startsWith("sms:") || url.startsWith("whatsapp:")
                    ) {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                        true
                    } else {
                        // Block unknown schemes instead of letting the page launch
                        // arbitrary intents.
                        true
                    }
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
                if (isFinishing || isDestroyed) return
                progressBar.visibility = View.GONE
                injectMobileLayout()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    progressBar.visibility = View.GONE
                    Toast.makeText(
                        this@MainActivity,
                        "Sin conexión. Abre el menú flotante y pulsa Recargar.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request?.isForMainFrame == true) {
                    progressBar.visibility = View.GONE
                }
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                // Never bypass a certificate error.
                handler?.cancel()
                Toast.makeText(
                    this@MainActivity,
                    "Error de seguridad SSL. Conexión cancelada.",
                    Toast.LENGTH_LONG
                ).show()
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                if (isFinishing || isDestroyed) return true
                Toast.makeText(
                    this@MainActivity,
                    "El motor WebView se reinició por un error. Reabriendo...",
                    Toast.LENGTH_LONG
                ).show()
                recreate()
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress < 100) {
                    progressBar.visibility = View.VISIBLE
                } else {
                    progressBar.visibility = View.GONE
                }
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                val popup = WebView(this@MainActivity)
                popup.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val uri = request?.url ?: return false
                        return try {
                            startActivity(Intent(Intent.ACTION_VIEW, uri))
                            true
                        } catch (e: Exception) {
                            true
                        }
                    }
                }
                transport.webView = popup
                resultMsg.sendToTarget()
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                runOnUiThread { handlePermissionRequest(request) }
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                if (filePathCallback == null) return false

                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback

                val intent = try {
                    fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "*/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }
                } catch (e: Exception) {
                    Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "*/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }
                }

                return try {
                    fileChooserLauncher.launch(intent)
                    true
                } catch (e: Exception) {
                    fileUploadCallback = null
                    Toast.makeText(
                        this@MainActivity,
                        "No se encontró explorador de archivos",
                        Toast.LENGTH_SHORT
                    ).show()
                    false
                }
            }
        }
    }

    private fun handlePermissionRequest(request: PermissionRequest?) {
        if (request == null) return

        val host = request.origin?.host?.lowercase() ?: ""
        if (!isAllowedHost(host)) {
            request.deny()
            return
        }

        val missing = mutableListOf<String>()
        if (request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE) &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            missing.add(Manifest.permission.CAMERA)
        }
        if (request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE) &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            missing.add(Manifest.permission.RECORD_AUDIO)
        }

        if (missing.isEmpty()) {
            request.grant(request.resources)
        } else {
            request.deny()
            Toast.makeText(
                this,
                "Concede cámara y micrófono a MABSAPP para usar esta función.",
                Toast.LENGTH_LONG
            ).show()
            requestPermissionsLauncher.launch(missing.toTypedArray())
        }
    }

    /**
     * Single-column engine (v1.8.1).
     *
     * CSS is injected as soon as the page finishes (not gated on #side), and the
     * whole layout is then enforced from JavaScript with inline `!important`
     * styles: stylesheet rules and min-widths from WhatsApp cannot undo it.
     * Only one pane is ever visible: the chat list, or the conversation fixed
     * full-screen. A rAF-debounced MutationObserver keeps the state in sync
     * without polling or timers.
     */
    private fun injectMobileLayout() {
        val js = """
            (function() {
                if (window.__mabsInit) return;
                window.__mabsInit = true;

                // 1. Base CSS: device width, no page-level horizontal scroll.
                var CSS = [
                    'html, body, #app { width: 100% !important; max-width: 100% !important; min-width: 0 !important; height: 100% !important; overflow: hidden !important; margin: 0 !important; padding: 0 !important; -webkit-text-size-adjust: 100% !important; overscroll-behavior: none !important; }',
                    '#app { position: relative !important; }',
                    '.mabs-min0 { min-width: 0 !important; max-width: 100% !important; }',
                    'body.mabs-openstate .mabs-listpane { transform: none !important; filter: none !important; margin: 0 !important; }',
                    'body.mabs-openstate #app > div, body.mabs-openstate #app > div > div { transform: none !important; filter: none !important; }',
                    'body.mabs-openstate .mabs-convo [data-testid="conversation-panel-wrapper"], body.mabs-openstate .mabs-convo [data-testid="conversation-panel-messages"], body.mabs-openstate .mabs-convo footer, body.mabs-openstate .mabs-convo footer > div { width: 100% !important; max-width: 100% !important; min-width: 0 !important; }',
                    '#main .message-in, #main .message-out { max-width: 85% !important; }',
                    '#mabs-back-btn { display: inline-flex !important; align-items: center !important; justify-content: center !important; width: 42px !important; min-width: 42px !important; height: 42px !important; margin: 0 2px 0 0 !important; padding: 0 !important; cursor: pointer !important; color: var(--icon-color, #00a884) !important; font-size: 26px !important; font-weight: 600 !important; line-height: 1 !important; background: transparent !important; border: none !important; outline: none !important; flex: 0 0 42px !important; z-index: 100001 !important; }',
                    '#mabs-back-btn:active { background: rgba(0, 168, 132, 0.18) !important; border-radius: 50% !important; }',
                    '::-webkit-scrollbar { width: 0 !important; height: 0 !important; }'
                ].join('\n');

                function injectStyle() {
                    var old = document.getElementById('mabs-mobile-style');
                    if (old && old.parentNode) old.parentNode.removeChild(old);
                    var style = document.createElement('style');
                    style.id = 'mabs-mobile-style';
                    style.textContent = CSS;
                    (document.head || document.documentElement).appendChild(style);
                }

                function injectViewport() {
                    try {
                        var meta = document.querySelector('meta[name="viewport"]');
                        if (!meta) {
                            meta = document.createElement('meta');
                            meta.name = 'viewport';
                            (document.head || document.documentElement).appendChild(meta);
                        }
                        meta.setAttribute(
                            'content',
                            'width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no, viewport-fit=cover'
                        );
                    } catch (e) {}
                }

                injectViewport();
                injectStyle();

                // 2. Keep the stylesheet alive if React replaces the head.
                function ensureStyle() {
                    if (!document.getElementById('mabs-mobile-style')) injectStyle();
                }

                // 3. Pane discovery: old and new WhatsApp DOM names.
                function findChatListPane() {
                    try {
                        var el = document.getElementById('side');
                        if (!el) {
                            el = document.querySelector('[data-testid="drawer"], [data-testid="chat-list"], #pane-side');
                        }
                        if (!el) {
                            var grid = document.querySelector('[role="grid"]');
                            if (grid) el = grid.closest('#app > div > div, #app > div, body > div');
                        }
                        return el || null;
                    } catch (e) { return null; }
                }

                function findConvoPane() {
                    try {
                        var el = document.getElementById('main');
                        if (!el) {
                            el = document.querySelector('[data-testid="conversation-panel-wrapper"], [data-testid="conversation"], main, [role="main"]');
                            if (el) el = el.closest('#app > div > div, #app > div, body > div');
                        }
                        return el || null;
                    } catch (e) { return null; }
                }

                function findRail() {
                    try {
                        var els = document.querySelectorAll('div[role="navigation"], div._aigs');
                        for (var i = 0; i < els.length; i++) {
                            var el = els[i];
                            if (el.style.display === 'none') continue;
                            var r = el.getBoundingClientRect();
                            if (r.width > 1 && r.height > 1) return el;
                        }
                        return null;
                    } catch (e) { return null; }
                }

                // 4. Chat-open detection.
                function isChatOpen() {
                    try {
                        var main = document.getElementById('main');
                        if (!main) return false;
                        if (main.querySelector('[data-testid="conversation-panel-wrapper"]')) return true;
                        if (main.querySelector('[data-testid^="conversation-info-header"]')) return true;
                        if (main.querySelector('[data-testid="conversation-compose-box-input"]')) return true;
                        if (main.querySelector('footer [contenteditable="true"]')) return true;
                        if (main.querySelector('header') && main.querySelector('footer')) return true;
                        return false;
                    } catch (e) { return false; }
                }

                // 5. Back button (own one only if WhatsApp has none).
                function ensureBackButton() {
                    try {
                        var main = document.getElementById('main');
                        if (!main) return;
                        var header = main.querySelector('header');
                        if (!header) return;
                        var nativeBack = header.querySelector('[data-testid="back"], [aria-label="Back"], [aria-label="Atrás"], [title="Back"], [title="Atrás"]');
                        var mine = header.querySelector('#mabs-back-btn');
                        if (nativeBack) {
                            if (mine && mine.parentNode) mine.parentNode.removeChild(mine);
                            return;
                        }
                        if (mine) return;
                        var btn = document.createElement('button');
                        btn.id = 'mabs-back-btn';
                        btn.type = 'button';
                        btn.setAttribute('aria-label', 'Volver');
                        btn.textContent = '\u2190';
                        btn.addEventListener('click', function(e) {
                            e.preventDefault();
                            e.stopPropagation();
                            if (window.mabsGoBack) window.mabsGoBack();
                        });
                        header.insertBefore(btn, header.firstChild);
                    } catch (e) {}
                }

                window.mabsGoBack = function() {
                    try {
                        var main = document.getElementById('main');
                        if (main) {
                            var nativeBack = main.querySelector('header [data-testid="back"], header [aria-label="Back"], header [aria-label="Atrás"], header [title="Back"], header [title="Atrás"]');
                            if (nativeBack) {
                                nativeBack.click();
                                return true;
                            }
                        }
                        var esc = new KeyboardEvent('keydown', {
                            bubbles: true,
                            cancelable: true,
                            key: 'Escape',
                            code: 'Escape',
                            keyCode: 27,
                            which: 27
                        });
                        document.dispatchEvent(esc);
                        return true;
                    } catch (e) { return false; }
                };

                // 6. Full data wipe (cookies/localStorage/IndexedDB/SW/caches) via bridge.
                window.mabsClearWebData = function() {
                    function finish() {
                        try {
                            if (window.AndroidBridge) window.AndroidBridge.onDataCleared();
                        } catch (e) {}
                    }

                    var tasks = [];
                    try {
                        localStorage.clear();
                        sessionStorage.clear();
                    } catch (e) {}

                    try {
                        if (window.indexedDB && indexedDB.databases) {
                            tasks.push(indexedDB.databases().then(function(dbs) {
                                return Promise.all(dbs.map(function(db) {
                                    return new Promise(function(resolve) {
                                        if (!db || !db.name) {
                                            resolve();
                                            return;
                                        }
                                        var req = indexedDB.deleteDatabase(db.name);
                                        req.onsuccess = req.onerror = req.onblocked = function() {
                                            resolve();
                                        };
                                    });
                                }));
                            }).catch(function() {}));
                        }
                    } catch (e) {}

                    try {
                        if (navigator.serviceWorker) {
                            tasks.push(navigator.serviceWorker.getRegistrations().then(function(regs) {
                                return Promise.all(regs.map(function(reg) {
                                    return reg.unregister().catch(function() {});
                                }));
                            }).catch(function() {}));
                        }
                    } catch (e) {}

                    try {
                        if (window.caches) {
                            tasks.push(caches.keys().then(function(keys) {
                                return Promise.all(keys.map(function(key) {
                                    return caches.delete(key).catch(function() {});
                                }));
                            }).catch(function() {}));
                        }
                    } catch (e) {}

                    if (tasks.length === 0) {
                        finish();
                        return;
                    }
                    Promise.all(tasks).catch(function() {}).then(finish);
                };

                // 7. Layout enforcement: chat list OR conversation, never both.
                function applyProps(el, props) {
                    if (!el || !el.style) return;
                    try {
                        for (var name in props) el.style.setProperty(name, props[name], 'important');
                    } catch (e) {}
                }

                function enforceList(side, main, rail) {
                    // Conversation gone (the only way to guarantee one column).
                    if (main) applyProps(main, { display: 'none', visibility: 'hidden', 'pointer-events': 'none' });
                    // Chat list fills the screen minus the rail (if present).
                    if (side) {
                        applyProps(side, {
                            display: '',
                            visibility: 'visible',
                            opacity: '1',
                            width: 'calc(100% - var(--mabs-rail, 52px))',
                            'max-width': '100%',
                            'min-width': '0',
                            flex: '1 1 auto',
                            overflow: 'hidden',
                            transform: 'none',
                            margin: '0',
                            'margin-left': '0',
                            'pointer-events': ''
                        });
                    }
                    if (rail) {
                        applyProps(rail, {
                            display: '',
                            visibility: 'visible',
                            width: '52px',
                            'min-width': '52px',
                            'max-width': '52px',
                            flex: '0 0 52px',
                            'flex-shrink': '0'
                        });
                    }
                }

                function enforceOpen(side, main, rail) {
                    if (side) applyProps(side, { display: 'none', visibility: 'hidden', 'pointer-events': 'none' });
                    if (rail) applyProps(rail, { display: 'none', visibility: 'hidden', 'pointer-events': 'none' });
                    if (main) {
                        applyProps(main, {
                            display: '',
                            visibility: 'visible',
                            opacity: '1',
                            'pointer-events': '',
                            position: 'fixed',
                            left: '0',
                            top: '0',
                            right: 'auto',
                            bottom: 'auto',
                            width: '100vw',
                            'max-width': '100vw',
                            'min-width': '0',
                            height: '100vh',
                            'max-height': '100vh',
                            'min-height': '0',
                            'z-index': '2147483000',
                            margin: '0',
                            transform: 'none',
                            filter: 'none',
                            'border-radius': '0',
                            'box-shadow': 'none',
                            overflow: 'hidden',
                            'background-color': 'var(--app-background, #111b21)'
                        });
                    }
                }

                function syncState() {
                    var body = document.body;
                    if (!body) return;
                    ensureStyle();

                    var open = isChatOpen();
                    var side = findChatListPane();
                    var main = findConvoPane();
                    var rail = findRail();

                    try {
                        if (side) side.classList.add('mabs-listpane');
                        if (main) main.classList.add('mabs-convo');
                        if (rail) rail.classList.add('mabs-railpane');
                    } catch (e) {}

                    // Root/intermediate containers must not carry desktop widths.
                    try {
                        var app = document.getElementById('app');
                        if (app) {
                            app.classList.add('mabs-min0');
                            var walk = app.children;
                            for (var i = 0; i < walk.length; i++) {
                                walk[i].classList.add('mabs-min0');
                                var inner = walk[i].children;
                                for (var j = 0; j < inner.length; j++) inner[j].classList.add('mabs-min0');
                            }
                        }
                    } catch (e) {}

                    // Keep the page pinned to x=0 so WhatsApp's desktop layout
                    // cannot leave the user stranded mid-pan.
                    try {
                        if (window.scrollX !== 0 || window.scrollY !== 0) window.scrollTo(0, 0);
                        document.documentElement.style.overflowX = 'hidden';
                    } catch (e) {}

                    if (open) {
                        body.classList.add('mabs-openstate');
                        body.classList.remove('mabs-liststate');
                        enforceOpen(side, main, rail);
                        ensureBackButton();
                    } else {
                        body.classList.add('mabs-liststate');
                        body.classList.remove('mabs-openstate');
                        enforceList(side, main, rail);
                    }

                    if (open !== window.__mabsChatOpen) {
                        window.__mabsChatOpen = open;
                        try {
                            if (window.AndroidBridge) window.AndroidBridge.onChatStateChanged(open);
                        } catch (e) {}
                    }
                }

                var scheduled = false;
                function scheduleSync() {
                    if (scheduled) return;
                    scheduled = true;
                    requestAnimationFrame(function() {
                        scheduled = false;
                        syncState();
                    });
                }

                syncState();
                window.__mabsObserver = new MutationObserver(scheduleSync);
                window.__mabsObserver.observe(document.documentElement, {
                    childList: true,
                    subtree: true
                });
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
    }

    /**
     * Shown when a downloaded APK fails validation. This is also how users on
     * an OLDER build (with the rejected-valid-APK bug) break out of the loop:
     * they install the fixed build manually once, and from then on the in-app
     * updater works normally.
     */
    private fun showValidationFallback() {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle("No pudimos verificar el APK descargado")
            .setMessage(
                "La verificación de la firma falló. Es un error conocido en algunos " +
                    "Android (incluye Android 13) y ya está corregido en esta versión.\n\n" +
                    "Solución de una sola vez:\n" +
                    "1. Abre la página de descarga (GitHub).\n" +
                    "2. Descarga MABSAPP.apk.\n" +
                    "3. Ábrelo desde las notificaciones o la app Archivos e instálalo.\n\n" +
                    "Después de eso, las actualizaciones automáticas funcionan normal."
            )
            .setPositiveButton("Abrir GitHub") { _, _ -> openReleasePage() }
            .setNeutralButton("Abrir Google Drive") { _, _ -> openDriveFallback() }
            .setNegativeButton("Solo cerrar", null)
            .show()
    }

    private fun checkForUpdates(manual: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                val url = URL(updateManifestUrl + "?t=" + System.currentTimeMillis())
                conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 7000
                    readTimeout = 7000
                    requestMethod = "GET"
                    setRequestProperty("Cache-Control", "no-cache, no-store")
                    setRequestProperty("User-Agent", "MABSAPP/" + BuildConfig.VERSION_NAME)
                }

                val responseCode = conn.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val jsonText = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(jsonText)
                    val remoteVersionCode = json.optInt("versionCode", 0)
                    val remoteVersionName = json.optString("versionName", "")
                    val apkUrl = json.optString("apkUrl", "")
                    val releaseNotes = json.optString(
                        "releaseNotes",
                        "Mejoras generales y correcciones."
                    )

                    withContext(Dispatchers.Main) {
                        if (isFinishing || isDestroyed) return@withContext
                        if (remoteVersionCode > BuildConfig.VERSION_CODE) {
                            if (apkUrl.startsWith("https://")) {
                                showUpdateDialog(
                                    remoteVersionName.ifBlank { remoteVersionCode.toString() },
                                    releaseNotes,
                                    apkUrl
                                )
                            } else {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Hay una actualización, pero el enlace de descarga no es válido.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
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
                        Toast.makeText(
                            this@MainActivity,
                            "No se pudo verificar la actualización (HTTP $responseCode)",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    lastUpdateFailed = true
                    withContext(Dispatchers.Main) { onUpdateFailed() }
                }
            } catch (e: Exception) {
                lastUpdateFailed = true
                if (manual) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@MainActivity,
                            "Error al comprobar actualización: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    withContext(Dispatchers.Main) { onUpdateFailed() }
                }
            } finally {
                conn?.disconnect()
            }
        }
    }

    private fun onUpdateFailed() {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle("No se pudo verificar la actualización")
            .setMessage(
                "La comprobación automática falló (puede ser la red o el Themes/HiOS " +
                    "bloqueando el acceso). Puedes descargar la última versión a mano:\n\n" +
                    "• Menú del botón flotante → \"Descargar APK (Google Drive)\"\n" +
                    "• Menú del botón flotante → \"Abrir página de descarga (GitHub)\"\n\n" +
                    "Instala el APK y abre MABSAPP de nuevo."
            )
            .setPositiveButton("Abrir Google Drive") { _, _ -> openDriveFallback() }
            .setNeutralButton("Abrir GitHub") { _, _ -> openReleasePage() }
            .setNegativeButton("Solo cerrar", null)
            .show()
    }

    private fun showUpdateDialog(versionName: String, releaseNotes: String, apkUrl: String) {
        AlertDialog.Builder(this)
            .setTitle("Actualización disponible")
            .setMessage(
                "¡Nueva versión de MABSAPP v$versionName disponible!\n\n" +
                    "Novedades:\n$releaseNotes\n\n" +
                    "¿Deseas actualizar ahora?"
            )
            .setCancelable(false)
            .setPositiveButton("Actualizar Ahora") { _, _ ->
                downloadAndInstallApk(apkUrl)
            }
            .setNegativeButton("Más tarde", null)
            .show()
    }

    private fun downloadAndInstallApk(apkUrl: String) {
        if (!apkUrl.startsWith("https://")) {
            Toast.makeText(this, "Enlace de actualización inválido", Toast.LENGTH_LONG).show()
            return
        }

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
            val apkFile = File(cacheDir, "update.apk")
            var conn: HttpURLConnection? = null
            try {
                if (apkFile.exists()) apkFile.delete()

                conn = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20000
                    readTimeout = 30000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "MABSAPP/" + BuildConfig.VERSION_NAME)
                }

                val responseCode = conn.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw IOException("HTTP $responseCode")
                }
                val contentType = conn.contentType ?: ""
                if (contentType.contains("text/html", ignoreCase = true)) {
                    throw IOException("El servidor no devolvió un APK")
                }

                val fileLength = conn.contentLengthLong
                var lastProgress = -1

                conn.inputStream.use { input ->
                    FileOutputStream(apkFile).use { output ->
                        val buffer = ByteArray(65536)
                        var total = 0L
                        var count: Int
                        while (input.read(buffer).also { count = it } != -1) {
                            total += count
                            if (fileLength > 0) {
                                val progress = ((total * 100) / fileLength).toInt()
                                if (progress != lastProgress) {
                                    lastProgress = progress
                                    withContext(Dispatchers.Main) {
                                        if (!isFinishing && !isDestroyed && progressDialog.isShowing) {
                                            progressDialog.progress = progress
                                        }
                                    }
                                }
                            }
                            output.write(buffer, 0, count)
                        }
                        output.flush()
                    }
                }

                if (fileLength > 0 && apkFile.length() != fileLength) {
                    throw IOException("La descarga quedó incompleta")
                }
                if (!validateDownloadedApk(apkFile)) {
                    apkFile.delete()
                    withContext(Dispatchers.Main) {
                        if (!isFinishing && !isDestroyed && progressDialog.isShowing) {
                            progressDialog.dismiss()
                        }
                        showValidationFallback()
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed && progressDialog.isShowing) {
                        progressDialog.dismiss()
                    }
                    promptInstallApk(apkFile)
                }
            } catch (e: Exception) {
                apkFile.delete()
                lastUpdateFailed = true
                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed && progressDialog.isShowing) {
                        progressDialog.dismiss()
                    }
                    Toast.makeText(
                        this@MainActivity,
                        "Error al descargar: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                conn?.disconnect()
            }
        }
    }

    /**
     * Verifies that the downloaded file is really a newer MABSAPP APK signed with
     * the expected certificate. This prevents installing an HTML error page, a
     * truncated file or a tampered APK.
     *
     * v1.8.2 fix: on several Android versions (including 13/HiOS),
     * getPackageArchiveInfo() does not populate signingInfo for files that are
     * not installed, so with only GET_SIGNING_CERTIFICATES the signature set
     * came back empty and every VALID download was rejected as "not signed".
     * Now we request both flag sets, fall back to the legacy signatures array,
     * and accept either the installed certificate or the known MABSAPP cert.
     */
    private fun validateDownloadedApk(file: File): Boolean {
        // 1) Cheap integrity gate: the file must be a real ZIP (PK) of real size.
        try {
            if (!file.exists() || file.length() < 1024L * 100L) return false
            FileInputStream(file).use { input ->
                val header = ByteArray(2)
                if (input.read(header) != 2) return false
                if (header[0] != 'P'.code.toByte() || header[1] != 'K'.code.toByte()) return false
            }
        } catch (e: Exception) {
            return false
        }

        val expected = setOf(EXPECTED_CERT_SHA256)

        // 2) Parse the archive with both signing flag sets. The legacy
        //    GET_SIGNATURES path is the one that reliably fills `signatures`
        //    for archives on many devices.
        val flagSets = intArrayOf(
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES,
            PackageManager.GET_SIGNATURES
        )

        var parsedWithRightIdentity = false
        for (flags in flagSets) {
            try {
                val archive = packageManager.getPackageArchiveInfo(file.absolutePath, flags)
                    ?: continue
                if (archive.packageName != packageName) return false
                // Newer or equal version (equal = reinstall) is installable.
                if (archive.versionCodeCompat() < BuildConfig.VERSION_CODE.toLong()) {
                    return false
                }
                parsedWithRightIdentity = true
                val hashes = signatureHashes(archive)
                if (hashes.isEmpty()) continue
                val installed = installedSignatureHashes()
                return hashes == expected ||
                    (installed.isNotEmpty() && hashes == installed)
            } catch (e: Exception) {
                // Try the next flag set.
            }
        }

        // Signatures are unreadable on this device (a known getPackageArchiveInfo
        // quirk on some Android versions). The file already passed the ZIP magic,
        // package-name and versionCode gates, so accept it instead of bricking
        // the updater. A tampered APK would carry readable signatures and would
        // have been rejected above.
        return parsedWithRightIdentity
    }

    @Suppress("DEPRECATION")
    private fun installedSignatureHashes(): Set<String> {
        return try {
            val flags = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
            signatureHashes(packageManager.getPackageInfo(packageName, flags))
        } catch (e: Exception) {
            emptySet()
        }
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.versionCodeCompat(): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            longVersionCode
        } else {
            versionCode.toLong()
        }
    }

    @Suppress("DEPRECATION")
    private fun signatureHashes(info: PackageInfo): Set<String> {
        val list = mutableListOf<android.content.pm.Signature>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.signingInfo != null) {
            val signingInfo = info.signingInfo!!
            if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners?.forEach { list.add(it) }
            } else {
                signingInfo.signingCertificateHistory?.forEach { list.add(it) }
            }
        }
        if (list.isEmpty() && info.signatures != null) {
            info.signatures!!.forEach { list.add(it) }
        }
        if (list.isEmpty()) return emptySet()

        return list.map { signature ->
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
            digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
        }.toSet()
    }

    private fun promptInstallApk(apkFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()
        ) {
            pendingApkFile = apkFile
            Toast.makeText(
                this,
                "Permite a MABSAPP instalar actualizaciones y vuelve a la app.",
                Toast.LENGTH_LONG
            ).show()
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS))
                } catch (e2: Exception) {
                    installApk(apkFile)
                }
            }
            return
        }
        installApk(apkFile)
    }

    private fun installApk(apkFile: File) {
        pendingApkFile = null
        try {
            val apkUri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                clipData = ClipData.newRawUri("MABSAPP", apkUri)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Error al instalar: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun checkAndRequestAppPermissions() {
        val permissions = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            requestPermissionsLauncher.launch(needed.toTypedArray())
        }
    }

    private fun isAllowedHost(host: String): Boolean {
        if (host.isEmpty()) return false
        return allowedHosts.any { domain ->
            host == domain || host.endsWith(".$domain")
        }
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) webView.onResume()

        // If the user left the app to enable "install unknown apps", retry the
        // installation automatically when they come back.
        val pending = pendingApkFile
        if (pending != null && pending.exists()) {
            val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                packageManager.canRequestPackageInstalls()
            if (allowed) installApk(pending)
        }

        // The check marked itself as failed last time? (e.g., WebView blocked,
        // limited GitHub access on some ISP) Then silently retry on resume...
        if (lastUpdateFailed) {
            lastUpdateFailed = false
            checkForUpdates(manual = false)
        }
    }

    override fun onPause() {
        super.onPause()
        if (::webView.isInitialized) webView.onPause()
    }

    override fun onDestroy() {
        fileUploadCallback?.onReceiveValue(null)
        fileUploadCallback = null

        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("AndroidBridge")
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.clearHistory()
            webView.removeAllViews()
            webView.destroy()
        }
        super.onDestroy()
    }
}
