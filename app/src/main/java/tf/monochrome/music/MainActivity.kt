package tf.monochrome.music

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.webkit.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.isGone
import tf.monochrome.music.Constants.ACTION_NEXT
import tf.monochrome.music.Constants.ACTION_PAUSE
import tf.monochrome.music.Constants.ACTION_PLAY
import tf.monochrome.music.Constants.ACTION_PREVIOUS
import tf.monochrome.music.Constants.APPLE_MUSIC_CSS
import tf.monochrome.music.Constants.DEFAULT_ORIGIN
import tf.monochrome.music.Constants.JS_HOOKS
import tf.monochrome.music.Constants.MIME_MPEG
import tf.monochrome.music.Constants.MIME_OCTET_STREAM
import tf.monochrome.music.Constants.PROXY_UA
import tf.monochrome.music.Constants.SITE_URL
import tf.monochrome.music.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    lateinit var webView: WebView
    private lateinit var binding: ActivityMainBinding

    private var pendingBlobUrl: String? = null
    private var pendingBlobMime: String? = null
    private var pendingBlobName: String? = null
    private var pendingFolderCbId: String? = null

    private var pullStartY = 0f
    private var pullReloadPosted = false
    private val pullHandler = Handler(Looper.getMainLooper())
    private val pullThresholdPx get() = 80f * resources.displayMetrics.density
    private val holdDurationMs = 3000L
    private var pullStartedInZone = false

    private val reqStoragePerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            pendingBlobUrl?.let { url ->
                triggerBlobRead(url, pendingBlobMime ?: MIME_OCTET_STREAM, pendingBlobName ?: "download")
            }
        } else {
            ToastHelper.showToast(this, getString(R.string.storage_permission_denied))
        }
        pendingBlobUrl = null
    }

    private val reqNotifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val reqMediaPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val id = pendingFolderCbId ?: return@registerForActivityResult
        if (uri == null) {
            webView.evaluateJavascript("window.__mcPendingFolder = null; window.__mcResolveFolder('$id');", null)
            pendingFolderCbId = null
            return@registerForActivityResult
        }

        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) { }

        pendingFolderCbId = null

        Thread {
            val files = FileSystemHelper.enumerateAudioFiles(contentResolver, uri)
            runOnUiThread {
                if (files.isEmpty()) {
                    ToastHelper.showToast(this, getString(R.string.no_audio_files_found))
                    webView.evaluateJavascript("window.__mcPendingFolder = null; window.__mcResolveFolder('$id');", null)
                } else {
                    val json = FileSystemHelper.buildJson(contentResolver, files)
                    webView.evaluateJavascript("window.__mcPendingFolder=$json; window.__mcResolveFolder('$id');", null)
                    ToastHelper.showToast(this, resources.getQuantityString(R.plurals.loaded_tracks, files.size, files.size))
                }
            }
        }.start()
    }

    private val mediaControlReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val script = when (intent.action) {
                ACTION_PAUSE -> "window.__mcPause();"
                ACTION_PLAY -> "window.__mcPlay();"
                ACTION_NEXT -> "window.__mcNext();"
                ACTION_PREVIOUS -> "window.__mcPrev();"
                else -> null
            }
            script?.let { webView.evaluateJavascript(it, null) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        webView = binding.webView
        setupWebView()
        setupDownloadListener()
        requestRuntimePermissions()
        setupOnBackPressed()
        registerMediaControlReceiver()

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(SITE_URL)
        }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        webView.resumeTimers()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        try { unregisterReceiver(mediaControlReceiver) } catch (_: Exception) { }
        webView.destroy()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_MODERATE) {
            try { webView.clearCache(false) } catch (_: Exception) { }
        }
    }

    private fun registerMediaControlReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_PLAY)
            addAction(ACTION_PAUSE)
            addAction(ACTION_NEXT)
            addAction(ACTION_PREVIOUS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mediaControlReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(mediaControlReceiver, filter)
        }
    }

    private fun requestRuntimePermissions() {
        if ((Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) &&
            (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
        ) reqStoragePerm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                reqNotifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED)
                reqMediaPerm.launch(Manifest.permission.READ_MEDIA_AUDIO)
        }
    }

    @Suppress("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            userAgentString = PROXY_UA
            safeBrowsingEnabled = false
            cacheMode = WebSettings.LOAD_DEFAULT
            @Suppress("DEPRECATION")
            databaseEnabled = true
            setSupportMultipleWindows(false)
            blockNetworkImage = false
            loadsImagesAutomatically = true
            defaultTextEncodingName = "UTF-8"
        }

        webView.webViewClient = createWebViewClient()
        webView.webChromeClient = createWebChromeClient()
        webView.addJavascriptInterface(JsBlobReceiver(this), "MonochromeApp")
        webView.overScrollMode = WebView.OVER_SCROLL_NEVER

        setupPullToReload()
    }

    private fun createWebViewClient() = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            val oauthRedirectHttp = "redirect_uri=http%3A%2F%2Fauth."
            val oauthRedirectHttps = "redirect_uri=https%3A%2F%2Fauth."

            if ((url.contains("discord.com/oauth2/authorize") || url.contains("accounts.google.com")) && url.contains(oauthRedirectHttp)) {
                view.loadUrl(url.replace(oauthRedirectHttp, oauthRedirectHttps))
                return true
            }

            if (url.startsWith("http://") && (url.contains("monochrome.tf") || url.contains("lossless.wtf") || url.contains("samidy.com"))) {
                view.loadUrl(url.replace("http://", "https://"))
                return true
            }
            return false
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            binding.pullIndicator.visibility = View.GONE
            CookieManager.getInstance().flush()

            if (binding.splashScreen.visibility == View.VISIBLE) {
                binding.splashCredit.animate().alpha(0f).setDuration(150).start()
                binding.splashScreen.animate()
                    .alpha(0f)
                    .setDuration(200)
                    .withEndAction {
                        binding.splashScreen.visibility = View.GONE
                        binding.splashCredit.visibility = View.GONE
                    }
                    .start()
            }

            view.evaluateJavascript(JS_HOOKS + APPLE_MUSIC_CSS, null)
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url
            val host = url.host ?: return null
            val method = (request.method ?: "GET").uppercase()

            val isWorker = host.endsWith(".workers.dev")
            val isLocalFile = host == "local-file.monochrome.tf"
            val isMainDomain = host == "monochrome.tf" || host == "monochrome.samidy.com" ||
                    host == "lossless.wtf" || host == "localhost" || host == "127.0.0.1"
            val isMonochrome = isMainDomain || host.endsWith(".monochrome.tf") || host.startsWith("auth.")

            return when {
                isLocalFile -> serveLocalFile(request)
                (isMonochrome || isWorker) && (method == "GET" || method == "OPTIONS") -> {
                    if (method == "OPTIONS") NetworkHelper.corsOkResponse(request.requestHeaders)
                    else NetworkHelper.proxyWithCors(request, method)
                }
                host.contains("discord.com") || host.contains("appleid.apple.com") ||
                        host.contains("google.com") || host.contains("accounts.google") ||
                        host.contains("gstatic.com") || host.contains("googleusercontent.com") ||
                        host.contains("play.google.com") -> null
                else -> null
            }
        }
    }

    private fun createWebChromeClient() = object : WebChromeClient() {
        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
            return true
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupPullToReload() {
        webView.setOnTouchListener { v, event ->
            val atTop = !webView.canScrollVertically(-1)
            val screenHeight = resources.displayMetrics.heightPixels
            val topZone = screenHeight * 0.2f

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    pullStartY = event.rawY
                    pullReloadPosted = false
                    pullStartedInZone = event.rawY <= topZone
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!pullStartedInZone) return@setOnTouchListener false
                    val dy = event.rawY - pullStartY
                    if (atTop && dy > pullThresholdPx && !pullReloadPosted) {
                        startPullReloadTimer()
                        v.performClick()
                        return@setOnTouchListener true
                    } else if (dy < 0 || (pullReloadPosted && dy < pullThresholdPx)) {
                        cancelPullReload()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    cancelPullReload()
                    pullStartedInZone = false
                }
            }
            false
        }
    }

    private fun startPullReloadTimer() {
        pullReloadPosted = true
        binding.pullIndicator.apply {
            visibility = View.VISIBLE
            text = getString(R.string.pull_to_reload_hold)
        }

        var remaining = 3
        val ticker = object : Runnable {
            override fun run() {
                remaining--
                if (remaining > 0 && pullReloadPosted) {
                    binding.pullIndicator.text = getString(R.string.pull_to_reload_reloading_in, remaining)
                    pullHandler.postDelayed(this, 1000)
                }
            }
        }
        pullHandler.postDelayed(ticker, 1000)
        pullHandler.postDelayed({ if (pullReloadPosted) triggerReload() }, holdDurationMs)
    }

    private fun triggerReload() {
        if (binding.pullIndicator.isGone && !pullReloadPosted) return
        binding.pullIndicator.visibility = View.GONE
        pullReloadPosted = false
        webView.reload()
        ToastHelper.showToast(this, getString(R.string.reloading))
    }

    private fun cancelPullReload() {
        pullHandler.removeCallbacksAndMessages(null)
        pullReloadPosted = false
        binding.pullIndicator.visibility = View.GONE
    }

    private fun setupOnBackPressed() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(enabled = true) {
                override fun handleOnBackPressed() {
                    if (webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        moveTaskToBack(true)
                    }
                }
            }
        )
    }

    fun triggerBlobRead(blobUrl: String, mimeType: String, fileName: String) {
        val safeUrl = blobUrl.replace("\\", "\\\\").replace("'", "\\'")
        val safeName = fileName.replace("\\", "\\\\").replace("'", "\\'")
        val safeMime = mimeType.replace("\\", "\\\\").replace("'", "\\'")
        runOnUiThread {
            webView.evaluateJavascript("window.__mcGetBlob('$safeUrl','$safeName','$safeMime');", null)
        }
    }

    fun launchFolderPicker(callbackId: String) {
        pendingFolderCbId = callbackId
        runOnUiThread { folderPicker.launch(null) }
    }

    fun evaluateJs(script: String) {
        if (!isFinishing && !isDestroyed) {
            runOnUiThread { webView.evaluateJavascript(script, null) }
        }
    }

    private fun serveLocalFile(request: WebResourceRequest): WebResourceResponse? {
        val uriStr = request.url.getQueryParameter("uri") ?: return null
        return try {
            val uri = uriStr.toUri()
            var mime = contentResolver.getType(uri) ?: Constants.MIME_MPEG
            if (mime == Constants.MIME_OCTET_STREAM && uri.path?.endsWith(".flac") == true) mime = Constants.MIME_FLAC

            val pfd = contentResolver.openFileDescriptor(uri, "r") ?: return null
            val totalLength = pfd.statSize
            pfd.close()

            val rangeHeader = request.requestHeaders["Range"]
            val headers = mutableMapOf(
                "Access-Control-Allow-Origin" to (request.requestHeaders["Origin"] ?: Constants.DEFAULT_ORIGIN),
                "Access-Control-Allow-Methods" to "GET",
                "Access-Control-Allow-Credentials" to "true",
                "Accept-Ranges" to "bytes",
                "Cache-Control" to "no-cache",
            )

            if (rangeHeader?.startsWith("bytes=") == true) {
                val ranges = rangeHeader.substring(6).split("-")
                val start = ranges[0].toLong()
                val end = if (ranges.size > 1 && ranges[1].isNotBlank()) ranges[1].toLong() else totalLength - 1
                if (start >= totalLength) return WebResourceResponse(mime, null, 416, "Range Not Satisfiable", headers, null)

                val rangeLength = (end - start + 1).coerceAtMost(totalLength - start)
                val stream = contentResolver.openInputStream(uri)?.apply { if (start > 0) skip(start) } ?: return null
                headers["Content-Range"] = "bytes $start-$end/$totalLength"
                headers["Content-Length"] = rangeLength.toString()
                WebResourceResponse(mime, null, 206, "Partial Content", headers, stream)
            } else {
                headers["Content-Length"] = totalLength.toString()
                WebResourceResponse(mime, null, 200, "OK", headers, contentResolver.openInputStream(uri) ?: return null)
            }
        } catch (e: Exception) {
            android.util.Log.e("Monochrome", "Error serving local file", e)
            null
        }
    }

    private fun setupDownloadListener() {
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            when {
                url.startsWith("blob:") -> handleBlobDownload(url, mimeType, fileName)
                url.startsWith("http://") || url.startsWith("https://") ->
                    DownloadHelper.startHttpDownload(this, url, userAgent, fileName, mimeType)
                else -> ToastHelper.showToast(this, getString(R.string.unsupported_download_type))
            }
        }
    }

    private fun handleBlobDownload(blobUrl: String, mimeType: String, fileName: String) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingBlobUrl = blobUrl
            pendingBlobMime = mimeType
            pendingBlobName = fileName
            reqStoragePerm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            triggerBlobRead(blobUrl, mimeType, fileName)
        }
    }
}
