package tf.monochrome.music

import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import android.webkit.CookieManager
import androidx.core.net.toUri

object DownloadHelper {

    fun startHttpDownload(context: Context, url: String, userAgent: String, fileName: String, mimeType: String) {
        val dm = (context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager) ?: return
        try {
            val req = DownloadManager.Request(url.toUri()).apply {
                setMimeType(mimeType)
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                addRequestHeader("User-Agent", userAgent)
                setTitle(fileName)
                setDescription(context.getString(R.string.downloading_file, fileName))
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            dm.enqueue(req)
            ToastHelper.showToast(context, context.getString(R.string.downloading_file, fileName))
        } catch (e: Exception) {
            ToastHelper.showToast(context, context.getString(R.string.download_failed, e.message))
        }
    }
}
