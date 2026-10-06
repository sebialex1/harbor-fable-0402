package io.harbor.fable.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import io.harbor.fable.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps a dataSync notification alive while
 * [DownloadManager] has queued or active transfers.
 *
 * The service does not download anything itself. It enters the foreground so
 * Android does not kill the process mid-transfer, then calls
 * [DownloadManager.kick] to start (or resume) the queue. It tears itself down
 * once [DownloadSnapshot.hasActiveWork] is false.
 *
 * If the system refuses a foreground service start (background launch
 * restrictions on Android 12+), [DownloadManager] falls back to an in-process
 * download so an open activity is not blocked.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var observer: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "onStartCommand action=$action startId=$startId")

        ensureChannel(this)
        startForeground(NOTIFICATION_ID, buildNotification(null))

        if (action == ACTION_PROCESS) {
            downloadManager.kick()
        }

        // Observe the snapshot and stop when there is no active work left.
        if (observer?.isActive != true) {
            observer = scope.launch {
                downloadManager.snapshot.collect { snapshot ->
                    val notification = buildNotification(snapshot)
                    val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                    manager.notify(NOTIFICATION_ID, notification)

                    if (!snapshot.hasActiveWork) {
                        Log.d(TAG, "No active downloads, stopping")
                        stopForegroundCompat()
                        stopSelf(startId)
                        observer?.cancel()
                        observer = null
                    }
                }
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        observer?.cancel()
        observer = null
        super.onDestroy()
    }

    private fun buildNotification(snapshot: DownloadSnapshot?): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(getString(R.string.download_notification_title))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (snapshot == null || !snapshot.hasActiveWork) {
            builder.setContentText(getString(R.string.download_notification_preparing))
        } else {
            val active = snapshot.active
            val downloading = active.filter { it.status == DownloadStatus.DOWNLOADING }
            if (downloading.isNotEmpty()) {
                val current = downloading.first()
                val percent = (current.progressFraction * 100).toInt()
                builder.setContentText("${current.displayName} — $percent%")
                builder.setProgress(100, percent, current.totalBytes <= 0)
            } else {
                val count = active.size
                builder.setContentText(
                    resources.getQuantityString(R.plurals.download_notification_queued, count, count)
                )
                builder.setProgress(0, 0, true)
            }
        }

        return builder.build()
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    private val downloadManager: DownloadManager get() = DownloadManager.get(this)

    companion object {
        const val ACTION_PROCESS = "io.harbor.fable.action.PROCESS_DOWNLOADS"
        private const val CHANNEL_ID = "fable_downloads"
        private const val NOTIFICATION_ID = 0xF4B1
        private const val TAG = "DownloadService"

        private val channelCreated = java.util.concurrent.atomic.AtomicBoolean(false)

        /**
         * Creates the notification channel. Safe to call from any thread; the
         * work is performed at most once per process lifetime.
         */
        fun ensureChannel(context: Context) {
            if (channelCreated.get()) return
            synchronized(CHANNEL_ID) {
                if (channelCreated.get()) return
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.download_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.download_channel_desc)
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
                channelCreated.set(true)
            }
        }
    }
}
