package tf.monochrome.music

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.media.MediaBrowserServiceCompat
import androidx.media.session.MediaButtonReceiver
import tf.monochrome.music.Constants.ACTION_NEXT
import tf.monochrome.music.Constants.ACTION_PAUSE
import tf.monochrome.music.Constants.ACTION_PLAY
import tf.monochrome.music.Constants.ACTION_PREVIOUS
import tf.monochrome.music.Constants.ACTION_UPDATE_STATE
import tf.monochrome.music.Constants.EXTRA_IS_PLAYING
import java.net.HttpURLConnection
import java.net.URL

class MusicService : MediaBrowserServiceCompat() {

    companion object {
        const val CHANNEL_ID = "monochrome_playback"
        const val NOTIFICATION_ID = 1001
        const val ACTION_UPDATE_TRACK = "tf.monochrome.music.UPDATE_TRACK"
        const val EXTRA_TRACK_NAME = "track_name"
    }

    private var mediaSession: MediaSessionCompat? = null
    private var currentTrack = ""
    private var currentArtist = "Monochrome"
    private var currentArtUrl = ""
    private var currentLocalUri = ""
    private var currentBitmap: Bitmap? = null
    private var isPlaying = false

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                mediaSession?.controller?.transportControls?.pause()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        setupMediaSession()
        registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MediaButtonReceiver.handleIntent(mediaSession, intent)

        var needsUpdate = false
        when (intent?.action) {
            ACTION_UPDATE_TRACK -> {
                val name = intent.getStringExtra(EXTRA_TRACK_NAME)?.takeIf { it.isNotBlank() } ?: "Monochrome"
                val artist = intent.getStringExtra("artist")?.takeIf { it.isNotBlank() } ?: "Monochrome"
                val artUrl = intent.getStringExtra("art_url") ?: ""
                val localUri = intent.getStringExtra("local_uri") ?: ""

                if ((name != currentTrack) || (artist != currentArtist) || (artUrl != currentArtUrl) || (localUri != currentLocalUri)) {
                    currentTrack = name
                    currentArtist = artist
                    currentArtUrl = artUrl
                    currentLocalUri = localUri
                    needsUpdate = true

                    fetchArt(artUrl, localUri)
                }
            }
            ACTION_UPDATE_STATE -> {
                val playing = intent.getBooleanExtra(EXTRA_IS_PLAYING, false)
                if (playing != isPlaying) {
                    isPlaying = playing
                    needsUpdate = true
                }
            }
        }

        if (needsUpdate || intent?.action == null) {
            updateMetadataAndNotify()
        }

        return START_STICKY
    }

    private fun fetchArt(url: String, localUri: String) {
        Thread {
            try {
                var bitmap: Bitmap? = null

                if (url.startsWith("http")) {
                    try {
                        val conn = URL(url).openConnection() as HttpURLConnection
                        conn.connectTimeout = 5000
                        conn.readTimeout = 5000
                        conn.connect()
                        bitmap = BitmapFactory.decodeStream(conn.inputStream)
                    } catch (e: Exception) {
                        android.util.Log.w("MusicService", "Failed to download art from HTTP: ${e.message}")
                    }
                }

                if (bitmap == null && url.startsWith("content://")) {
                    try {
                        contentResolver.openInputStream(url.toUri())?.use {
                            bitmap = BitmapFactory.decodeStream(it)
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("MusicService", "Failed to load art from content URL: ${e.message}")
                    }
                }

                if (bitmap == null && localUri.isNotBlank()) {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(this, localUri.toUri())
                        val art = retriever.embeddedPicture
                        if (art != null) {
                            bitmap = BitmapFactory.decodeByteArray(art, 0, art.size)
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("MusicService", "MetadataRetriever failed for $localUri: ${e.message}")
                    } finally {
                        try { retriever.release() } catch (_: Exception) {}
                    }
                }

                if (url == currentArtUrl && localUri == currentLocalUri) {
                    currentBitmap = bitmap
                    runOnUiThread { updateMetadataAndNotify() }
                }
            } catch (e: Exception) {
                android.util.Log.e("MusicService", "Art fetch thread failed: ${e.message}")
            }
        }.start()
    }

    private fun runOnUiThread(action: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(action)
    }

    private fun updateMetadataAndNotify() {
        updateMetadata()
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            android.util.Log.e("MusicService", "Failed to start foreground service", e)
        }
    }

    private fun updateMetadata() {
        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTrack)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "Monochrome")

        currentBitmap?.let {
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, it)
        }

        mediaSession?.setMetadata(builder.build())

        val state = PlaybackStateCompat.Builder()
            .setState(
                if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                1.0f,
            )
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            )
            .build()
        mediaSession?.setPlaybackState(state)
    }

    override fun onDestroy() {
        try { unregisterReceiver(noisyReceiver) } catch (_: Exception) { }
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot = BrowserRoot("root", null)

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) = result.sendResult(mutableListOf())

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "Monochrome").apply {
            isActive = true
            setCallback(
                object : MediaSessionCompat.Callback() {
                    override fun onPlay() = sendBroadcast(Intent(ACTION_PLAY).setPackage(packageName))
                    override fun onPause() = sendBroadcast(Intent(ACTION_PAUSE).setPackage(packageName))
                    override fun onSkipToNext() = sendBroadcast(Intent(ACTION_NEXT).setPackage(packageName))
                    override fun onSkipToPrevious() = sendBroadcast(Intent(ACTION_PREVIOUS).setPackage(packageName))
                }
            )
        }
        sessionToken = mediaSession?.sessionToken
    }

    private fun buildNotification(): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val openPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                this.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            flags
        )

        val exitPi = PendingIntent.getBroadcast(
            this, 1,
            Intent(this, AppExitReceiver::class.java).apply {
                action = AppExitReceiver.ACTION_EXIT
            },
            flags
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setLargeIcon(currentBitmap)
            .setContentTitle(currentTrack)
            .setContentText(currentArtist)
            .setContentIntent(openPi)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setOngoing(isPlaying)

        builder.addAction(android.R.drawable.ic_media_previous, "Previous", MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS))
        if (isPlaying) builder.addAction(android.R.drawable.ic_media_pause, "Pause", MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PAUSE))
        else builder.addAction(android.R.drawable.ic_media_play, "Play", MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PLAY))
        builder.addAction(android.R.drawable.ic_media_next, "Next", MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT))
        builder.addAction(android.R.drawable.ic_delete, "Exit", exitPi)

        builder.setStyle(
            androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(mediaSession?.sessionToken)
                .setShowActionsInCompactView(0, 1, 2)
        )

        return builder.build()
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Monochrome Playback", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                    setSound(null, null)
                    description = "Playback controls for Monochrome"
                }
            )
        }
    }
}
