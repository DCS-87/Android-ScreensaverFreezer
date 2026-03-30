package com.antigravity.screensaverfreezer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView

class OverlayManager(private val context: Context) {

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null
    
    var onSingleTap: (() -> Unit)? = null
    var onDoubleTap: (() -> Unit)? = null

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            Log.d("ScreensaverFreezer", "Overlay single-tapped")
            onSingleTap?.invoke()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            Log.d("ScreensaverFreezer", "Overlay double-tapped")
            onDoubleTap?.invoke()
            return true
        }
    })

    fun showOverlay(bitmap: Bitmap?) {
        Log.d("ScreensaverFreezer", "showOverlay: hasBitmap=${bitmap != null}")
        try {
            if (overlayView == null) {
                val inflater = LayoutInflater.from(context)
                overlayView = inflater.inflate(R.layout.overlay_image, null)

                val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_SYSTEM_ERROR
                }

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    layoutFlag,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                            WindowManager.LayoutParams.FLAG_FULLSCREEN or
                            WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON,
                    PixelFormat.TRANSLUCENT
                )

                params.gravity = Gravity.TOP or Gravity.START
                
                overlayView?.setOnTouchListener { _, event ->
                    gestureDetector.onTouchEvent(event)
                    true
                }

                windowManager.addView(overlayView, params)
            }

            overlayView?.visibility = View.VISIBLE
            val imageView = overlayView?.findViewById<ImageView>(R.id.frozen_image)
            imageView?.let {
                val oldBitmap = (it.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                if (bitmap != null) {
                    it.setBackgroundColor(Color.BLACK)
                    it.setImageBitmap(bitmap)
                } else {
                    it.setBackgroundColor(Color.RED)
                    it.setImageDrawable(null)
                }
                if (oldBitmap != null && oldBitmap != bitmap) oldBitmap.recycle()
            }
        } catch (e: Exception) {
            Log.e("ScreensaverFreezer", "Error showing overlay", e)
        }
    }

    fun hideOverlay() {
        overlayView?.visibility = View.GONE
    }

    fun removeOverlay() {
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {}
            overlayView = null
        }
    }

    fun isShowing(): Boolean = overlayView != null && overlayView?.visibility == View.VISIBLE
}
