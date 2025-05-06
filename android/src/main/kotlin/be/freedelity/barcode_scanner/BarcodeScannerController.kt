// Copyright 2023 Freedelity. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.
package be.freedelity.barcode_scanner

import BarcodeFormats
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.camera.camera2.interop.Camera2Interop
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

class BarcodeScannerController(
    private val activity: Activity,
    messenger: BinaryMessenger,
    methodChannelName: String,
    scanEventChannelName: String
) : MethodChannel.MethodCallHandler, EventChannel.StreamHandler {

    private var methodChannel: MethodChannel = MethodChannel(messenger, methodChannelName)
    private var scanStreamChannel: EventChannel = EventChannel(messenger, scanEventChannelName)
    private var eventSink: EventChannel.EventSink? = null

    private lateinit var executor: ExecutorService
    private lateinit var previewView: PreviewView
    private lateinit var context: Context

    private val imageAnalysis: ImageAnalysis = ImageAnalysis.Builder().build()
    private var cameraParams: Map<String?, Any?>? = null
    private var camera: Camera? = null

    private var isScannerActive: Boolean = false
    private var torchEnabled: Boolean = false
    private var scanSucceedTimestamp: Long = System.currentTimeMillis()
    private var scannerView: BarcodeScannerView? = null

    init {
        methodChannel.setMethodCallHandler(this)
        scanStreamChannel.setStreamHandler(this)
    }

    fun setScannerView(view: BarcodeScannerView) {
        scannerView = view
    }

    fun stopListening() {
        methodChannel.setMethodCallHandler(null)
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        eventSink = events
    }

    override fun onCancel(arguments: Any?) {
        eventSink?.endOfStream()
        eventSink = null
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        runBlocking {
            try {
                when (call.method) {
                    "requestCameraPermission" -> {
                        scannerView?.requestCameraPermission()
                        result.success(null)
                    }
                    "toggleTorch" -> {
                        torchEnabled = !torchEnabled
                        camera?.cameraControl?.enableTorch(torchEnabled)
                        result.success(null)
                    }

                    "flipCamera" -> {
                        cameraParams?.let {
                            val map = it.toMutableMap()
                            map["camera_selector"] =
                                if (map["camera_selector"] == "front") "back" else "front"
                            startCamera(map)
                        }
                        result.success(null)
                    }

                    "startScanner" -> {
                        if (!isScannerActive) {
                            startScanner()
                        }
                        result.success(null)
                    }

                    "stopScanner" -> {
                        if (isScannerActive) {
                            isScannerActive = false
                            imageAnalysis.clearAnalyzer()
                        }
                        result.success(null)
                    }

                    "closeCamera" -> {
                        val cameraProvider = ProcessCameraProvider.getInstance(context).get()
                        cameraProvider.unbindAll()
                        result.success(null)
                    }

                    else -> result.notImplemented()
                }
            } catch (e: Exception) {
                handleException(e, result)
            }
        }
    }

    fun startCamera(
        params: Map<String?, Any?>?,
        viewContext: Context? = null,
        viewPreviewView: PreviewView? = null,
        viewCameraExecutor: ExecutorService? = null
    ) {
        if (viewPreviewView != null) previewView = viewPreviewView
        if (viewContext != null) context = viewContext
        if (viewCameraExecutor != null) executor = viewCameraExecutor

        cameraParams = params
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
                cameraProvider.unbindAll()

                val cameraSelector = when (cameraParams?.get("camera_selector")) {
                    "front" -> CameraSelector.DEFAULT_FRONT_CAMERA
                    else -> CameraSelector.DEFAULT_BACK_CAMERA
                }

                val builder = Preview.Builder()
                val ext = Camera2Interop.Extender(builder)
                ext.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                )

                val preview = builder.build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                camera = cameraProvider.bindToLifecycle(
                    activity as LifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )

                configureAutofocus()

                if (cameraParams?.get("start_scanning") == true) {
                    startScanner()
                }

            } catch (exc: Exception) {
                eventSink?.error("native_scanner_failed", "Camera binding failed", exc.message.orEmpty())
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun startScanner() {
        isScannerActive = true

        val options = BarcodeScannerOptions.Builder().setBarcodeFormats(
            Barcode.FORMAT_CODE_39,
            Barcode.FORMAT_CODE_93,
            Barcode.FORMAT_CODE_128,
            Barcode.FORMAT_EAN_8,
            Barcode.FORMAT_EAN_13,
            Barcode.FORMAT_ITF,
            Barcode.FORMAT_CODABAR,
            Barcode.FORMAT_UPC_A,
            Barcode.FORMAT_UPC_E
        ).build()
        val barcodeScanner = BarcodeScanning.getClient(options)

        imageAnalysis.setAnalyzer(executor) { imageProxy ->
            processImageProxy(imageProxy, barcodeScanner)
        }
    }

    private fun handleException(exception: Exception, result: MethodChannel.Result) {
        if (exception is CameraAccessException) {
            result.error("CameraAccess", exception.message, null)
        } else {
            result.error("Exception", exception.message, null)
        }
    }

    private fun configureAutofocus() {
        previewView.afterMeasured {
            val factory = previewView.meteringPointFactory
            val xCenter = previewView.width / 2f
            val yCenter = previewView.height / 2f
            val autofocusPoint = factory.createPoint(xCenter, yCenter, .01f)

            try {
                val autofocusAction = FocusMeteringAction.Builder(autofocusPoint).apply {
                    setAutoCancelDuration(300, TimeUnit.MILLISECONDS)
                }.build()

                camera?.cameraControl?.startFocusAndMetering(autofocusAction)
            } catch (e: CameraInfoUnavailableException) {
                Log.e("native_scanner", "cannot access camera", e)
            }
        }
    }

    private inline fun View.afterMeasured(crossinline block: () -> Unit) {
        if (measuredWidth > 0 && measuredHeight > 0) {
            block()
        } else {
            viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    if (measuredWidth > 0 && measuredHeight > 0) {
                        viewTreeObserver.removeOnGlobalLayoutListener(this)
                        block()
                    }
                }
            })
        }
    }

    private fun convertBarcodeType(mlKitType: Int): Int {
        return when (mlKitType) {
            Barcode.FORMAT_CODE_39 -> BarcodeFormats.CODE_39
            Barcode.FORMAT_CODE_93 -> BarcodeFormats.CODE_93
            Barcode.FORMAT_CODE_128 -> BarcodeFormats.CODE_128
            Barcode.FORMAT_EAN_8 -> BarcodeFormats.EAN_8
            Barcode.FORMAT_EAN_13 -> BarcodeFormats.EAN_13
            Barcode.FORMAT_ITF -> BarcodeFormats.ITF
            Barcode.FORMAT_CODABAR -> BarcodeFormats.CODABAR
            Barcode.FORMAT_UPC_A -> BarcodeFormats.UPC_A
            Barcode.FORMAT_UPC_E -> BarcodeFormats.UPC_E
            else -> -1
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun processImageProxy(imageProxy: ImageProxy, barcodeScanner: BarcodeScanner) {
        val mediaImage = imageProxy.image ?: return

        if (System.currentTimeMillis() > (scanSucceedTimestamp + 2000)) {
            val inputImage =
                InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            barcodeScanner.process(inputImage)
                .addOnSuccessListener { barcodeList ->
                    barcodeList.firstOrNull()?.let { barcode ->
                        scanSucceedTimestamp = System.currentTimeMillis()
                        eventSink?.success(
                            mapOf(
                                "barcode" to barcode.displayValue,
                                "format" to convertBarcodeType(barcode.format)
                            )
                        )
                    }
                }
                .addOnFailureListener {
                    eventSink?.error(
                        "native_scanner_failed",
                        "Error while processing image for barcode scanner",
                        it.message.orEmpty()
                    )
                }
                .addOnCompleteListener {
                    imageProxy.image?.close()
                    imageProxy.close()
                }
        } else {
            imageProxy.image?.close()
            imageProxy.close()
        }
    }
}