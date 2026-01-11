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
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.graphics.ImageFormat
import android.graphics.BitmapFactory
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.widget.Button
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executor

class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var flipButton: Button
    private lateinit var framePreview: ImageView
    private lateinit var cameraToggle: Switch

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
        framePreview = findViewById(R.id.framePreview)
        cameraToggle = findViewById(R.id.cameraToggle)

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
        val cameraId = pickCameraId(cameraManager, cameraToggle.isChecked)
        if (cameraId == null) {
            showError(R.string.status_no_camera)
            return
        }

        val imageSize = pickLargestJpegSize(cameraManager, cameraId)
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
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            runOnUiThread {
                if (bitmap != null) {
                    framePreview.setImageBitmap(bitmap)
                }
                statusText.text = getString(if (isHeads) R.string.status_heads else R.string.status_tails)
                flipButton.isEnabled = true
                capturing = false
            }
        }, handler)

        openCamera(cameraManager, cameraId, reader)
    }

    private fun pickCameraId(cameraManager: CameraManager, useFront: Boolean): String? {
        return try {
            var fallback: String? = null
            var preferredFallback: String? = null
            for (id in cameraManager.cameraIdList) {
                if (fallback == null) {
                    fallback = id
                }
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (facing != null) {
                    if (preferredFallback == null) {
                        if (useFront && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                            preferredFallback = id
                        } else if (!useFront && facing == CameraCharacteristics.LENS_FACING_BACK) {
                            preferredFallback = id
                        }
                    }
                    if (useFront && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                        return id
                    }
                    if (!useFront && facing == CameraCharacteristics.LENS_FACING_BACK) {
                        return id
                    }
                }
            }
            preferredFallback ?: fallback
        } catch (exc: CameraAccessException) {
            null
        }
    }

    private fun pickLargestJpegSize(cameraManager: CameraManager, cameraId: String): Size {
        return try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(ImageFormat.JPEG)
            sizes?.maxByOrNull { it.width * it.height } ?: Size(1920, 1080)
        } catch (exc: CameraAccessException) {
            Size(1920, 1080)
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
            val callback = object : CameraCaptureSession.StateCallback() {
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
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val handler = backgroundHandler
                val executor = Executor { command ->
                    if (handler != null) {
                        handler.post(command)
                    } else {
                        command.run()
                    }
                }
                val outputConfigs = listOf(OutputConfiguration(reader.surface))
                val config = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputConfigs,
                    executor,
                    callback
                )
                device.createCaptureSession(config)
            } else {
                @Suppress("DEPRECATION")
                device.createCaptureSession(listOf(reader.surface), callback, backgroundHandler)
            }
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
