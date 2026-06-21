package tf.monochrome.music

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import tf.monochrome.music.Constants.MIME_MPEG
import org.json.JSONArray
import org.json.JSONObject

object FileSystemHelper {

    fun enumerateAudioFiles(contentResolver: ContentResolver, treeUri: Uri): List<Pair<Uri, String>> {
        val results = mutableListOf<Pair<Uri, String>>()
        val audioExts = setOf("flac", "wav", "mp3", "aac", "ogg", "opus", "m4a", "alac", "aiff", "dsf", "dff", "wv", "ape")

        fun scanDir(docId: String) {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null, null, null,
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val iName = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val iMime = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (c.moveToNext()) {
                    val childId = c.getString(iId) ?: continue
                    val name = c.getString(iName) ?: continue
                    val mime = c.getString(iMime) ?: ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        scanDir(childId)
                    } else if ((mime.startsWith("audio/")) || (name.substringAfterLast('.', "").lowercase() in audioExts)) {
                        results.add(Pair(DocumentsContract.buildDocumentUriUsingTree(treeUri, childId), name))
                    }
                }
            }
        }

        try {
            scanDir(DocumentsContract.getTreeDocumentId(treeUri))
        } catch (_: Exception) { }
        return results
    }

    fun buildJson(contentResolver: ContentResolver, files: List<Pair<Uri, String>>): String {
        val array = JSONArray()
        files.forEach { (uri, name) ->
            val obj = JSONObject()
            obj.put("name", name)
            obj.put("uri", uri.toString())
            obj.put("mimeType", contentResolver.getType(uri) ?: MIME_MPEG)
            array.put(obj)
        }
        return array.toString()
    }
}
