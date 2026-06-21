package tf.monochrome.music

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

object ToastHelper {
    fun showToast(context: Context, msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
