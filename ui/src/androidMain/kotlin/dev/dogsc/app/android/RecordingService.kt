package dev.dogsc.app.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.dogsc.app.ui.Loc
import kotlin.math.roundToInt

class RecordingService : Service() {
    private var usesMicrophone = false
    private var usesCamera = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                usesMicrophone = intent.getBooleanExtra(EXTRA_USES_MICROPHONE, false)
                usesCamera = intent.getBooleanExtra(EXTRA_USES_CAMERA, false)
            }
            ACTION_STOP -> {
                // Keep the foreground service alive until the coordinator has
                // stopped encoders and saved the project. The manager sends a
                // second ACTION_STOP after finalization to actually stop us.
                val manager = AndroidRecorderManager.instance
                if (manager?.state?.value?.isActive == true) {
                    manager.stop()
                } else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
            ACTION_PAUSE -> AndroidRecorderManager.instance?.pause()
            ACTION_RESUME -> AndroidRecorderManager.instance?.resume()
            ACTION_MARKER -> AndroidRecorderManager.instance?.addMarker()
            else -> Unit
        }
        val notification = buildNotification(0.0, false, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Microphone/camera FGS types were introduced in API 30; on API 29 passing
                // those bits to startForeground is invalid.
                if (usesMicrophone && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                if (usesCamera && checkSelfPermission(android.Manifest.permission.CAMERA) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    // CameraX runs while the app is in the background; Android 14+ only allows
                    // that from a foreground service that declares the camera type.
                    types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                }
            }
            startForeground(NOTIFICATION_ID, notification, types)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                Loc.t("录制"),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = Loc.t("屏幕录制状态与控制")
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(elapsed: Double, paused: Boolean, markers: Int): Notification {
        val total = elapsed.coerceAtLeast(0.0).roundToInt()
        val time = "%02d:%02d".format(total / 60, total % 60)
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(
                this,
                1,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (paused) "${Loc.t("DogSC 已暂停")} · $time" else "${Loc.t("DogSC 正在录制")} · $time")
            .setContentText(if (markers > 0) Loc.t("已添加 $markers 个标记") else Loc.t("点击回到 DogSC 查看录制状态"))
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .setContentIntent(launch)
            .setOnlyAlertOnce(true)
            .addAction(0, Loc.t(if (paused) "继续" else "暂停"), servicePending(if (paused) ACTION_RESUME else ACTION_PAUSE))
            .addAction(0, Loc.t("标记"), servicePending(ACTION_MARKER))
            .addAction(0, Loc.t("停止"), servicePending(ACTION_STOP))
        return builder.build()
    }

    private fun servicePending(action: String): PendingIntent = PendingIntent.getService(
        this,
        action.hashCode(),
        Intent(this, RecordingService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val CHANNEL_ID = "dogsc_recording"
        const val NOTIFICATION_ID = 4101
        const val ACTION_START = "dev.dogsc.action.START"
        const val ACTION_STOP = "dev.dogsc.action.STOP"
        const val ACTION_PAUSE = "dev.dogsc.action.PAUSE"
        const val ACTION_RESUME = "dev.dogsc.action.RESUME"
        const val ACTION_MARKER = "dev.dogsc.action.MARKER"
        const val EXTRA_USES_MICROPHONE = "dev.dogsc.extra.MICROPHONE"
        const val EXTRA_USES_CAMERA = "dev.dogsc.extra.CAMERA"

        fun update(context: Context, elapsed: Double, paused: Boolean, markers: Int) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val total = elapsed.coerceAtLeast(0.0).roundToInt()
            val time = "%02d:%02d".format(total / 60, total % 60)
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                PendingIntent.getActivity(
                    context,
                    1,
                    it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(if (paused) "${Loc.t("DogSC 已暂停")} · $time" else "${Loc.t("DogSC 正在录制")} · $time")
                .setContentText(if (markers > 0) Loc.t("已添加 $markers 个标记") else Loc.t("点击回到 DogSC 查看录制状态"))
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setOngoing(true)
                .setContentIntent(launch)
                .setOnlyAlertOnce(true)
                .addAction(0, Loc.t(if (paused) "继续" else "暂停"), pending(context, if (paused) ACTION_RESUME else ACTION_PAUSE))
                .addAction(0, Loc.t("标记"), pending(context, ACTION_MARKER))
                .addAction(0, Loc.t("停止"), pending(context, ACTION_STOP))
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        }

        private fun pending(context: Context, action: String): PendingIntent = PendingIntent.getService(
            context,
            action.hashCode(),
            Intent(context, RecordingService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
