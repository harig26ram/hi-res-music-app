package tf.monochrome.music

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import tf.monochrome.music.Constants.ACTION_UPDATE_STATE
import tf.monochrome.music.Constants.EXTRA_IS_PLAYING
import tf.monochrome.music.Constants.MIME_FLAC
import tf.monochrome.music.Constants.MIME_MPEG
import tf.monochrome.music.Constants.MIME_OCTET_STREAM
import tf.monochrome.music.MusicService.Companion.ACTION_UPDATE_TRACK
import tf.monochrome.music.MusicService.Companion.EXTRA_TRACK_NAME
import java.io.File
import java.io.FileOutputStream

class JsBlobReceiver(private val context: Context) {

    @Suppress("unused")
    @JavascriptInterface
    fun onBlobData(dataUrl: String, fileName: String, mimeType: String) {
        Thread {
            try {
                val base64 = dataUrl.substringAfter(",", "")
                if (base64.isEmpty()) { ToastHelper.showToast(context, context.getString(R.string.download_failed, "no data")); return@Thread }

                val bytes = Base64.decode(base64, Base64.DEFAULT)
                val resolvedMime = mimeType.takeIf { (it.isNotBlank()) && (it != MIME_OCTET_STREAM) }
                    ?: dataUrl.substringAfter("data:").substringBefore(";").ifBlank { MIME_OCTET_STREAM }

                val safeName = ensureExtension(fileName.trim().ifBlank { "download" }, resolvedMime)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    saveViaMediaStore(bytes, safeName, resolvedMime)
                } else {
                    saveViaFileSystem(bytes, safeName)
                }
            } catch (e: Exception) {
                ToastHelper.showToast(context, context.getString(R.string.download_failed, e.message))
            }
        }.start()
    }

    @JavascriptInterface
    fun onBlobError(message: String) {
        ToastHelper.showToast(context, context.getString(R.string.download_failed, message))
    }

    @Suppress("unused")
    @JavascriptInterface
    fun onMetadataChanged(title: String, artist: String, artUrl: String, localUri: String = "") {
        val ctx = context.applicationContext
        val intent = Intent(ctx, MusicService::class.java).apply {
            action = ACTION_UPDATE_TRACK
            putExtra(EXTRA_TRACK_NAME, title.trim())
            putExtra("artist", artist.trim())
            putExtra("art_url", artUrl.trim())
            if (localUri.isNotBlank()) putExtra("local_uri", localUri.trim())
        }
        ctx.startForegroundService(intent)
    }

    @Suppress("unused")
    @JavascriptInterface
    fun onPlaybackStateChanged(isPlaying: Boolean) {
        val intent = Intent(context.applicationContext, MusicService::class.java).apply {
            action = ACTION_UPDATE_STATE
            putExtra(EXTRA_IS_PLAYING, isPlaying)
        }
        context.startService(intent)
    }

    @Suppress("unused")
    @JavascriptInterface
    fun onPlaybackError(message: String) {
        android.util.Log.e("Monochrome", "Playback error: $message")
        ToastHelper.showToast(context, "Playback error: $message")
    }

    @Suppress("unused")
    @JavascriptInterface
    fun requestFolderPicker(callbackId: String) {
        (context as? MainActivity)?.launchFolderPicker(callbackId)
    }

    @Suppress("unused")
    @JavascriptInterface
    fun requestFileContent(fileUriString: String, callbackId: String) {
        Thread {
            try {
                val uri = fileUriString.toUri()
                var mime = context.contentResolver.getType(uri) ?: MIME_MPEG
                if ((mime == MIME_OCTET_STREAM) && (uri.path?.endsWith(".flac") == true)) mime = MIME_FLAC

                val inputStream = try {
                    context.contentResolver.openInputStream(uri)
                } catch (e: Exception) {
                    if (fileUriString.contains("/tree/")) {
                        val docId = DocumentsContract.getDocumentId(uri)
                        val authority = uri.authority
                        if (authority != null) {
                            context.contentResolver.openInputStream(DocumentsContract.buildDocumentUri(authority, docId))
                        } else throw e
                    } else throw e
                }

                val bytes = inputStream?.use { it.readBytes() } ?: throw Exception("Cannot open file")
                val safeB64 = Base64.encodeToString(bytes, Base64.NO_WRAP).replace("\\", "\\\\").replace("'", "\\'")
                val safeMime = mime.replace("'", "\\'")

                (context as? MainActivity)?.evaluateJs("var cb=window['__monochromeFile_$callbackId']; if(cb){cb('$safeB64','$safeMime');}")
            } catch (_: Exception) {
                (context as? MainActivity)?.evaluateJs("var cb=window['__monochromeFile_$callbackId']; if(cb){cb(null,null);}")
            }
        }.start()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveViaMediaStore(bytes: ByteArray, fileName: String, mimeType: String) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw Exception("MediaStore insert failed")
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw Exception("Cannot open output stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            ToastHelper.showToast(context, context.getString(R.string.saved_file, fileName))
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    private fun saveViaFileSystem(bytes: ByteArray, fileName: String) {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists()) dir.mkdirs()
        val target = uniqueFile(dir, fileName)
        FileOutputStream(target).use { it.write(bytes) }
        ToastHelper.showToast(context, context.getString(R.string.saved_file, target.name))
    }

    private fun ensureExtension(name: String, mimeType: String): String {
        val knownExts = setOf("flac","wav","mp3","aac","ogg","opus","m4a","alac","aiff","dsf","dff","wv","ape","zip")
        if (name.substringAfterLast('.', "").lowercase() in knownExts) return name
        val ext = when {
            mimeType.contains("flac", ignoreCase = true) -> "flac"
            mimeType.contains("wav", ignoreCase = true) -> "wav"
            mimeType.contains("mpeg", ignoreCase = true) || mimeType.contains("mp3", ignoreCase = true) -> "mp3"
            mimeType.contains("aac", ignoreCase = true) -> "aac"
            mimeType.contains("ogg", ignoreCase = true) -> "ogg"
            mimeType.contains("opus", ignoreCase = true) -> "opus"
            mimeType.contains("m4a", ignoreCase = true) -> "m4a"
            mimeType.contains("zip", ignoreCase = true) -> "zip"
            else -> "bin"
        }
        return "$name.$ext"
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "").let { if (it.isNotEmpty()) ".$it" else "" }
        var i = 1
        while (f.exists()) { f = File(dir, "$base($i)$ext"); i++ }
        return f
    }
}
