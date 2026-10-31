package com.betterlife.liftlog

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject

/**
 * Liftlog's web app (bundled in assets/www) running in a WebView, plus a small native bridge
 * ([Bridge]) for things a web page can't do well on Android: background sync to Habits,
 * rest-timer notifications with the app closed, and saving/opening backup files.
 */
class MainActivity : ComponentActivity() {

    companion object {
        /** True while Liftlog is on screen — the rest-over alarm only notifies when it isn't. */
        @Volatile
        var foreground = false

        const val EXTRA_HASH = "hash"
        const val HOST = "appassets.androidplatform.net"
        const val START_URL = "https://$HOST/assets/www/index.html"
    }

    lateinit var web: WebView
        private set

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingSaveText: String? = null

    private val pickFile = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        fileCallback = null
    }

    private val createFile = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val text = pendingSaveText
        pendingSaveText = null
        val uri = r.data?.data
        if (r.resultCode != RESULT_OK || uri == null || text == null) return@registerForActivityResult
        try {
            contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Toast.makeText(this, "Couldn't save: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    private var pickTarget: String? = null

    private val openFile = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val id = pickTarget
        pickTarget = null
        val uri = r.data?.data
        if (r.resultCode != RESULT_OK || uri == null || id == null) return@registerForActivityResult
        try {
            var name = "file"
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0) ?: name
            }
            val type = contentResolver.getType(uri) ?: ""
            val text = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: error("empty")
            js("window.__liftlogPicked && window.__liftlogPicked(${JSONObject.quote(id)}, ${JSONObject.quote(name)}, ${JSONObject.quote(type)}, ${JSONObject.quote(text)})")
        } catch (t: Throwable) {
            Toast.makeText(this, "Couldn't read that file", Toast.LENGTH_LONG).show()
        }
    }

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        js("window.__liftlogResume && window.__liftlogResume()")
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RestTimer.createChannels(this)

        // Draw behind the system bars and pad the WebView by their size ourselves (Android 15 forces
        // edge-to-edge anyway); the padding area shows Liftlog's background colour.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF050708.toInt()) }
        web = WebView(this).apply {
            setBackgroundColor(0xFF050708.toInt())
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        root.addView(web)
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime(),
            )
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsetsCompat.CONSUMED
        }

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            allowFileAccess = false
            // Needed for files chosen through <input type=file> (content:// URIs).
            allowContentAccess = true
            setSupportZoom(false)
            builtInZoomControls = false
            mediaPlaybackRequiresUserGesture = false
        }
        web.addJavascriptInterface(Bridge(this), "LiftlogAndroid")

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.host == HOST) return false
                openExternal(u)
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    val intent = params.createIntent().apply {
                        // Backups are .json and imports .csv; some file managers label them oddly, so allow any.
                        type = "*/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }
                    pickFile.launch(intent)
                    true
                } catch (t: Throwable) {
                    fileCallback = null
                    false
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })

        val startHash = intent?.getStringExtra(EXTRA_HASH)?.takeIf { it.matches(Regex("[a-z]{2,12}")) } ?: "today"
        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        if (web.url == null) web.loadUrl("$START_URL#$startHash")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Widget taps: jump to Today or the workout log.
        val hash = intent.getStringExtra(EXTRA_HASH)?.takeIf { it.matches(Regex("[a-z]{2,12}")) } ?: return
        js(if (hash == "log") "window.__liftlogOpenLog && window.__liftlogOpenLog()" else "location.hash = ${JSONObject.quote("#$hash")}")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        web.onResume()
        RestTimer.hideOngoing(this)
        js("window.__liftlogResume && window.__liftlogResume()")
    }

    override fun onPause() {
        foreground = false
        RestTimer.showOngoing(this)
        web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    fun js(code: String) = runOnUiThread { if (::web.isInitialized) web.evaluateJavascript(code, null) }

    fun saveFile(name: String, mime: String, text: String) {
        pendingSaveText = text
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime.ifBlank { "application/octet-stream" }
            putExtra(Intent.EXTRA_TITLE, name)
        }
        try {
            createFile.launch(intent)
        } catch (t: Throwable) {
            pendingSaveText = null
            Toast.makeText(this, "No app available to save files", Toast.LENGTH_LONG).show()
        }
    }

    fun pickFile(inputId: String, accept: String) {
        pickTarget = inputId
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            // Backups (.json) and CSV exports often have generic MIME types, so allow any file.
            type = "*/*"
        }
        try {
            openFile.launch(intent)
        } catch (t: Throwable) {
            pickTarget = null
            Toast.makeText(this, "No file picker available", Toast.LENGTH_LONG).show()
        }
    }

    fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else startActivity(
            Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName),
        )
    }

    fun keepAwake(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    fun openExternal(u: Uri) {
        try {
            val intent = if (u.scheme == "intent") Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                component = null
                selector = null
            } else Intent(Intent.ACTION_VIEW, u)
            startActivity(intent)
        } catch (t: Throwable) {
            Toast.makeText(this, "Couldn't open that link", Toast.LENGTH_SHORT).show()
        }
    }
}
