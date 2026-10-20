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
            checkForUpdates(manual = true)
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

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val settings = webView.settings

        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true

        val desktopUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        settings.userAgentString = desktopUserAgent

        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.textZoom = 100
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.addJavascriptInterface(WebAppInterface(this), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.contains("whatsapp.com") || url.contains("whatsapp.net") || url.contains("facebook.com") || url.contains("fbcdn.net")) {
                    return false
                }
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
                injectSafeColumnUI()
            }

            override fun onReceivedSslError(view: WebView?, handler: android.webkit.SslErrorHandler?, error: android.net.http.SslError?) {
                handler?.proceed()
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
     * Safe Single-Column UI Injection.
     *
     * KEY FIX: Does NOT inject any CSS until WhatsApp Web's React app has fully
     * mounted (#side exists in DOM). Uses visibility/position offscreen trick instead
     * of display:none so React keeps its DOM nodes alive and rendering properly.
     */
    private fun injectSafeColumnUI() {
        val js = """
            (function() {
                if (window.__mabsInjected) return;

                function waitForApp() {
                    var side = document.getElementById('side');
                    if (!side) {
                        setTimeout(waitForApp, 500);
                        return;
                    }
                    window.__mabsInjected = true;
                    initMabs();
                }

                function initMabs() {
                    var meta = document.querySelector('meta[name="viewport"]');
                    if (!meta) {
                        meta = document.createElement('meta');
                        meta.name = 'viewport';
                        document.head.appendChild(meta);
                    }
                    meta.content = 'width=device-width, initial-scale=1.0, maximum-scale=2.5, user-scalable=yes';

                    var style = document.createElement('style');
                    style.id = 'mabs-style';
                    style.textContent = [
                        'html, body, #app { width:100vw!important; height:100%!important; overflow:hidden!important; margin:0!important; padding:0!important; }',
                        'body.mabs-list #main { visibility:hidden!important; position:absolute!important; left:-200vw!important; width:0!important; height:0!important; overflow:hidden!important; }',
                        'body.mabs-list #side { display:flex!important; width:calc(100vw - 52px)!important; max-width:calc(100vw - 52px)!important; flex:1 1 auto!important; }',
                        'body.mabs-chat #side { visibility:hidden!important; position:absolute!important; left:-200vw!important; width:0!important; height:0!important; overflow:hidden!important; }',
                        'body.mabs-chat div[role="navigation"] { visibility:hidden!important; position:absolute!important; left:-200vw!important; width:0!important; height:0!important; }',
                        'body.mabs-chat #main { display:flex!important; position:fixed!important; top:0!important; left:0!important; width:100vw!important; height:100%!important; z-index:9999!important; }',
                        '#mabs-back { display:inline-flex!important; align-items:center!important; justify-content:center!important; width:40px!important; height:40px!important; margin-right:6px!important; cursor:pointer!important; color:#00a884!important; font-size:24px!important; font-weight:bold!important; background:transparent!important; border:none!important; outline:none!important; z-index:100000!important; flex-shrink:0!important; }',
                        '#mabs-back:active { background:rgba(0,168,132,0.2)!important; border-radius:50%!important; }'
                    ].join('\n');
                    document.head.appendChild(style);

                    document.body.classList.add('mabs-list');

                    window.mabsGoBack = function() {
                        document.body.classList.remove('mabs-chat');
                        document.body.classList.add('mabs-list');
                        if (window.AndroidBridge) window.AndroidBridge.onChatStateChanged(false);
                        document.dispatchEvent(new KeyboardEvent('keydown', {bubbles:true, key:'Escape', code:'Escape', keyCode:27, which:27}));
                    };

                    window.mabsGoChat = function() {
                        document.body.classList.remove('mabs-list');
                        document.body.classList.add('mabs-chat');
                        if (window.AndroidBridge) window.AndroidBridge.onChatStateChanged(true);
                        function addBackBtn() {
                            var m = document.getElementById('main');
                            if (!m) return;
                            var h = m.querySelector('header');
                            if (h && !h.querySelector('#mabs-back')) {
                                var b = document.createElement('button');
                                b.id = 'mabs-back';
                                b.textContent = '\u2190';
                                b.onclick = function(e) { e.preventDefault(); e.stopPropagation(); window.mabsGoBack(); };
                                h.insertBefore(b, h.firstChild);
                            }
                        }
                        setTimeout(addBackBtn, 150);
                        setTimeout(addBackBtn, 500);
                        setTimeout(addBackBtn, 1000);
                    };

                    document.addEventListener('click', function(e) {
                        var paneSide = document.getElementById('pane-side');
                        if (paneSide && paneSide.contains(e.target)) {
                            var row = e.target.closest('[role="listitem"]') || e.target.closest('[data-testid="cell-frame-container"]') || e.target.closest('[tabindex="-1"]');
                            if (row) {
                                window.mabsGoChat();
                            }
                        }
                    }, true);

                    var mainEl = document.getElementById('main');
                    if (mainEl) {
                        var obs = new MutationObserver(function() {
                            var hasConvo = mainEl.querySelector('header') && mainEl.querySelector('footer');
                            if (hasConvo && !document.body.classList.contains('mabs-chat')) {
                                window.mabsGoChat();
                            }
                            if (!hasConvo && document.body.classList.contains('mabs-chat')) {
                                window.mabsGoBack();
                            }
                        });
                        obs.observe(mainEl, { childList: true, subtree: true });
                    }
                }

                waitForApp();
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
