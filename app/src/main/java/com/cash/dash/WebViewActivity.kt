@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
package com.cash.dash

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

class WebViewActivity : ThemedActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar

    companion object {
        private const val DEFAULT_URL = "https://www.cashdash.co.in"

        /**
         * JavaScript is enabled in this WebView, so it must only ever load our own
         * origins. Previously it loaded whatever "url" extra it was handed.
         */
        private val ALLOWED_HOSTS = setOf(
            "cashdash.co.in",
            "www.cashdash.co.in",
            // adminReply runs in both regions during the asia-south1 migration.
            // The us-central1 host must stay listed until every reply link emitted
            // before the switch has passed its 7-day TTL, or older emailed links
            // stop opening in-app. See MIGRATION_ASIA_SOUTH1.md step 2.
            "adminreply-khhfw7mtba-uc.a.run.app",
            "asia-south1-cashdash-8cd8b.cloudfunctions.net"
        )

        fun isAllowed(url: String?): Boolean {
            if (url.isNullOrBlank()) return false
            val uri = android.net.Uri.parse(url)
            if (!uri.scheme.equals("https", ignoreCase = true)) return false
            val host = uri.host?.lowercase() ?: return false
            return ALLOWED_HOSTS.contains(host)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webview)

        val titleStr = intent.getStringExtra("title") ?: "CashDash"
        val requestedUrl = intent.getStringExtra("url")
        val url = if (isAllowed(requestedUrl)) requestedUrl!! else DEFAULT_URL

        findViewById<TextView>(R.id.tvHeaderTitle).text = titleStr
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        progressBar = findViewById(R.id.progressBar)
        webView = findViewById(R.id.webView)

        // Configure WebView
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.javaScriptCanOpenWindowsAutomatically = false

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url?.toString() ?: ""
                if (uri.startsWith("mailto:")) {
                    return true // Disable email clicks
                }
                // Keep in-WebView navigation on our own origins. Anything else is
                // handed to the browser, where it runs without our app context.
                if (!isAllowed(uri)) {
                    try {
                        startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, request!!.url))
                    } catch (e: Exception) {
                        Toast.makeText(this@WebViewActivity, "Could not open link.", Toast.LENGTH_SHORT).show()
                    }
                    return true
                }
                return super.shouldOverrideUrlLoading(view, request)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                progressBar.visibility = View.GONE
                Toast.makeText(this@WebViewActivity, "Failed to load page.", Toast.LENGTH_SHORT).show()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                if (newProgress == 100) {
                    progressBar.visibility = View.GONE
                }
            }
        }

        webView.loadUrl(url)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }
}
