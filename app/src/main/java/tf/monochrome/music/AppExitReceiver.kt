package tf.monochrome.music

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AppExitReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_EXIT = "tf.monochrome.music.EXIT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_EXIT) {
            context.stopService(Intent(context, MusicService::class.java))
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }
}
