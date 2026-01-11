package com.example.cam_rng

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageFormat
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.widget.Button
import android.widget.TextView
import java.security.MessageDigest
import java.security.SecureRandom

class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var flipButton: Button

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    @Volatile
    private var capturing = false

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        flipButton = findViewById(R.id.flipButton)

        startBackgroundThread()

        flipButton.setOnClickListener {
            if (hasCameraPermission()) {
                captureAndFlip()
            } else {
                statusText.text = getString(R.string.status_permission)
                requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
            }
        }
    }

    override fun onDestroy() {
        closeCamera()
        stopBackgroundThread()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                captureAndFlip()
            } else {
                statusText.text = getString(R.string.status_permission)
            }
        }
    }

    private fun hasCameraPermission(): Boolean {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraThread").also {
            it.start()
            backgroundHandler = Handler(it.looper)
        }
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
        } catch (ignored: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        backgroundThread = null
        backgroundHandler = null
    }

    private fun captureAndFlip() {
        if (capturing) {
            return
        }
        capturing = true
        flipButton.isEnabled = false
        statusText.text = getString(R.string.status_capturing)

        val handler = backgroundHandler
        if (handler == null) {
            showError(R.string.status_error)
            return
        }

        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = pickCameraId(cameraManager)
        if (cameraId == null) {
            showError(R.string.status_no_camera)
            return
        }

        val imageSize = pickSmallestJpegSize(cameraManager, cameraId)
        val reader = ImageReader.newInstance(imageSize.width, imageSize.height, ImageFormat.JPEG, 1)
        imageReader = reader

        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireNextImage() ?: return@setOnImageAvailableListener
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            image.close()
            closeCamera()

            val isHeads = flipFromSeed(bytes)
            runOnUiThread {
                statusText.text = getString(if (isHeads) R.string.status_heads else R.string.status_tails)
                flipButton.isEnabled = true
                capturing = false
            }
        }, handler)

        openCamera(cameraManager, cameraId, reader)
    }

    private fun pickCameraId(cameraManager: CameraManager): String? {
        return try {
            var fallback: String? = null
            for (id in cameraManager.cameraIdList) {
                if (fallback == null) {
                    fallback = id
                }
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    return id
                }
            }
            fallback
        } catch (exc: CameraAccessException) {
            null
        }
    }

    private fun pickSmallestJpegSize(cameraManager: CameraManager, cameraId: String): Size {
        return try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(ImageFormat.JPEG)
            sizes?.minByOrNull { it.width * it.height } ?: Size(640, 480)
        } catch (exc: CameraAccessException) {
            Size(640, 480)
        }
    }

    private fun flipFromSeed(frameBytes: ByteArray): Boolean {
        val digest = MessageDigest.getInstance("SHA-256").digest(frameBytes)
        val random = SecureRandom()
        random.setSeed(digest)
        return random.nextBoolean()
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(cameraManager: CameraManager, cameraId: String, reader: ImageReader) {
        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device
                    createCaptureSession(device, reader)
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    showError(R.string.status_error)
                }

                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    showError(R.string.status_error)
                }
            }, backgroundHandler)
        } catch (exc: CameraAccessException) {
            showError(R.string.status_error)
        } catch (exc: SecurityException) {
            showError(R.string.status_permission)
        }
    }

    private fun createCaptureSession(device: CameraDevice, reader: ImageReader) {
        try {
            device.createCaptureSession(
                listOf(reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                            addTarget(reader.surface)
                        }
                        session.capture(
                            request.build(),
                            object : CameraCaptureSession.CaptureCallback() {
                                override fun onCaptureCompleted(
                                    session: CameraCaptureSession,
                                    request: CaptureRequest,
                                    result: TotalCaptureResult
                                ) {
                                    // ImageReader listener handles the result.
                                }
                            },
                            backgroundHandler
                        )
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        showError(R.string.status_error)
                    }
                },
                backgroundHandler
            )
        } catch (exc: CameraAccessException) {
            showError(R.string.status_error)
        }
    }

    private fun showError(messageId: Int) {
        runOnUiThread {
            statusText.text = getString(messageId)
            flipButton.isEnabled = true
            capturing = false
        }
        closeCamera()
    }

    private fun closeCamera() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
    }

    companion object {
        private const val REQUEST_CAMERA = 1
    }
}
