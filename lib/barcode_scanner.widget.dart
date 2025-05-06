// Copyright 2023 Freedelity. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.
import 'dart:async';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';

import 'barcode_scanner.dart';

/// Widget displaying the camera stream while scanning barcodes.
class BarcodeScannerWidget extends StatefulWidget {
  /// Select which camera should be used when creating the widget.
  final CameraSelector cameraSelector;

  /// Indicates if the barcode scanning process should start when creating the widget.
  final bool startScanning;

  /// Indicates if barcode scanning should stop after a barcode is detected. If `false`, `onBarcodeDetected` may be triggered multiple times for the same barcode.
  final bool stopScanOnBarcodeDetected;

  /// The orientation of the camera. Default set as Prortait
  final CameraOrientation orientation;

  /// The type of scanner which should decode the image and scan data
  final ScannerType scannerType;

  /// This function will be called when a barcode is detected.
  final Function(Barcode barcode)? onBarcodeDetected;

  final Function(int? progress)? onScanProgress;

  final Function(dynamic error) onError;

  final Function(bool status)? onPermissionStatus;

  const BarcodeScannerWidget({
    Key? key,
    this.cameraSelector = CameraSelector.back,
    this.startScanning = true,
    this.stopScanOnBarcodeDetected = true,
    this.orientation = CameraOrientation.portrait,
    this.scannerType = ScannerType.barcode,
    this.onBarcodeDetected,
    this.onScanProgress,
    this.onPermissionStatus,
    required this.onError,
  })  : assert(onBarcodeDetected != null),
        super(key: key);

  @override
  State<BarcodeScannerWidget> createState() => _BarcodeScannerWidgetState();
}

class _BarcodeScannerWidgetState extends State<BarcodeScannerWidget> {
  static const String platformViewChannel = 'be.freedelity/native_scanner/view';
  static const EventChannel eventChannel =
      EventChannel('be.freedelity/native_scanner/imageStream');
  static const MethodChannel _methodChannel =
      MethodChannel('be.freedelity/native_scanner/method');

  late Map<String, dynamic> creationParams;
  late StreamSubscription eventSubscription;

  Future<String> getCameraPermissionStatus() async {
    try {
      return await _methodChannel
              .invokeMethod<String>('getCameraPermissionStatus') ??
          'unknown';
    } catch (e) {
      return 'error';
    }
  }

  Future<void> requestCameraPermissionFromNative() async {
    try {
      final bool granted =
          await _methodChannel.invokeMethod('requestCameraPermission');
      widget.onPermissionStatus?.call(granted);
    } catch (e) {
      widget.onError(e);
    }
  }

  @override
  void initState() {
    super.initState();

    requestCameraPermissionFromNative();

    creationParams = {
      'orientation': widget.orientation.name,
      'scanner_type': widget.scannerType.name,
      'camera_selector': widget.cameraSelector.name,
      'start_scanning': widget.startScanning,
    };

    eventSubscription =
        eventChannel.receiveBroadcastStream().listen((dynamic event) async {
      if (widget.onScanProgress != null) {
        widget.onScanProgress!(event["progress"] as int?);
      }

      if (widget.onBarcodeDetected != null &&
          widget.scannerType == ScannerType.barcode) {
        final format = BarcodeFormat.unserialize(event['format']);

        if (format != null && event['barcode'] != null) {
          await BarcodeScanner.stopScanner();

          await widget.onBarcodeDetected!(
              Barcode(format: format, value: event['barcode'] as String));

          if (!widget.stopScanOnBarcodeDetected) {
            BarcodeScanner.startScanner();
          }
        } else if (event["progress"] == null) {
          widget.onError(const FormatException('Barcode not found'));
        }
      }
    }, onError: (dynamic error) {
      widget.onError(error);
    });
  }

  @override
  void dispose() {
    try {
      eventSubscription.cancel();
    } on PlatformException catch (e) {
      debugPrint(
          "Intercept event subscription cancel exception on scanner disposed without stream initialized yet : $e");
    }
    BarcodeScanner.closeCamera();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (Platform.isIOS) {
      return UiKitView(
          viewType: platformViewChannel,
          layoutDirection: TextDirection.ltr,
          creationParams: creationParams,
          creationParamsCodec: const StandardMessageCodec());
    }

    return Stack(children: [
      PlatformViewLink(
          viewType: platformViewChannel,
          surfaceFactory: (context, controller) {
            return AndroidViewSurface(
              controller: controller as AndroidViewController,
              gestureRecognizers: const <Factory<
                  OneSequenceGestureRecognizer>>{},
              hitTestBehavior: PlatformViewHitTestBehavior.opaque,
            );
          },
          onCreatePlatformView: (params) {
            return PlatformViewsService.initExpensiveAndroidView(
                id: params.id,
                viewType: platformViewChannel,
                layoutDirection: TextDirection.ltr,
                creationParams: creationParams,
                creationParamsCodec: const StandardMessageCodec(),
                onFocus: () {
                  params.onFocusChanged(true);
                })
              ..addOnPlatformViewCreatedListener(params.onPlatformViewCreated);
          }),
      const Positioned.fill(
          child: ModalBarrier(dismissible: false, color: Colors.transparent))
    ]);
  }
}
