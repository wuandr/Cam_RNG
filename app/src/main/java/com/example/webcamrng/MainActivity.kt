package com.example.webcamrng

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var captureButton: Button
    private lateinit var statusText: TextView
    private lateinit var resultText: TextView
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        captureButton = findViewById(R.id.captureButton)
        statusText = findViewById(R.id.statusText)
        resultText = findViewById(R.id.resultText)

        cameraExecutor = Executors.newSingleThreadExecutor()

        captureButton.setOnClickListener {
            captureAndGenerate()
        }

        if (hasCameraPermission()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                REQUEST_CAMERA
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageCapture
                )
                statusText.text = getString(R.string.status_ready)
            } catch (exc: Exception) {
                statusText.text = "Camera error: ${exc.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun captureAndGenerate() {
        val capture = imageCapture ?: run {
            statusText.text = "Camera not ready."
            return
        }

        statusText.text = "Capturing frame..."
        capture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val seedBytes = imageToBytes(image)
                        val digest = MessageDigest.getInstance("SHA-256").digest(seedBytes)
                        val rng = SecureRandom()
                        rng.setSeed(digest)

                        val number = rng.nextInt().toLong() and 0xffffffffL
                        val flip = if (number % 2L == 0L) "heads" else "tails"

                        runOnUiThread {
                            statusText.text = "Captured and seeded."
                            resultText.text = "Number: $number\nCoin flip: $flip"
                        }
                    } catch (exc: Exception) {
                        runOnUiThread {
                            statusText.text = "Capture error: ${exc.message}"
                        }
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    runOnUiThread {
                        statusText.text = "Capture error: ${exception.message}"
                    }
                }
            }
        )
    }

    private fun imageToBytes(image: ImageProxy): ByteArray {
        val planes = image.planes
        var total = 0
        for (plane in planes) {
            total += plane.buffer.remaining()
        }

        val data = ByteArray(total)
        var offset = 0
        for (plane in planes) {
            val buffer = plane.buffer
            val length = buffer.remaining()
            buffer.get(data, offset, length)
            offset += length
        }
        return data
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA && grantResults.isNotEmpty()) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                statusText.text = "Camera permission denied."
            }
        }
    }

    companion object {
        private const val REQUEST_CAMERA = 1001
    }
}
