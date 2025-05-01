// Copyright 2023 Freedelity. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.
package be.freedelity.barcode_scanner

import BarcodeFormats
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.hardware.camera2.CameraAccessException
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import be.freedelity.barcode_scanner.util.BarcodeScannerUtil
import be.freedelity.barcode_scanner.util.MrzUtil
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit


class BarcodeScannerController(private val activity: Activity, messenger: BinaryMessenger, methodChannelName: String, scanEventChannelName: String) : MethodChannel.MethodCallHandler, EventChannel.StreamHandler {

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

    init {
        methodChannel.setMethodCallHandler(this)
        scanStreamChannel.setStreamHandler(this)
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
            when (call.method) {
                "toggleTorch" -> {
                    try {
                        torchEnabled = !torchEnabled
                        camera?.cameraControl?.enableTorch(torchEnabled)
                        result.success(null)
                    } catch (e: Exception) {
                        handleException(e, result)
                    }
                }
                "flipCamera" -> {
                    try {
                        if (cameraParams != null) {

                            val map: MutableMap<String?, Any?> = cameraParams!!.toMutableMap()
                            if (map["camera_selector"] == "front")
                                map["camera_selector"] = "back"
                            else
                                map["camera_selector"] = "front"

                            startCamera(map)
                        }
                        result.success(null)
                    } catch (e: Exception) {
                        handleException(e, result)
                    }
                }
                "startScanner" -> {
                    try {
                        if (!isScannerActive) {
                            startScanner()
                        }
                        result.success(null)
                    } catch (e: Exception) {
                        handleException(e, result)
                    }
                }
                "stopScanner" -> {
                    try {
                        if (isScannerActive) {
                            isScannerActive = false
                            imageAnalysis.clearAnalyzer()
                        }
                        result.success(null)
                    } catch (e: Exception) {
                        handleException(e, result)
                    }
                }
                "closeCamera" -> {
                    try {
                        val cameraProvider = ProcessCameraProvider.getInstance(context).get()
                        cameraProvider.unbindAll()

                        result.success(null)
                    } catch (e: Exception) {
                        handleException(e, result)
                    }
                }
                else -> result.notImplemented()
            }

        }
    }

    fun startCamera(params: Map<String?, Any?>?, viewContext: Context? = null, viewPreviewView: PreviewView? = null, viewCameraExecutor: ExecutorService? = null) {

        if (viewPreviewView != null)
            previewView = viewPreviewView
        if (viewContext != null)
            context = viewContext
        if (viewCameraExecutor != null)
            executor = viewCameraExecutor

        cameraParams = params

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({

            if (cameraParams?.get("start_scanning") == true) {
                startScanner()
            }

            try {

                val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
                cameraProvider.unbindAll()

                val cameraSelector : CameraSelector = when (cameraParams?.get("camera_selector")) {
                    "front" -> CameraSelector.DEFAULT_FRONT_CAMERA
                    else -> CameraSelector.DEFAULT_BACK_CAMERA
                }

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
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

                camera = cameraProvider.bindToLifecycle(activity as LifecycleOwner, cameraSelector, preview, imageAnalysis)

                configureAutofocus()

            } catch(exc: Exception) {
                eventSink?.error("native_scanner_failed", "Camera binding failed", exc.message.orEmpty())
            }

        }, ContextCompat.getMainExecutor(context))
    }

    private fun startScanner() {
        isScannerActive = true

        var barcodeScanner: BarcodeScanner? = null

        Log.i("native_scanner", "Start for barcode scanner")
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
        barcodeScanner = BarcodeScanning.getClient(options)

        imageAnalysis.setAnalyzer(executor) { imageProxy ->
            processImageProxy(imageProxy, barcodeScanner, textRecognizer)
        }
    }

    private fun handleException(exception: Exception, result: MethodChannel.Result) {
        if (exception is CameraAccessException) {
            result.error("CameraAccess", exception.message, null)
            return
        }
        throw (exception as RuntimeException)
    }

    private fun configureAutofocus() {
        previewView.afterMeasured {
            val factory = previewView.meteringPointFactory
            val yCenter = previewView.height / 2f
            val xCenter = previewView.width / 2f
            val autofocusPoint = factory.createPoint(xCenter, yCenter, .01f)
            try {
                val autofocusAction = FocusMeteringAction.Builder(autofocusPoint).apply {
                    setAutoCancelDuration(300, TimeUnit.MILLISECONDS)
                    if (cameraParams?.get("scanner_type") == "barcode") {
                        addPoint(factory.createPoint(xCenter - 9f, yCenter, .01f))
                        addPoint(factory.createPoint(xCenter - 6f, yCenter, .01f))
                        addPoint(factory.createPoint(xCenter - 3f, yCenter, .01f))
                        addPoint(factory.createPoint(xCenter + 3f, yCenter, .01f))
                        addPoint(factory.createPoint(xCenter + 6f, yCenter, .01f))
                        addPoint(factory.createPoint(xCenter + 9f, yCenter, .01f))
                    } else {
                        addPoint(factory.createPoint(0f, yCenter, .01f))
                        addPoint(factory.createPoint(previewView.width / 1f, yCenter, .01f))
                    }
                }.build()
                camera!!.cameraControl.startFocusAndMetering(autofocusAction)
            } catch( e: CameraInfoUnavailableException) {
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
        return when(mlKitType) {
            Barcode.FORMAT_CODE_39 -> BarcodeFormats.CODE_39
            Barcode.FORMAT_CODE_93 -> BarcodeFormats.CODE_93
            Barcode.FORMAT_CODE_128 -> BarcodeFormats.CODE_128
            Barcode.FORMAT_EAN_8 -> BarcodeFormats.EAN_8
            Barcode.FORMAT_EAN_13 -> BarcodeFormats.EAN_13
            Barcode.FORMAT_ITF -> BarcodeFormats.ITF
            Barcode.FORMAT_CODABAR -> BarcodeFormats.CODABAR
            Barcode.FORMAT_UPC_A -> BarcodeFormats.UPCA_A
            Barcode.FORMAT_UPC_E -> BarcodeFormats.UPC_E
            else -> -1
        }
    }

    private fun getScannerProcessInterval(): Int {
        return if (cameraParams?.get("scanner_type") == "mrz") {
            100
        } else if (cameraParams?.get("scanner_type") == "text") {
            500
        } else {
            2000
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun processImageProxy(imageProxy: ImageProxy, barcodeScanner: BarcodeScanner?) {

        assert(barcodeScanner != null)

        val mediaImage = imageProxy.image ?: return

        if (System.currentTimeMillis() > (scanSucceedTimestamp + getScannerProcessInterval())) {

            val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            barcodeScanner!!.process(inputImage)
                .addOnSuccessListener { barcodeList ->

                    val barcode: Barcode? = barcodeList.getOrNull(0)

                    if (barcode != null) {
                        scanSucceedTimestamp = System.currentTimeMillis()
                        eventSink?.success(mapOf(
                            "barcode" to barcode.displayValue,
                            "format" to convertBarcodeType(barcode.format)
                        ))
                    }

                }
                .addOnFailureListener {
                    eventSink?.error("native_scanner_failed", "An error occurs while processing image proxy for barcode scanner", it.message.orEmpty())
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