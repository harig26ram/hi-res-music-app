package tf.monochrome.music

import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

object NetworkHelper {

    private val MIME_HTML = "text/html"

    fun injectHooks(request: WebResourceRequest): WebResourceResponse? {
        return try {
            val conn = (URL(request.url.toString()).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 10000
                instanceFollowRedirects = true
            }
            conn.setRequestProperty("User-Agent", Constants.PROXY_UA)
            CookieManager.getInstance().getCookie(request.url.toString())?.let { conn.setRequestProperty("Cookie", it) }
            request.requestHeaders?.forEach { (k, v) ->
                if (!k.equals("User-Agent", ignoreCase = true) && !k.equals("Cookie", ignoreCase = true))
                    conn.setRequestProperty(k, v)
            }

            if (conn.responseCode >= 400) return null
            val ct = conn.contentType ?: "text/html"
            val charset = try {
                Charset.forName(ct.substringAfter("charset=", "").trim().ifBlank { "UTF-8" })
            } catch (_: Exception) { Charsets.UTF_8 }
            val originalHtml = conn.inputStream.bufferedReader(charset).use { it.readText() }

            val script = "<script>${Constants.JS_HOOKS}</script>"
            val patched = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(originalHtml)?.let { m ->
                originalHtml.substring(0, m.range.last + 1) + script + originalHtml.substring(m.range.last + 1)
            } ?: (script + originalHtml)

            val respHeaders = LinkedHashMap<String, String>()
            conn.headerFields.forEach { (k, v) ->
                if (k != null && !k.equals("Content-Security-Policy", ignoreCase = true) &&
                    !k.equals("X-Frame-Options", ignoreCase = true) &&
                    !k.equals("Content-Length", ignoreCase = true) &&
                    !k.equals("Transfer-Encoding", ignoreCase = true) &&
                    !k.equals("Content-Encoding", ignoreCase = true) &&
                    !k.equals("Vary", ignoreCase = true) &&
                    !k.equals("Set-Cookie", ignoreCase = true))
                    respHeaders[k] = v.joinToString(", ")
            }
            WebResourceResponse("text/html", charset.name(), conn.responseCode, "OK", respHeaders, ByteArrayInputStream(patched.toByteArray(charset)))
        } catch (e: Exception) {
            android.util.Log.e("MonochromeNet", "injectHooks error: ${e.message}", e)
            null
        }
    }

    fun corsOkResponse(requestHeaders: Map<String, String>?): WebResourceResponse {
        val headers = mapOf(
            "Access-Control-Allow-Origin" to (requestHeaders?.get("Origin") ?: "*"),
            "Access-Control-Allow-Methods" to "GET, POST, PUT, PATCH, DELETE, OPTIONS",
            "Access-Control-Allow-Headers" to (requestHeaders?.get("Access-Control-Request-Headers") ?: "*"),
            "Access-Control-Allow-Credentials" to "true",
            "Access-Control-Max-Age" to "86400",
        )
        return WebResourceResponse("text/plain", "UTF-8", 204, "No Content", headers, ByteArrayInputStream(ByteArray(0)))
    }

