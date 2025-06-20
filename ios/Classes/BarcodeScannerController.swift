import Foundation
import Flutter
import UIKit
import AVFoundation

class BarcodeScannerController: UIViewController, AVCaptureMetadataOutputObjectsDelegate, FlutterStreamHandler {
    
    private let supportedCodeTypes = [AVMetadataObject.ObjectType.code39,
                                      AVMetadataObject.ObjectType.code93,
                                      AVMetadataObject.ObjectType.code128,
                                      AVMetadataObject.ObjectType.ean8,
                                      AVMetadataObject.ObjectType.ean13,
                                      AVMetadataObject.ObjectType.itf14,
                                      AVMetadataObject.ObjectType.upce]
    
    private var barcodeStream: FlutterEventSink?=nil
    
    private var videoPreviewLayer: AVCaptureVideoPreviewLayer?
    private let captureSession = AVCaptureSession()
    private let captureMetadataOutput = AVCaptureMetadataOutput()

    private var orientation: String = "portrait"
    private var selector: String = "back"

    public func getCurrentPosition() {}

    public func setOrientation(orientation: String) {
        self.orientation = orientation
    }

    public func setSelector(selector: String) {
        self.selector = selector
    }

    
    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        self.initBarcodeComponents()
    }
    
    override public func viewDidDisappear(_ animated: Bool) {
        captureSession.stopRunning()
    }
    
    public func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
        barcodeStream = events
        return nil
    }
    
    public func onCancel(withArguments arguments: Any?) -> FlutterError? {
        barcodeStream=nil
        return nil
    }
    
    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        switch call.method {
        case "startScanner":
            captureSession.startRunning()
            result(nil)
        case "stopScanner":
            captureSession.stopRunning()
            result(nil)
        case "toggleTorch":
            toggleFlash()
            result(nil)
        case "flipCamera":
            switchCamera()
            result(nil)
        case "closeCamera":
            close()
            result(nil)
        case "refocus":
            refocus()
            result(nil)
        case "updateOrientation":
            if let args = call.arguments as? [String: Any],
            let newOrientation = args["orientation"] as? String {
                self.updateOrientation(newOrientation)
                result(nil)
            } else {
                result(FlutterError(code: "invalid_arguments", message: "Missing orientation", details: nil))
            }
        default:
            result(FlutterMethodNotImplemented)
        }
    }
    
    // Inititlize components
    func initBarcodeComponents() {
        
        do {
            
            let deviceDiscoverySession = AVCaptureDevice.DiscoverySession(deviceTypes: [.builtInWideAngleCamera], mediaType: AVMediaType.video, position: .back)
            // Get the back-facing camera for capturing videos
            guard let captureDevice = deviceDiscoverySession.devices.first else {
                barcodeStream?(FlutterError(code: "native_scanner_failed", message: "Failed to get the camera device", details: nil))
                return
            }

            // ✨ Налаштування фокуса
            do {
                try captureDevice.lockForConfiguration()
                if captureDevice.isFocusModeSupported(.continuousAutoFocus) {
                    captureDevice.focusMode = .continuousAutoFocus
                } else if captureDevice.isFocusModeSupported(.autoFocus) {
                    captureDevice.focusMode = .autoFocus
                }
                captureDevice.unlockForConfiguration()
            } catch {
                print("⚠️ Не вдалося налаштувати фокус: \(error)")
            }
            
            // Get an instance of the AVCaptureDeviceInput class using the previous device object.
            let input = try AVCaptureDeviceInput(device: captureDevice)
            
            if captureSession.inputs.isEmpty {
                captureSession.addInput(input)
            }
            
            if captureSession.outputs.isEmpty  {
                captureSession.addOutput(captureMetadataOutput)
            }
            
            // Set delegate and use the default dispatch queue to execute the call back
            captureMetadataOutput.setMetadataObjectsDelegate(self, queue: DispatchQueue.main)
            captureMetadataOutput.metadataObjectTypes = supportedCodeTypes
            
            videoPreviewLayer = AVCaptureVideoPreviewLayer(session: captureSession)
            videoPreviewLayer?.videoGravity = .resizeAspectFill
            if (self.orientation == "portrait" || self.orientation == nil) {
                videoPreviewLayer?.connection?.videoOrientation = .portrait
            } else if (self.orientation == "landscapeLeft") {
                videoPreviewLayer?.connection?.videoOrientation = .landscapeRight
            } else if (self.orientation == "landscapeRight") {
                videoPreviewLayer?.connection?.videoOrientation = .landscapeLeft
            } else if self.orientation == "portraitDown" {
                videoPreviewLayer?.connection?.videoOrientation = .portraitUpsideDown
            }
            
            view.contentMode = UIView.ContentMode.scaleAspectFill
            view.layer.addSublayer(videoPreviewLayer!)
            
            DispatchQueue.main.async {
                self.videoPreviewLayer?.frame = self.view.bounds
            }
            
            DispatchQueue.global(qos: .userInitiated).async {
                self.captureSession.startRunning()
            }
            
        } catch {
            // If any error occurs, simply print it out and don't continue any more.
            print(error)
            return
        }
    }

    @objc func updateOrientation(_ orientation: String) {
    DispatchQueue.main.async {
            if orientation == "portrait" {
                self.videoPreviewLayer?.connection?.videoOrientation = .portrait
            } else if orientation == "landscapeLeft" {
                self.videoPreviewLayer?.connection?.videoOrientation = .landscapeRight
            } else if orientation == "landscapeRight" {
                self.videoPreviewLayer?.connection?.videoOrientation = .landscapeLeft
            } else if orientation == "portraitDown" {
                self.videoPreviewLayer?.connection?.videoOrientation = .portraitUpsideDown
            }
            
            self.videoPreviewLayer?.frame = self.view.bounds
        }
    }

    func close() {
        for input in captureSession.inputs {
            captureSession.removeInput(input)
        }

        for output in captureSession.outputs {
            captureSession.removeOutput(output)
        }
    }
    
    public func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {

        // Check if the metadataObjects array is not nil and it contains at least one object.
        if metadataObjects.count == 0 {
            barcodeStream?(FlutterError(code: "native_scanner_failed", message: "metadataObjects array is nil or doesn't contains any object", details: nil))
            return
        }

        // Get the metadata object.
        let metadataObj = metadataObjects[0] as! AVMetadataMachineReadableCodeObject
        if supportedCodeTypes.contains(metadataObj.type) {
            if metadataObj.stringValue != nil {
                barcodeStream?(["barcode": metadataObj.stringValue!, "format": convertBarcodeType(type: metadataObj.type)])
            }
        } else {
            barcodeStream?(FlutterError(code: "native_scanner_failed", message: "unsupported barcode type", details: "barcode type : \(metadataObj.type)"))
            barcodeStream?(["barcode": "", "format": -1])
        }
    }
    
    private func convertBarcodeType(type: AVMetadataObject.ObjectType) -> Int {
        switch type {
        case AVMetadataObject.ObjectType.code39:
            return BarcodeFormats.CODE_39
        case AVMetadataObject.ObjectType.code93:
            return BarcodeFormats.CODE_93
        case AVMetadataObject.ObjectType.code128:
            return BarcodeFormats.CODE_128
        case AVMetadataObject.ObjectType.ean8:
            return BarcodeFormats.EAN_8
        case AVMetadataObject.ObjectType.ean13:
            return BarcodeFormats.EAN_13
        case AVMetadataObject.ObjectType.itf14:
            return BarcodeFormats.ITF
        case AVMetadataObject.ObjectType.upce:
            return BarcodeFormats.UPC_E
        default:
            return -1
        }
    }
    
    func toggleFlash() {
        guard let device = getCaptureDeviceFromCurrentSession(session: captureSession) else {
            return
        }
        
        do {
            try device.lockForConfiguration()
            
            if (device.torchMode == AVCaptureDevice.TorchMode.off) {
                setFlashStatus(device: device, mode: .on)
            } else {
                setFlashStatus(device: device, mode: .off)
            }
            
            device.unlockForConfiguration()
        } catch {
            print(error)
        }
    }

    func refocus() {
        guard let device = getCaptureDeviceFromCurrentSession(session: captureSession) else {
            return
        }

        do {
            try device.lockForConfiguration()
            if device.isFocusModeSupported(.autoFocus) {
                device.focusMode = .autoFocus
            }
            device.unlockForConfiguration()
        } catch {
            print("⚠️ Error refocusing: \(error)")
        }
    }
    
    private func setFlashStatus(device: AVCaptureDevice, mode: AVCaptureDevice.TorchMode) {
        guard device.hasTorch else {
            return
        }
        
        do {
            try device.lockForConfiguration()
            
            if (mode == .off) {
                device.torchMode = AVCaptureDevice.TorchMode.off
            } else {
                // Treat .auto & .on equally.
                do {
                    try device.setTorchModeOn(level: 1.0)
                } catch {
                    print(error)
                }
            }
            
            device.unlockForConfiguration()
        } catch {
            print(error)
        }
    }
    
    private func switchCamera() {
        // Get the current active input.
        guard let currentInput = captureSession.inputs.first as? AVCaptureDeviceInput else { return }
        let newPosition = getInversePosition(position: currentInput.device.position);
        guard let device = getCaptureDeviceByPosition(position: newPosition) else { return }
        do {
            let newInput = try AVCaptureDeviceInput(device: device)
            // Replace current input with the new one.
            captureSession.removeInput(currentInput)
            captureSession.addInput(newInput)
            // Disable flash by default
            setFlashStatus(device: device, mode: .off)
        } catch let error {
            print(error)
            return
        }
    }

    private func getCaptureDeviceFromCurrentSession(session: AVCaptureSession) -> AVCaptureDevice? {
        // Get the current active input.
        guard let currentInput = captureSession.inputs.first as? AVCaptureDeviceInput else { return nil }
        return currentInput.device;
    }
    
    private func getCaptureDeviceByPosition(position: AVCaptureDevice.Position) -> AVCaptureDevice? {
        // List all capture devices
        let devices = AVCaptureDevice.DiscoverySession(deviceTypes: [ .builtInWideAngleCamera ], mediaType: AVMediaType.video, position: .unspecified).devices
        for device in devices {
            if device.position == position {
                return device
            }
        }
        
        return nil;
    }
    
    private func getInversePosition(position: AVCaptureDevice.Position) -> AVCaptureDevice.Position {
        if (position == .back) {
            return AVCaptureDevice.Position.front;
        }
        if (position == .front) {
            return AVCaptureDevice.Position.back;
        }
        // Fall back to camera in the back.
        return AVCaptureDevice.Position.back;
    }
}
