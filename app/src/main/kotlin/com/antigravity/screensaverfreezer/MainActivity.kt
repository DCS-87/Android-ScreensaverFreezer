package com.antigravity.screensaverfreezer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var durationSpinner: Spinner
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var testButton: Button

    private val durations = arrayOf("5 Minutes", "15 Minutes", "30 Minutes", "1 Hour", "4 Hours")
    private val durationMs = arrayOf(
        5 * 60 * 1000L,
        15 * 60 * 1000L,
        30 * 60 * 1000L,
        60 * 60 * 1000L,
        240 * 60 * 1000L
    )

    private var pendingIsTest = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        durationSpinner = findViewById(R.id.duration_spinner)
        startButton = findViewById(R.id.btn_start)
        stopButton = findViewById(R.id.btn_stop)
        testButton = findViewById(R.id.btn_test)

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, durations)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        durationSpinner.adapter = adapter

        startButton.setOnClickListener {
            if (checkPermissions()) {
                requestIgnoreBatteryOptimizations()
                pendingIsTest = false
                requestScreenCapture()
            }
        }

        stopButton.setOnClickListener {
            stopFreezerService()
        }

        testButton.setOnClickListener {
            if (checkPermissions()) {
                pendingIsTest = true
                requestScreenCapture()
            }
        }
    }

    private fun checkPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, OVERLAY_PERMISSION_REQ_CODE)
                return false
            }
        }

        val storagePermission = Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, storagePermission) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(storagePermission), STORAGE_PERMISSION_REQ_CODE)
            return false
        }
        
        return true
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
        }
    }

    private fun requestScreenCapture() {
        // First start the service so it can call startForeground
        val serviceIntent = Intent(this, ScreensaverFreezerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(projectionManager.createScreenCaptureIntent(), SCREEN_CAPTURE_REQ_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SCREEN_CAPTURE_REQ_CODE && resultCode == RESULT_OK && data != null) {
            startFreezerService(resultCode, data, pendingIsTest)
        }
    }

    private fun startFreezerService(resultCode: Int, data: Intent, isTest: Boolean) {
        val selectedIndex = durationSpinner.selectedItemPosition
        val duration = durationMs[selectedIndex]
        
        val intent = Intent(this, ScreensaverFreezerService::class.java)
        intent.putExtra(ScreensaverFreezerService.EXTRA_DURATION, duration)
        intent.putExtra(ScreensaverFreezerService.EXTRA_PROJECTION_RESULT_CODE, resultCode)
        intent.putExtra(ScreensaverFreezerService.EXTRA_PROJECTION_DATA, data)
        intent.putExtra(ScreensaverFreezerService.EXTRA_TEST_MODE, isTest)
        
        // Restart service with the actual projection data
        startService(intent)
        
        val msg = if (isTest) "Running Test..." else "Service Started"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun stopFreezerService() {
        stopService(Intent(this, ScreensaverFreezerService::class.java))
        Toast.makeText(this, "Service Stopped", Toast.LENGTH_SHORT).show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == STORAGE_PERMISSION_REQ_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                requestScreenCapture()
            }
        }
    }

    companion object {
        private const val OVERLAY_PERMISSION_REQ_CODE = 123
        private const val STORAGE_PERMISSION_REQ_CODE = 456
        private const val SCREEN_CAPTURE_REQ_CODE = 789
    }
}
