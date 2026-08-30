package app.practicelens.android

import android.content.Context
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File

class BasicCameraController(
    private val context: Context,
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var captureInFlight = false
    val isBound: Boolean
        get() = imageCapture != null

    fun bind(previewView: PreviewView, lifecycleOwner: LifecycleOwner, onReady: () -> Unit, onError: (String, Int?, String?, Boolean?) -> Unit) {
        if (imageCapture != null) {
            onReady()
            return
        }
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener(
            {
                try {
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val capture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build()
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        capture,
                    )
                    cameraProvider = provider
                    imageCapture = capture
                    onReady()
                } catch (e: Exception) {
                    imageCapture = null
                    onError(e.message ?: "Camera failed to start.", null, e.javaClass.simpleName, imageCapture != null)
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    fun capture(onCaptured: (File) -> Unit, onError: (String, Int?, String?, Boolean?) -> Unit): Boolean {
        val capture = imageCapture ?: return false
        if (captureInFlight) return false
        captureInFlight = true
        capture.targetRotation = currentRotation()
        val file = File.createTempFile("practice-lens-basic-", ".jpg", context.cacheDir)
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(
            options,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    captureInFlight = false
                    log("capture-completed state=CAPTURING")
                    onCaptured(file)
                }

                override fun onError(exception: ImageCaptureException) {
                    captureInFlight = false
                    file.delete()
                    val bound = imageCapture != null
                    log("capture-failed state=ERROR code=${exception.imageCaptureError} exception=${exception.javaClass.simpleName} bound=$bound")
                    onError(exception.message ?: "Capture failed.", exception.imageCaptureError, exception.javaClass.simpleName, bound)
                }
            },
        )
        return true
    }

    fun dispose() {
        captureInFlight = false
        runCatching { cameraProvider?.unbindAll() }
        cameraProvider = null
        imageCapture = null
    }

    private fun currentRotation(): Int {
        val display = ContextCompat.getSystemService(context, WindowManager::class.java)?.defaultDisplay
        return display?.rotation ?: Surface.ROTATION_0
    }

    private fun log(message: String) {
        runCatching { Log.d("PracticeLensBasic", message) }
    }
}
