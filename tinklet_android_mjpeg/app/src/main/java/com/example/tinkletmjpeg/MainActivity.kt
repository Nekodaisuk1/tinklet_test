package com.example.tinkletmjpeg

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.widget.TextView
import android.hardware.Camera
import androidx.camera.camera2.interop.Camera2Interop
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.net.NetworkInterface
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var endpointTextView: TextView

    private var mjpegServer: MjpegServer? = null
    private lateinit var cameraExecutor: ExecutorService
    private var legacyCamera: Camera? = null
    private var legacySurfaceTexture: SurfaceTexture? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var lastFrameAtMs: Long = 0L
    @Volatile
    private var usingLegacyCamera = false
    @Volatile
    private var activeCameraLabel: String = "camera"

    private val frameWatchdog = object : Runnable {
        override fun run() {
            if (mjpegServer != null && !usingLegacyCamera) {
                val elapsed = SystemClock.elapsedRealtime() - lastFrameAtMs
                if (elapsed > FRAME_STALL_TIMEOUT_MS) {
                    Log.w(TAG, "No camera frames for ${elapsed}ms. Switching to legacy camera.")
                    startLegacyCamera()
                }
            }
            mainHandler.postDelayed(this, FRAME_WATCHDOG_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        endpointTextView = findViewById(R.id.endpointTextView)

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (hasCameraPermission()) {
            startPipeline()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                REQUEST_CAMERA_PERMISSION
            )
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun startPipeline() {
        startServer()
        usingLegacyCamera = false
        lastFrameAtMs = SystemClock.elapsedRealtime()
        mainHandler.removeCallbacks(frameWatchdog)
        mainHandler.postDelayed(frameWatchdog, FRAME_WATCHDOG_INTERVAL_MS)
        startCamera()
    }

    private fun startServer() {
        val server = MjpegServer(SERVER_PORT)
        server.start(SOCKET_READ_TIMEOUT_MS, false)
        mjpegServer = server

        val hostIp = resolveLocalIpv4Address() ?: "<device-ip>"
        val endpoint = "http://$hostIp:$SERVER_PORT/stream"
        endpointTextView.text = getString(R.string.endpoint_template, endpoint)

        Log.i(TAG, "MJPEG server started: $endpoint")
    }

    private fun startCamera() {
        usingLegacyCamera = false
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener(
            {
                try {
                    val cameraProvider = cameraProviderFuture.get()
                    val cameraSelector = selectCamera(cameraProvider)

                    val previewBuilder = Preview.Builder()
                    Camera2Interop.Extender(previewBuilder)
                        .setCaptureRequestOption(
                            CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                            EXPOSURE_COMPENSATION
                        )

                    val preview = previewBuilder.build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }

                    val analysisBuilder = ImageAnalysis.Builder()
                        .setTargetResolution(Size(640, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    Camera2Interop.Extender(analysisBuilder)
                        .setCaptureRequestOption(
                            CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                            EXPOSURE_COMPENSATION
                        )

                    val imageAnalysis = analysisBuilder
                        .build()
                        .also { analyzer ->
                            analyzer.setAnalyzer(cameraExecutor) { imageProxy ->
                                try {
                                    val jpegBytes = imageProxyToJpeg(imageProxy)
                                    if (jpegBytes != null) {
                                        lastFrameAtMs = SystemClock.elapsedRealtime()
                                        mjpegServer?.pushFrame(jpegBytes)
                                    }
                                } catch (t: Throwable) {
                                    Log.e(TAG, "Analyzer pipeline error", t)
                                } finally {
                                    imageProxy.close()
                                }
                            }
                        }
                    

                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        this,
                        cameraSelector,
                        preview,
                        imageAnalysis
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "CameraX initialization failed. Falling back to legacy camera", e)
                    startLegacyCamera()
                }
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    @Suppress("DEPRECATION")
    private fun startLegacyCamera() {
        try {
            if (usingLegacyCamera && legacyCamera != null) {
                return
            }
            usingLegacyCamera = true

            try {
                ProcessCameraProvider.getInstance(this).get().unbindAll()
            } catch (t: Throwable) {
                Log.w(TAG, "Unable to unbind CameraX before legacy fallback", t)
            }

            legacyCamera?.release()
            legacySurfaceTexture?.release()

            val cameraCount = Camera.getNumberOfCameras()
            if (cameraCount <= 0) {
                Log.e(TAG, "Legacy camera fallback failed: no cameras on device")
                return
            }

            var selectedId = 0
            val info = Camera.CameraInfo()
            for (id in 0 until cameraCount) {
                Camera.getCameraInfo(id, info)
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                    selectedId = id
                    activeCameraLabel = "front"
                    break
                }
            }
            if (activeCameraLabel != "front") {
                activeCameraLabel = "back"
            }

            val camera = Camera.open(selectedId)
            val params = camera.parameters
            val bestSize = params.supportedPreviewSizes
                ?.minByOrNull { kotlin.math.abs(it.width * it.height - 640 * 480) }
            if (bestSize != null) {
                params.setPreviewSize(bestSize.width, bestSize.height)
            }
            params.previewFormat = ImageFormat.NV21
            camera.parameters = params

            val texture = SurfaceTexture(0)
            camera.setPreviewTexture(texture)
            camera.setPreviewCallback { data, cam ->
                try {
                    val size = cam.parameters.previewSize
                    val yuv = YuvImage(data, ImageFormat.NV21, size.width, size.height, null)
                    val output = ByteArrayOutputStream()
                    if (yuv.compressToJpeg(Rect(0, 0, size.width, size.height), JPEG_QUALITY, output)) {
                        lastFrameAtMs = SystemClock.elapsedRealtime()
                        mjpegServer?.pushFrame(addOverlay(output.toByteArray()))
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Legacy preview callback error", t)
                }
            }
            camera.startPreview()

            legacyCamera = camera
            legacySurfaceTexture = texture
            Log.i(TAG, "Legacy camera fallback started")
        } catch (t: Throwable) {
            Log.e(TAG, "Legacy camera fallback failed", t)
            usingLegacyCamera = false
        }
    }

        private fun selectCamera(cameraProvider: ProcessCameraProvider): CameraSelector {
            val frontCamera = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build()
            val backCamera = CameraSelector.DEFAULT_BACK_CAMERA

            return when {
                cameraProvider.hasCamera(frontCamera) -> {
                    Log.i(TAG, "Using front camera")
                    activeCameraLabel = "front"
                    frontCamera
                }
                cameraProvider.hasCamera(backCamera) -> {
                    Log.i(TAG, "Using back camera")
                    activeCameraLabel = "back"
                    backCamera
                }
                else -> {
                    Log.w(TAG, "No matching camera found, defaulting to back camera selector")
                    activeCameraLabel = "back"
                    backCamera
                }
            }
        }

    private fun imageProxyToJpeg(imageProxy: androidx.camera.core.ImageProxy): ByteArray? {
        val image = imageProxy.image ?: return null
        if (image.format != ImageFormat.YUV_420_888) {
            return null
        }

        val nv21 = yuv420888ToNv21(imageProxy)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
        val outputStream = ByteArrayOutputStream()
        val success = yuvImage.compressToJpeg(
            Rect(0, 0, imageProxy.width, imageProxy.height),
            JPEG_QUALITY,
            outputStream
        )
        return if (success) addOverlay(outputStream.toByteArray()) else null
    }

    private fun addOverlay(jpegBytes: ByteArray): ByteArray {
        val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: return jpegBytes
        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutableBitmap)

        val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(160, 0, 0, 0)
            style = Paint.Style.FILL
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 28f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }

        val nowText = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val label = "TINKLET LIVE | $activeCameraLabel | $nowText"

        canvas.drawRoundRect(12f, 12f, 12f + 620f, 12f + 52f, 12f, 12f, backgroundPaint)
        canvas.drawText(label, 24f, 48f, textPaint)

        val overlayStream = ByteArrayOutputStream()
        mutableBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, overlayStream)
        return overlayStream.toByteArray()
    }

    private fun yuv420888ToNv21(imageProxy: androidx.camera.core.ImageProxy): ByteArray {
        val width = imageProxy.width
        val height = imageProxy.height

        val yPlane = imageProxy.planes[0]
        val uPlane = imageProxy.planes[1]
        val vPlane = imageProxy.planes[2]

        val yBytes = yPlane.buffer.toByteArray()
        val uBytes = uPlane.buffer.toByteArray()
        val vBytes = vPlane.buffer.toByteArray()

        val yRowStride = yPlane.rowStride
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride

        val nv21 = ByteArray(width * height + (width * height / 2))

        var outputOffset = 0
        for (row in 0 until height) {
            val rowStart = row * yRowStride
            System.arraycopy(yBytes, rowStart, nv21, outputOffset, width)
            outputOffset += width
        }

        val chromaHeight = height / 2
        val chromaWidth = width / 2
        for (row in 0 until chromaHeight) {
            val uRowStart = row * uRowStride
            val vRowStart = row * vRowStride
            for (col in 0 until chromaWidth) {
                nv21[outputOffset++] = vBytes[vRowStart + col * vPixelStride]
                nv21[outputOffset++] = uBytes[uRowStart + col * uPixelStride]
            }
        }

        return nv21
    }

    private fun ByteBuffer.toByteArray(): ByteArray {
        val duplicate = duplicate()
        duplicate.clear()
        val bytes = ByteArray(duplicate.remaining())
        duplicate.get(bytes)
        return bytes
    }

    private fun resolveLocalIpv4Address(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .asSequence()
                .flatMap { it.inetAddresses.toList().asSequence() }
                .firstOrNull { address ->
                    !address.isLoopbackAddress && address.hostAddress?.contains(":") == false
                }
                ?.hostAddress
        } catch (_: Exception) {
            null
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA_PERMISSION &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startPipeline()
        } else {
            endpointTextView.text = getString(R.string.camera_permission_denied)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(frameWatchdog)
        legacyCamera?.setPreviewCallback(null)
        legacyCamera?.stopPreview()
        legacyCamera?.release()
        legacySurfaceTexture?.release()
        mjpegServer?.stop()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CAMERA_PERMISSION = 1001
        private const val SERVER_PORT = 8080
        private const val SOCKET_READ_TIMEOUT_MS = 5_000
        private const val JPEG_QUALITY = 70
        private const val EXPOSURE_COMPENSATION = 0
        private const val FRAME_WATCHDOG_INTERVAL_MS = 1_500L
        private const val FRAME_STALL_TIMEOUT_MS = 6_000L
    }
}
