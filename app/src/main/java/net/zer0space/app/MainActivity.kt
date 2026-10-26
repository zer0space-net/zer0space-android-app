package net.zer0space.app

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    private lateinit var root: FrameLayout
    private lateinit var resolverHost: FrameLayout
    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var offline: View

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mediaRules = MediaRules()
    private lateinit var bridge: Bridge

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var insets: androidx.core.graphics.Insets = androidx.core.graphics.Insets.NONE

    private val pickFile = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data))
        fileCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        buildLayout()
        bridge = Bridge(this, scope, mediaRules, PageResolver(resolverHost))
        configureWebView()
        installBridge()

        MediaHub.actionSink = { action, seekTime ->
            val data = JSONObject().put("action", action)
            if (seekTime != null) data.put("seekTime", seekTime)
            bridge.emit("media.action", data)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> webView.webChromeClient?.onHideCustomView()
                    webView.canGoBack() -> webView.goBack()
                    // Keep the task (and any music) alive instead of finishing.
                    else -> moveTaskToBack(true)
                }
            }
        })

        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(AppConfig.startUrl)
        }
    }

    // --- layout ---------------------------------------------------------------

    private fun buildLayout() {
        root = FrameLayout(this).apply { setBackgroundColor(getColor(R.color.zs_bg)) }
        // The hidden resolver pages live here, behind the visible WebView.
        resolverHost = FrameLayout(this)
        root.addView(resolverHost, match())

        webView = WebView(this).apply { setBackgroundColor(getColor(R.color.zs_bg)) }
        root.addView(webView, match())

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.zs_accent))
            visibility = View.GONE
        }
        root.addView(progress, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3), Gravity.TOP))

        offline = buildOfflineView()
        root.addView(offline, match())

        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, windowInsets ->
            insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime(),
            )
            applyInsets()
            WindowInsetsCompat.CONSUMED
        }
        WindowCompat.getInsetsController(window, root).isAppearanceLightStatusBars = false
        WindowCompat.getInsetsController(window, root).isAppearanceLightNavigationBars = false
    }

    private fun applyInsets() {
        if (customView != null) {
            root.setPadding(0, 0, 0, 0)
        } else {
            root.setPadding(insets.left, insets.top, insets.right, insets.bottom)
        }
    }

    private fun buildOfflineView(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(getColor(R.color.zs_bg))
        setPadding(dp(32), dp(32), dp(32), dp(32))
        visibility = View.GONE
        isClickable = true
        addView(TextView(context).apply {
            text = getString(R.string.offline_title)
            textSize = 20f
            setTextColor(getColor(R.color.zs_text))
            gravity = Gravity.CENTER
        })
        addView(TextView(context).apply {
            text = getString(R.string.offline_body)
            textSize = 15f
            setTextColor(getColor(R.color.zs_text_dim))
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(20))
        })
        addView(Button(context).apply {
            text = getString(R.string.retry)
            setOnClickListener {
                offline.visibility = View.GONE
                webView.reload()
            }
        })
    }

    // --- WebView --------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Music starts the next track from the "ended" event, which is not
            // a user gesture; with the default, the queue would stop after one.
            mediaPlaybackRequiresUserGesture = false
            userAgentString = Http.userAgent
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            setGeolocationEnabled(false)
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        CookieManager.getInstance().setAcceptCookie(true)
        // Movie players are third-party iframes; some need their own cookies.
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Iframes may navigate wherever their players go; only the top
                // level is pinned to zer0space.
                if (!request.isForMainFrame) return false
                val url = request.url.toString()
                if (AppConfig.isAppUrl(url)) return false
                if (request.url.scheme == "https" || request.url.scheme == "http") openExternal(request.url)
                return true
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                mediaRules.intercept(request)

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String?) {
                progress.visibility = View.GONE
                CookieManager.getInstance().flush()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) offline.visibility = View.VISIBLE
            }

            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                // The renderer died (memory pressure). Start over cleanly
                // instead of letting the whole app crash with it.
                recreate()
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                root.addView(view, match())
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                WindowCompat.getInsetsController(window, root).apply {
                    hide(WindowInsetsCompat.Type.systemBars())
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
                applyInsets()
            }

            override fun onHideCustomView() {
                val v = customView ?: return
                root.removeView(v)
                customView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                WindowCompat.getInsetsController(window, root).show(WindowInsetsCompat.Type.systemBars())
                applyInsets()
            }

            // No popups: ad scripts in player iframes love them.
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?) = false

            override fun onPermissionRequest(request: PermissionRequest) = request.deny()

            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams,
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    pickFile.launch(params.createIntent())
                    true
                } catch (_: ActivityNotFoundException) {
                    fileCallback = null
                    false
                }
            }
        }

        // Downloads (the APK link in a browser, mostly) go to the real browser.
        webView.setDownloadListener { url, _, _, _, _ ->
            val uri = Uri.parse(url)
            if (uri.scheme == "https") openExternal(uri)
        }
    }

    private fun installBridge() {
        val supported = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (!supported) {
            // Old WebView: the site still works, sources resolve on the server
            // as in a normal browser.
            Toast.makeText(this, R.string.webview_too_old, Toast.LENGTH_LONG).show()
            return
        }
        val rules = setOf(AppConfig.origin)
        WebViewCompat.addWebMessageListener(webView, "zs_native", rules, bridge)
        val script = assets.open("bridge.js").bufferedReader().use { it.readText() }
            .replace("__ZS_VERSION__", BuildConfig.VERSION_NAME.replace(Regex("[^0-9A-Za-z.+-]"), ""))
        WebViewCompat.addDocumentStartJavaScript(webView, script, rules)
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show()
        }
    }

    // --- lifecycle --------------------------------------------------------------

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    // The WebView is deliberately NOT paused in onPause/onStop: pausing it
    // would stop the music the moment the screen turns off.

    override fun onDestroy() {
        MediaHub.actionSink = null
        if (isFinishing) MediaHub.clear(applicationContext)
        scope.cancel()
        root.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    // --- helpers --------------------------------------------------------------

    private fun match() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
