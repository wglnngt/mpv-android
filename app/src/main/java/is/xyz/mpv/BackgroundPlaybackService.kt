package `is`.xyz.mpv

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.util.Log

/*
    All this service does is
    - Discourage Android from killing mpv while it's in background
    - Update the persistent notification (which we're forced to display)
*/

class BackgroundPlaybackService : Service(), MPVLib.EventObserver {
    override fun onCreate() {
        MPVLib.addObserver(this)
    }

    private var cachedMetadata = Utils.AudioMetadata()
    private var shouldShowPrevNext: Boolean = false

    private fun createButtonIntent(action: String): PendingIntent {
        val intent = Intent()
        intent.action = "is.xyz.mpv.$action"
        // turn into explicit intent:
        intent.component = ComponentName(applicationContext, NotificationButtonReceiver::class.java)
        return PendingIntent.getBroadcast(this, 0, intent, 0)
    }

    @Suppress("DEPRECATION") // deliberate to support lower API levels
    private fun buildNotification(): Notification {
        val notificationIntent = Intent(this, MPVActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0)

        val builder: Notification.Builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        else
            Notification.Builder(this)

        builder
                .setPriority(Notification.PRIORITY_LOW)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentTitle(cachedMetadata.formatTitle())
                .setContentText(cachedMetadata.formatArtistAlbum())
                .setSmallIcon(R.drawable.ic_mpv_symbolic)
                .setContentIntent(pendingIntent)
        if (thumbnail != null)
            builder.setLargeIcon(thumbnail)
        if (shouldShowPrevNext) {
            // action icons need to be 32dp according to the docs
            builder.addAction(R.drawable.ic_skip_previous_black_32dp, "Prev", createButtonIntent("ACTION_PREV"))
            builder.addAction(R.drawable.ic_skip_next_black_32dp, "Next", createButtonIntent("ACTION_NEXT"))
            builder.style = Notification.MediaStyle().setShowActionsInCompactView(0, 1)
        }

        return builder.build()
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Log.v(TAG, "BackgroundPlaybackService: starting")

        // read some metadata

        cachedMetadata.readAll()
        shouldShowPrevNext = MPVLib.getPropertyInt("playlist-count") ?: 0 > 1

        // create notification and turn this into a "foreground service"

        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)

        return START_NOT_STICKY // Android can't restart this service on its own
    }

    override fun onDestroy() {
        MPVLib.removeObserver(this)

        Log.v(TAG, "BackgroundPlaybackService: destroyed")
    }

    override fun onBind(intent: Intent): IBinder? { return null }

    /* Event observers */

    override fun eventProperty(property: String) { }

    override fun eventProperty(property: String, value: Boolean) { }

    override fun eventProperty(property: String, value: Long) { }

    override fun eventProperty(property: String, value: String) {
        if (!cachedMetadata.update(property, value))
            return

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification())
    }

    override fun event(eventId: Int) {
        if (eventId == MPVLib.mpvEventId.MPV_EVENT_IDLE)
            stopSelf()
    }


    companion object {
        /* Using this property MPVActivity gives us a thumbnail
           to display alongside the permanent notification */
        var thumbnail: Bitmap? = null

        private const val NOTIFICATION_ID = 12345 // TODO: put this into resource file
        const val NOTIFICATION_CHANNEL_ID = "background_playback"

        private const val TAG = "mpv"
    }
}