package com.example.phonecamera.streamer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.phonecamera.MainActivity
import com.example.phonecamera.data.nsd.NsdHelper
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.library.view.OpenGlView
import com.pedro.rtspserver.RtspServerCamera2

private const val TAG = "RTSPGuard.Service"

/** Result of a streaming start attempt with a detailed reason on failure */
sealed class StreamResult {
    data object Success : StreamResult()
    data class Error(val reason: String) : StreamResult()
}

/**
 * RTSP Server Foreground Service — RootEncoder 2.x API
 *
 * ConnectChecker      → com.pedro.common.ConnectChecker
 * OpenGlView          → com.pedro.library.view.OpenGlView
 * CameraHelper.Facing → com.pedro.encoder.input.video.CameraHelper.Facing
 * startStream()       → Unit  (check isStreaming after)
 */
class RtspStreamService : Service() {

    companion object {
        const val CHANNEL_ID = "rtsp_guard_camera"
        const val NOTIFICATION_ID = 101
        const val PORT = 8080
        const val ACTION_STOP = "com.example.phonecamera.ACTION_STOP_STREAM"

        fun startIntent(context: Context) = Intent(context, RtspStreamService::class.java)
        fun stopIntent(context: Context) = Intent(context, RtspStreamService::class.java).apply {
            action = ACTION_STOP
        }
    }

    inner class StreamBinder : Binder() {
        fun getService() = this@RtspStreamService
    }

    private val binder = StreamBinder()
    private var rtspCamera: RtspServerCamera2? = null
    private val nsdHelper by lazy { NsdHelper(applicationContext) }

    val isStreaming: Boolean get() = rtspCamera?.isStreaming ?: false

    private val connectChecker = object : ConnectChecker {
        override fun onConnectionStarted(url: String) {
            Log.d(TAG, "Client connecting: $url")
        }
        override fun onConnectionSuccess() {
            Log.d(TAG, "Client connected successfully")
            updateNotification("Camera đang phát — cổng $PORT")
        }
        override fun onConnectionFailed(reason: String) {
            Log.e(TAG, "Connection failed: $reason")
            updateNotification("Lỗi kết nối: $reason")
        }
        override fun onNewBitrate(bitrate: Long) {
            Log.d(TAG, "Outgoing Bitrate: ${bitrate / 1000} kbps")
        }
        override fun onDisconnect() {
            Log.d(TAG, "Client disconnected")
            updateNotification("Client đã ngắt kết nối")
        }
        override fun onAuthError() {
            Log.e(TAG, "Auth error")
            updateNotification("Lỗi xác thực")
        }
        override fun onAuthSuccess() {}
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Đang chuẩn bị..."))
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.d(TAG, "Stop action received")
            stopStreaming()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /**
     * Attach OpenGlView. Must be called before [startStreaming].
     * RtspServerCamera2 constructor requires an OpenGlView, not a plain SurfaceView.
     */
    fun attachCamera(openGlView: OpenGlView, useFrontCamera: Boolean = false) {
        Log.d(TAG, "attachCamera() called — creating RtspServerCamera2 on port $PORT")
        rtspCamera = RtspServerCamera2(openGlView, connectChecker, PORT)
        val facing = if (useFrontCamera) CameraHelper.Facing.FRONT else CameraHelper.Facing.BACK
        rtspCamera?.startPreview(facing)
    }

    /**
     * Prepare encoder and start RTSP stream. Returns [StreamResult] with details on failure.
     */
    fun startStreaming(
        width: Int,
        height: Int,
        fps: Int,
        bitrateBps: Int,
        useFrontCamera: Boolean
    ): StreamResult {
        val cam = rtspCamera
            ?: return StreamResult.Error("Camera chưa được gắn vào (rtspCamera = null). " +
                    "Hãy đợi một chút và thử lại.")

        return try {
            Log.d(TAG, "prepareVideo: ${width}x${height} @ ${fps}fps ${bitrateBps/1000}kbps")
            val videoOk = cam.prepareVideo(width, height, fps, bitrateBps, 0)
            if (!videoOk) {
                return StreamResult.Error(
                    "Không thể chuẩn bị encoder video ${width}x${height}. " +
                    "Hãy thử chọn độ phân giải 480p."
                )
            }

            Log.d(TAG, "prepareAudio")
            val audioOk = cam.prepareAudio()
            if (!audioOk) {
                return StreamResult.Error(
                    "Không thể khởi tạo microphone. Kiểm tra quyền RECORD_AUDIO."
                )
            }

            val facing = if (useFrontCamera) CameraHelper.Facing.FRONT else CameraHelper.Facing.BACK
            Log.d(TAG, "startPreview: facing=$facing")
            cam.startPreview(facing)

            Log.d(TAG, "startStream")
            cam.startStream()

            if (cam.isStreaming) {
                Log.d(TAG, "Stream started successfully on port $PORT")
                nsdHelper.registerService(PORT)
                StreamResult.Success
            } else {
                StreamResult.Error(
                    "Luồng camera đã khởi tạo nhưng không phát được. " +
                    "Hãy kiểm tra quyền CAMERA và thử lại."
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "startStreaming exception", e)
            StreamResult.Error("Lỗi: ${e.localizedMessage ?: e.javaClass.simpleName}")
        }
    }

    fun stopStreaming() {
        nsdHelper.unregisterService()
        rtspCamera?.apply {
            if (isStreaming) { Log.d(TAG, "stopStream"); stopStream() }
            if (isOnPreview) { Log.d(TAG, "stopPreview"); stopPreview() }
        }
        updateNotification("Đã dừng phát")
    }

    fun switchCamera() {
        Log.d(TAG, "switchCamera")
        rtspCamera?.switchCamera()
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        nsdHelper.stopAll()
        stopStreaming()
        super.onDestroy()
    }

    // ─────────────────────────────────────────────
    private fun buildNotification(text: String): Notification {
        val openPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stopPi = PendingIntent.getService(
            this, 1, stopIntent(this), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Phone Camera")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_media_pause, "Dừng", stopPi)
            .setOngoing(true).setSilent(true).build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Camera an ninh", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Phone Camera đang phát"; setShowBadge(false) }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }
}
