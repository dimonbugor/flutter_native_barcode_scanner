package be.freedelity.barcode_scanner

import android.app.Activity
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodChannel

class BarcodeScannerPlugin : FlutterPlugin, ActivityAware {

  private val platformViewChannel = "be.freedelity/native_scanner/view"
  private val methodChannelName = "be.freedelity/native_scanner/method"
  private val scanEventChannel = "be.freedelity/native_scanner/imageStream"

  private var barcodeScannerController: BarcodeScannerController? = null
  private lateinit var activity: Activity
  private lateinit var methodChannel: MethodChannel
  private lateinit var flutterPluginBinding: FlutterPlugin.FlutterPluginBinding

  override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    flutterPluginBinding = binding
    methodChannel = MethodChannel(binding.binaryMessenger, methodChannelName)
    // controller ще не створено, чекаємо onAttachedToActivity
  }

  override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    methodChannel.setMethodCallHandler(null)
  }

  override fun onAttachedToActivity(binding: ActivityPluginBinding) {
    activity = binding.activity

    barcodeScannerController = BarcodeScannerController(
      activity,
      flutterPluginBinding.binaryMessenger,
      methodChannelName,
      scanEventChannel
    )

    methodChannel.setMethodCallHandler(barcodeScannerController)

    flutterPluginBinding
      .platformViewRegistry
      .registerViewFactory(
        platformViewChannel,
        BarcodeScannerViewFactory(activity, barcodeScannerController!!)
      )
  }

  override fun onDetachedFromActivity() {
    barcodeScannerController?.stopListening()
    barcodeScannerController = null
    methodChannel.setMethodCallHandler(null)
  }

  override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
    onAttachedToActivity(binding)
  }

  override fun onDetachedFromActivityForConfigChanges() {
    onDetachedFromActivity()
  }
}