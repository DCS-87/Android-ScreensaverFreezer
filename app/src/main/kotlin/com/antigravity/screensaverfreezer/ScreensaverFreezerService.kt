package com.antigravity.screensaverfreezer

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat

class ScreensaverFreezerService : Service() {

    private lateinit var overlayManager: OverlayManager
    private val handler = Handler(Looper.getMainLooper())
    private var freezeDurationMs: Long = 5 * 60 * 1000
    private var isServiceActive = true
    private var wakeLock: PowerManager.WakeLock? = null

    private var projectionResultCode: Int = 0
    private var projectionData: Intent? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var screenDensity: Int = 0
    
    private var isWaitingForSnapshot = false
    private var snapshotCount = 0

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(TAG, "Screen OFF - Service remaining active via WakeLock")
                    acquireWakeLock()
                }
                Intent.ACTION_SCREEN_ON -> {
                    Log.d(TAG, "Screen ON - removing overlay")
                    unfreeze()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        isServiceActive = true
        overlayManager = OverlayManager(this)
        
        // Single tap initiates manual refresh and resets timer
        overlayManager.onSingleTap = {
            Log.d(TAG, "Manual refresh requested via single tap")
            handler.removeCallbacks(cycleRunnable)
            handler.post(cycleRunnable)
        }

        // Double tap stops service
        overlayManager.onDoubleTap = {
            Log.d(TAG, "Exit requested via double tap")
            stopSelf()
        }
        
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "screensaver_freezer"
            val channel = NotificationChannel(
                channelId,
                "Screensaver Freezer Service",
                NotificationManager.IMPORTANCE_MAX
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        acquireWakeLock()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenReceiver, filter)
    }

    private fun acquireWakeLock() {
        if (wakeLock == null || !wakeLock!!.isHeld) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ScreensaverFreezer::WakeLock")
            wakeLock?.acquire(24 * 60 * 60 * 1000L)
            Log.d(TAG, "WakeLock acquired")
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "WakeLock released")
            }
        }
        wakeLock = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            val duration = intent.getLongExtra(EXTRA_DURATION, 0)
            if (duration > 0) freezeDurationMs = duration
            
            val code = intent.getIntExtra(EXTRA_PROJECTION_RESULT_CODE, 0)
            val data = intent.getParcelableExtra<Intent>(EXTRA_PROJECTION_DATA)
            
            if (data != null && code != 0) {
                projectionResultCode = code
                projectionData = data
                
                Log.d(TAG, "Projection data received. Establishing permanent stream...")
                initCaptureResources()
                
                handler.removeCallbacks(cycleRunnable)
                val isTest = intent.getBooleanExtra(EXTRA_TEST_MODE, false)
                snapshotCount = 0 
                handler.postDelayed(cycleRunnable, if (isTest) 100 else 1000)
            }
        }
        return START_STICKY
    }

    private fun initCaptureResources() {
        if (mediaProjection != null) return
        
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            mediaProjection = projectionManager.getMediaProjection(projectionResultCode, projectionData!!)
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w(TAG, "MediaProjection session ended by system")
                    mediaProjection = null
                }
            }, handler)

            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                if (image != null) {
                    if (isWaitingForSnapshot) {
                        isWaitingForSnapshot = false
                        Log.d(TAG, "Snapshot frame arrived! Processing...")
                        val bitmap = convertImageToBitmap(image)
                        if (bitmap != null) {
                            snapshotCount++
                            Log.d(TAG, "Snapshot #$snapshotCount success. Freezing...")
                            overlayManager.showOverlay(bitmap)
                            
                            val nextDelay = if (snapshotCount == 1) {
                                60 * 1000L // Second snap after 1 minute
                            } else {
                                freezeDurationMs
                            }
                            scheduleNext(nextDelay)
                        } else {
                            Log.w(TAG, "Captured frame was invalid (black). Retrying in 5s...")
                            scheduleNext(5000)
                        }
                    }
                    image.close()
                }
            }, handler)

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreensaverCapture",
                screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface, null, null
            )
            
            Log.d(TAG, "Capture resources established successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init resources", e)
        }
    }

    private val cycleRunnable = object : Runnable {
        override fun run() {
            if (!isServiceActive) return
            
            Log.d(TAG, "Cycle: Exposing screensaver...")
            unfreeze()
            
            handler.postDelayed({
                Log.d(TAG, "Requesting fresh snapshot from stream...")
                isWaitingForSnapshot = true
            }, 8000)
        }
    }

    private fun scheduleNext(delay: Long) {
        if (isServiceActive) {
            handler.removeCallbacks(cycleRunnable)
            handler.postDelayed(cycleRunnable, delay)
        }
    }

    private fun convertImageToBitmap(image: android.media.Image): Bitmap? {
        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val bitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight, Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            
            val pixel = bitmap.getPixel(screenWidth / 2, screenHeight / 2)
            if (Color.alpha(pixel) == 0 || pixel == Color.BLACK) {
                bitmap.recycle()
                return null
            }

            return if (rowPadding == 0) {
                bitmap
            } else {
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
                bitmap.recycle()
                cropped
            }
        } catch (e: Exception) {
            Log.e(TAG, "Bitmap conversion error", e)
            return null
        }
    }

    private fun unfreeze() {
        isWaitingForSnapshot = false
        overlayManager.hideOverlay()
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceActive = false
        handler.removeCallbacks(cycleRunnable)
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        releaseWakeLock()
        try { unregisterReceiver(screenReceiver) } catch (e: Exception) {}
        overlayManager.removeOverlay()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(): Notification {
        val channelId = "screensaver_freezer"
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Screensaver Freezer is Active")
            .setContentText("Double-tap to exit. Single-tap to refresh.")
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "ScreensaverFreezer"
        private const val NOTIFICATION_ID = 101
        const val EXTRA_DURATION = "extra_duration"
        const val EXTRA_PROJECTION_RESULT_CODE = "extra_projection_result_code"
        const val EXTRA_PROJECTION_DATA = "extra_projection_data"
        const val EXTRA_TEST_MODE = "extra_test_mode"
    }
}