    fun proxyWithCors(request: WebResourceRequest, method: String): WebResourceResponse? {
        return try {
            val url = URL(request.url.toString())
            val host = url.host ?: ""
            val isWorker = host.endsWith(".workers.dev")
            val path = url.path?.lowercase() ?: ""

            var conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15000
                readTimeout = 60000
                instanceFollowRedirects = true
                doInput = true
            }

            request.requestHeaders?.forEach { (k, v) ->
                if (!k.equals("User-Agent", true) && !k.equals("Host", true) &&
                    !k.equals("Origin", true) && !k.equals("Referer", true) &&
                    !k.equals("X-Requested-With", true) && !k.equals("Cookie", true))
                    conn.setRequestProperty(k, v)
            }
            conn.setRequestProperty("User-Agent", Constants.PROXY_UA)
            conn.setRequestProperty("Origin", request.requestHeaders?.get("Origin") ?: "https://$host")
            conn.setRequestProperty("Referer", request.requestHeaders?.get("Referer") ?: "https://$host/")
            conn.setRequestProperty("X-Forwarded-Proto", "https")
            conn.setRequestProperty("Accept", request.requestHeaders?.get("Accept") ?: "*/*")

            request.requestHeaders?.get("Range")?.let {
                conn.setRequestProperty("Range", it)
            }

            CookieManager.getInstance().getCookie(request.url.toString())?.let {
                conn.setRequestProperty("Cookie", it)
            }
            conn.connect()

            var responseCode = conn.responseCode
            var finalConn = conn
            var redirectCount = 0
            while ((responseCode == 301 || responseCode == 302 || responseCode == 307 || responseCode == 308) && redirectCount < 5) {
                val location = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                val redirectUrl = if (location.startsWith("http")) location else "https://$host$location"
                finalConn = (URL(redirectUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = 15000
                    readTimeout = 60000
                    instanceFollowRedirects = true
                    doInput = true
                    setRequestProperty("User-Agent", Constants.PROXY_UA)
                    setRequestProperty("Accept", request.requestHeaders?.get("Accept") ?: "*/*")
                    request.requestHeaders?.get("Range")?.let { setRequestProperty("Range", it) }
                    CookieManager.getInstance().getCookie(request.url.toString())?.let { setRequestProperty("Cookie", it) }
                }
                finalConn.connect()
                responseCode = finalConn.responseCode
                conn = finalConn
                redirectCount++
            }

            var mime = finalConn.contentType?.substringBefore(";")?.trim() ?: "application/octet-stream"
            if (mime == "text/html") {
                if (path.endsWith(".js")) mime = "application/javascript"
                else if (path.endsWith(".css")) mime = "text/css"
                else if (path.endsWith(".mp3")) mime = "audio/mpeg"
                else if (path.endsWith(".flac")) mime = "audio/flac"
                else if (path.endsWith(".wav")) mime = "audio/wav"
                else if (path.endsWith(".m4a")) mime = "audio/mp4"
                else if (path.endsWith(".ogg")) mime = "audio/ogg"
                else if (path.endsWith(".aac")) mime = "audio/aac"
            }

            val audioExtensions = listOf(".mp3", ".flac", ".wav", ".aac", ".ogg", ".opus", ".m4a", ".alac", ".aiff", ".dsf", ".dff", ".wv", ".ape")
            val isAudio = audioExtensions.any { path.endsWith(it) }
            if (isAudio && !mime.startsWith("audio/")) {
                when {
                    path.endsWith(".mp3") -> mime = "audio/mpeg"
                    path.endsWith(".flac") -> mime = "audio/flac"
                    path.endsWith(".wav") -> mime = "audio/wav"
                    path.endsWith(".m4a") -> mime = "audio/mp4"
                    path.endsWith(".aac") -> mime = "audio/aac"
                    path.endsWith(".ogg") || path.endsWith(".opus") -> mime = "audio/ogg"
                    else -> mime = "audio/mpeg"
                }
            }

            val respHeaders = LinkedHashMap<String, String>()
            val restrictedHeaders = setOf(
                "Access-Control-Allow-Origin", "Access-Control-Allow-Credentials",
                "Access-Control-Allow-Methods", "Access-Control-Allow-Headers",
                "Content-Security-Policy", "X-Frame-Options", "Content-Length",
                "Transfer-Encoding", "Content-Encoding", "Vary", "Set-Cookie"
            )
            finalConn.headerFields.forEach { (k, v) ->
                if (k != null && !restrictedHeaders.any { it.equals(k, ignoreCase = true) }) {
                    var value = v.joinToString(", ")
                    if (k.equals("Location", true))
                        value = value.replace("redirect_uri=http%3A%2F%2Fauth.", "redirect_uri=https%3A%2F%2Fauth.")
                    respHeaders[k] = value
                }
            }

            respHeaders["Access-Control-Allow-Origin"] =
                if (isWorker) (request.requestHeaders?.get("Origin") ?: "*")
                else (request.requestHeaders?.get("Origin") ?: "https://$host")
            respHeaders["Access-Control-Allow-Methods"] = "GET, POST, PUT, DELETE, PATCH, OPTIONS"
            respHeaders["Access-Control-Allow-Headers"] = "*"
            respHeaders["Access-Control-Allow-Credentials"] = "true"

            if (isAudio) {
                respHeaders["Accept-Ranges"] = "bytes"
                respHeaders["Cache-Control"] = "no-cache"
            }

            val responseCode = finalConn.responseCode
            val stream = if (responseCode >= 400) finalConn.errorStream ?: ByteArrayInputStream(ByteArray(0))
            else finalConn.inputStream

            WebResourceResponse(mime, null, responseCode, finalConn.responseMessage ?: "OK", respHeaders, stream)
        } catch (e: Exception) {
            android.util.Log.e("MonochromeNet", "proxyWithCors error: ${e.message}", e)
            null
        }
    }
}
