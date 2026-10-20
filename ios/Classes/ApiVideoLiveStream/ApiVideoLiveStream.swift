//
//  ApiVideoLiveStream.swift
//
//  Vendored from api.video's swift-live-stream 1.4.1 (the `ApiVideoLiveStream`
//  pod), MIT licensed — see ios/ApiVideoLiveStream-LICENSE.md. Copied in so the
//  plugin can reach HaishinKit's RTMPConnection and RTMPStream, which the pod
//  keeps private. Changes from the pod are marked "Fork:".
//

import AVFoundation
import Foundation
import HaishinKit
#if !os(macOS)
import UIKit
#endif
import VideoToolbox

public class ApiVideoLiveStream {
    private let rtmpStream: RTMPStream
    private let rtmpConnection = RTMPConnection()

    private var streamKey: String = ""
    private var url: String = ""

    private var isAudioConfigured = false
    private var isVideoConfigured = false

    /// Fork: the video bitrate the host chose. HaishinKit's adaptive strategy
    /// lowers the encoder below it when the uplink cannot keep up, and climbs
    /// back towards it once it can.
    private var maxVideoBitrate = 0

    /// Fork: HaishinKit's last verdict on the uplink. It only gives one when
    /// the send queue grew on every sample (insufficient) or on none
    /// (sufficient); in between, the last verdict stands.
    private var isBandwidthSufficient = true

    /// Fork: lowers and raises the video bitrate; see
    /// [SteadyAdaptiveBitRateStrategy].
    private var adaptiveStrategy: SteadyAdaptiveBitRateStrategy?

    /// The delegate of the ApiVideoLiveStream
    public weak var delegate: ApiVideoLiveStreamDelegate?

    ///  Getter and Setter for an AudioConfig
    public var audioConfig: AudioConfig {
        get {
            AudioConfig(bitrate: self.rtmpStream.audioSettings.bitRate)
        }
        set {
            self.prepareAudio(audioConfig: newValue)
        }
    }

    /// Getter and Setter for a VideoConfig
    public var videoConfig: VideoConfig {
        get {
            VideoConfig(
                bitrate: Int(self.rtmpStream.videoSettings.bitRate),
                resolution: CGSize(
                    width: Int(self.rtmpStream.videoSettings.videoSize.width),
                    height: Int(self.rtmpStream.videoSettings.videoSize.height)
                ),
                fps: self.rtmpStream.frameRate,
                gopDuration: TimeInterval(self.rtmpStream.videoSettings.maxKeyFrameIntervalDuration)
            )
        }
        set {
            self.prepareVideo(videoConfig: newValue)
        }
    }

    /// Getter and Setter for the Bitrate number for the video
    public var videoBitrate: Int {
        get {
            self.rtmpStream.videoSettings.bitRate
        }
        set(newValue) {
            self.rtmpStream.videoSettings.bitRate = newValue
        }
    }

    private var lastCamera: AVCaptureDevice?

    /// Camera position
    public var cameraPosition: AVCaptureDevice.Position {
        get {
            guard let position = rtmpStream.videoCapture(for: 0)?.device?.position else {
                return AVCaptureDevice.Position.unspecified
            }
            return position
        }
        set(newValue) {
            self.attachCamera(newValue)
        }
    }

    /// Camera device
    public var camera: AVCaptureDevice? {
        get {
            self.rtmpStream.videoCapture(for: 0)?.device
        }
        set(newValue) {
            self.attachCamera(newValue)
        }
    }

    /// Mutes or unmutes audio capture.
    public var isMuted: Bool {
        get {
            !self.rtmpStream.hasAudio
        }
        set(newValue) {
            self.rtmpStream.hasAudio = !newValue
        }
    }

    #if os(iOS)
    /// Zoom on the video capture
    public var zoomRatio: CGFloat {
        get {
            guard let device = rtmpStream.videoCapture(for: 0)?.device else {
                return 1.0
            }
            return device.videoZoomFactor
        }
        set(newValue) {
            guard let device = rtmpStream.videoCapture(for: 0)?.device, newValue >= 1,
                  newValue < device.activeFormat.videoMaxZoomFactor else
            {
                return
            }
            do {
                try device.lockForConfiguration()
                device.videoZoomFactor = newValue
                device.unlockForConfiguration()
            } catch let error as NSError {
                print("Error while locking device for zoom ramp: \(error)")
            }
        }
    }
    #endif

    /// Creates a new ApiVideoLiveStream object without a preview
    /// - Parameters:
    ///   - initialAudioConfig: The ApiVideoLiveStream's initial AudioConfig
    ///   - initialVideoConfig: The ApiVideoLiveStream's initial VideoConfig
    ///   - initialCamera: The ApiVideoLiveStream's initial camera device
    public init(
        initialAudioConfig: AudioConfig? = AudioConfig(),
        initialVideoConfig: VideoConfig? = VideoConfig(),
        initialCamera: AVCaptureDevice? = AVCaptureDevice.default(
            .builtInWideAngleCamera,
            for: .video,
            position: .back
        )
    ) throws {
        #if os(iOS)
        let session = AVAudioSession.sharedInstance()

        // https://stackoverflow.com/questions/51010390/avaudiosession-setcategory-swift-4-2-ios-12-play-sound-on-silent
        try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetooth])
        // Fork: iOS mutes vibration while the microphone records, which
        // silenced the host's weak-connection alert for the whole broadcast.
        if #available(iOS 13.0, *) {
            try? session.setAllowHapticsAndSystemSoundsDuringRecording(true)
        }
        try session.setActive(true)
        #endif

        self.rtmpStream = RTMPStream(connection: self.rtmpConnection)

        // Force default resolution because HK default resolution is not supported (480x272)
        self.rtmpStream.videoSettings = VideoCodecSettings(videoSize: .init(width: 1_280, height: 720))

        #if os(iOS)
        if let orientation = DeviceUtil.videoOrientation(by: Self.interfaceOrientation) {
            self.rtmpStream.videoOrientation = orientation
        }
        #endif

        if let initialCamera = initialCamera {
            self.attachCamera(initialCamera)
        }
        if let initialVideoConfig = initialVideoConfig {
            self.prepareVideo(videoConfig: initialVideoConfig)
        }

        self.attachAudio()
        if let initialAudioConfig = initialAudioConfig {
            self.prepareAudio(audioConfig: initialAudioConfig)
        }

        #if !os(macOS)
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(self.didEnterBackground(_:)),
            name: UIApplication.didEnterBackgroundNotification,
            object: nil
        )
        #endif

        self.rtmpConnection.addEventListener(.rtmpStatus, selector: #selector(self.rtmpStatusHandler), observer: self)
        self.rtmpConnection.addEventListener(.ioError, selector: #selector(self.rtmpErrorHandler), observer: self)
        // Fork: bandwidth verdicts and per-second stats.
        self.rtmpConnection.delegate = self

        #if os(iOS)
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(self.orientationDidChange(_:)),
            name: UIDevice.orientationDidChangeNotification,
            object: nil
        )
        #endif
    }

    #if !os(macOS)
    /// Creates a new ApiVideoLiveStream object with a UIView as preview
    /// - Parameters:
    ///   - preview: The UIView where to display the preview of camera
    ///   - initialAudioConfig: The ApiVideoLiveStream's new AudioConfig
    ///   - initialVideoConfig: The ApiVideoLiveStream's new VideoConfig
    ///   - initialCamera: The ApiVideoLiveStream's initial camera device
    public convenience init(
        preview: UIView,
        initialAudioConfig: AudioConfig? = AudioConfig(),
        initialVideoConfig: VideoConfig? = VideoConfig(),
        initialCamera: AVCaptureDevice? = AVCaptureDevice.default(
            .builtInWideAngleCamera,
            for: .video,
            position: .back
        )
    ) throws {
        try self.init(
            initialAudioConfig: initialAudioConfig,
            initialVideoConfig: initialVideoConfig,
            initialCamera: initialCamera
        )

        let mthkView = MTHKView(frame: preview.bounds)
        mthkView.translatesAutoresizingMaskIntoConstraints = false
        mthkView.videoGravity = AVLayerVideoGravity.resizeAspectFill
        mthkView.attachStream(self.rtmpStream)

        preview.addSubview(mthkView)

        let maxWidth = mthkView.widthAnchor.constraint(lessThanOrEqualTo: preview.widthAnchor)
        let maxHeight = mthkView.heightAnchor.constraint(lessThanOrEqualTo: preview.heightAnchor)
        let width = mthkView.widthAnchor.constraint(equalTo: preview.widthAnchor)
        let height = mthkView.heightAnchor.constraint(equalTo: preview.heightAnchor)
        let centerX = mthkView.centerXAnchor.constraint(equalTo: preview.centerXAnchor)
        let centerY = mthkView.centerYAnchor.constraint(equalTo: preview.centerYAnchor)

        width.priority = .defaultHigh
        height.priority = .defaultHigh

        NSLayoutConstraint.activate([
            maxWidth, maxHeight, width, height, centerX, centerY
        ])
    }
    #endif

    /// Creates a new ApiVideoLiveStream object with a NetStreamDrawable
    /// - Parameters:
    ///   - preview: The NetStreamDrawable where to display the preview of camera
    ///   - initialAudioConfig: The ApiVideoLiveStream's new AudioConfig
    ///   - initialVideoConfig: The ApiVideoLiveStream's new VideoConfig
    ///   - initialCamera: The ApiVideoLiveStream's initial camera device
    public convenience init(
        preview: IOStreamDrawable,
        initialAudioConfig: AudioConfig? = AudioConfig(),
        initialVideoConfig: VideoConfig? = VideoConfig(),
        initialCamera: AVCaptureDevice? = AVCaptureDevice.default(
            .builtInWideAngleCamera,
            for: .video,
            position: .back
        )
    ) throws {
        try self.init(
            initialAudioConfig: initialAudioConfig,
            initialVideoConfig: initialVideoConfig,
            initialCamera: initialCamera
        )
        preview.attachStream(self.rtmpStream)
    }

    deinit {
        #if os(iOS)
        NotificationCenter.default.removeObserver(self, name: UIDevice.orientationDidChangeNotification, object: nil)
        #endif
        #if !os(macOS)
        NotificationCenter.default.removeObserver(self, name: UIApplication.didEnterBackgroundNotification, object: nil)
        #endif
        rtmpConnection.removeEventListener(.rtmpStatus, selector: #selector(rtmpStatusHandler), observer: self)
        rtmpConnection.removeEventListener(.ioError, selector: #selector(rtmpErrorHandler), observer: self)
    }

    private func attachCamera(_ cameraPosition: AVCaptureDevice.Position) {
        let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: cameraPosition)
        self.attachCamera(camera)
    }

    private func attachCamera(_ camera: AVCaptureDevice?) {
        self.lastCamera = camera

        self.rtmpStream.attachCamera(camera, channel: 0) { videoCaptureUnit, error in
            if let error {
                print("======== Camera error ==========")
                print(error)
                self.delegate?.videoError(error)
                return
            }

            if let camera {
                videoCaptureUnit?.isVideoMirrored = camera.position == .front
            }
            #if os(iOS)
            // videoCaptureUnit.preferredVideoStabilizationMode = AVCaptureVideoStabilizationMode
            //   .auto // Add latency to video
            #endif

            guard let device = videoCaptureUnit?.device else {
                return
            }
            self.rtmpStream.lockQueue.async {
                do {
                    try device.lockForConfiguration()
                    if device.isExposureModeSupported(.continuousAutoExposure) {
                        device.exposureMode = .continuousAutoExposure
                    }
                    if device.isFocusModeSupported(.continuousAutoFocus) {
                        device.focusMode = .continuousAutoFocus
                    }
                    device.unlockForConfiguration()
                } catch {
                    print("Could not lock device for exposure and focus: \(error)")
                }
            }
        }
    }

    private func prepareVideo(videoConfig: VideoConfig) {
        self.rtmpStream.frameRate = videoConfig.fps
        self.rtmpStream.sessionPreset = AVCaptureSession.Preset.high

        let resolution = videoConfig.resolution
        let width = self.rtmpStream.videoOrientation
            .isLandscape ? max(resolution.width, resolution.height) : min(resolution.width, resolution.height)
        let height = self.rtmpStream.videoOrientation
            .isLandscape ? min(resolution.width, resolution.height) : max(resolution.width, resolution.height)

        self.rtmpStream.videoSettings = VideoCodecSettings(
            videoSize: CGSize(width: width, height: height),
            bitRate: videoConfig.bitrate,
            profileLevel: kVTProfileLevel_H264_Baseline_5_2 as String,
            maxKeyFrameIntervalDuration: Int32(videoConfig.gopDuration)
        )
        // Fork: lower the bitrate on a weak uplink instead of queueing frames
        // the connection cannot carry, which froze viewers.
        self.maxVideoBitrate = videoConfig.bitrate
        let strategy = SteadyAdaptiveBitRateStrategy(mamimumVideoBitrate: videoConfig.bitrate)
        self.adaptiveStrategy = strategy
        self.rtmpStream.bitrateStrategy = strategy

        self.isVideoConfigured = true
    }

    private func attachAudio() {
        self.rtmpStream.attachAudio(AVCaptureDevice.default(for: AVMediaType.audio)) { error in
            print("======== Audio error ==========")
            print(error)
            self.delegate?.audioError(error)
        }
    }

    private func prepareAudio(audioConfig: AudioConfig) {
        self.rtmpStream.audioSettings = AudioCodecSettings(
            bitRate: audioConfig.bitrate
        )

        self.isAudioConfigured = true
    }

    /// Start your livestream
    /// - Parameters:
    ///   - streamKey: The key of your live
    ///   - url: The url of your rtmp server, by default it's rtmp://broadcast.api.video/s
    /// - Returns: Void
    public func startStreaming(streamKey: String, url: String = "rtmp://broadcast.api.video/s") throws {
        if streamKey.isEmpty {
            throw LiveStreamError.IllegalArgumentError("Stream key must not be empty")
        }
        if url.isEmpty {
            throw LiveStreamError.IllegalArgumentError("URL must not be empty")
        }
        if !self.isAudioConfigured || !self.isVideoConfigured {
            throw LiveStreamError.IllegalOperationError("Missing audio and/or video configuration")
        }

        self.streamKey = streamKey
        self.url = url
        self.isBandwidthSufficient = true

        self.rtmpConnection.connect(url)
    }

    /// Stop your livestream
    /// - Returns: Void
    public func stopStreaming() {
        let isConnected = self.rtmpConnection.connected
        self.rtmpConnection.close()
        if isConnected {
            self.delegate?.disconnection()
        }
    }

    public func startPreview() {
        guard let lastCamera = lastCamera else {
            print("No camera has been set")
            return
        }
        self.attachCamera(lastCamera)
        self.attachAudio()
    }

    public func stopPreview() {
        self.rtmpStream.attachCamera(nil, channel: 0)
        self.rtmpStream.attachAudio(nil)
    }

    @objc
    private func rtmpStatusHandler(_ notification: Notification) {
        let e = Event.from(notification)
        guard let data: ASObject = e.data as? ASObject,
              let code: String = data["code"] as? String,
              let level: String = data["level"] as? String else
        {
            print("rtmpStatusHandler: failed to parse event: \(e)")
            return
        }
        switch code {
        case RTMPConnection.Code.connectSuccess.rawValue:
            self.rtmpStream.publish(self.streamKey)

        case RTMPStream.Code.publishStart.rawValue:
            // Fork: a fresh connection gets its grace period.
            self.adaptiveStrategy?.restart()
            self.isBandwidthSufficient = true
            self.delegate?.connectionSuccess()

        case RTMPConnection.Code.connectClosed.rawValue:
            self.delegate?.disconnection()

        default:
            if level == "error" {
                self.delegate?.connectionFailed(code)
            }
        }
    }

    @objc
    private func rtmpErrorHandler(_ notification: Notification) {
        let e = Event.from(notification)
        print("rtmpErrorHandler: \(e)")
        DispatchQueue.main.async {
            self.rtmpConnection.connect(self.url)
        }
    }

    #if os(iOS)
    /// Fork: the window scene's orientation. The pod read
    /// `UIApplication.statusBarOrientation`, which a UIScene app cannot rely
    /// on: under it the capture stayed portrait while the interface had
    /// turned to landscape, and a portrait picture went out stretched across
    /// a 16:9 frame.
    private static var interfaceOrientation: UIInterfaceOrientation {
        if #available(iOS 13.0, *), let scene = Self.windowScene {
            return scene.interfaceOrientation
        }
        return UIApplication.shared.statusBarOrientation
    }

    @available(iOS 13.0, *)
    private static var windowScene: UIWindowScene? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        return scenes.first { $0.activationState == .foregroundActive } ?? scenes.first
    }

    /// Fork: whether the interface may turn to `orientation` right now — the
    /// app's own orientation lock, as the root view controller reports it.
    private static func interfaceAllows(_ orientation: AVCaptureVideoOrientation) -> Bool {
        let window: UIWindow?
        if #available(iOS 13.0, *) {
            let windows = Self.windowScene?.windows ?? []
            window = windows.first { $0.isKeyWindow } ?? windows.first
        } else {
            window = UIApplication.shared.keyWindow
        }
        // The app-wide set is Info.plist's; the lock a screen asks for (Flutter's
        // setPreferredOrientations) lives on the root view controller.
        var allowed = UIApplication.shared.supportedInterfaceOrientations(for: window)
        if let root = window?.rootViewController {
            allowed.formIntersection(root.supportedInterfaceOrientations)
        }
        // AVCaptureVideoOrientation and UIInterfaceOrientation share raw
        // values, and each mask bit is 1 << that value.
        let mask = UIInterfaceOrientationMask(rawValue: 1 << UInt(orientation.rawValue))
        return allowed.contains(mask)
    }

    @objc
    private func orientationDidChange(_: Notification) {
        // Fork: the device turns first and the interface follows, so the
        // interface read here can still be the orientation it is leaving, and
        // a turn from one landscape to the other would keep the old one —
        // upside down. Follow the device instead, but only where the
        // interface can follow it too: a phone held upright under a
        // landscape-locked screen keeps the landscape capture.
        guard let orientation = DeviceUtil.videoOrientation(by: UIDevice.current.orientation),
              Self.interfaceAllows(orientation) else {
            return
        }

        self.rtmpStream.lockQueue.async {
            self.rtmpStream.videoOrientation = orientation

            let currentVideoSize = self.rtmpStream.videoSettings.videoSize
            var newVideoSize: CGSize
            if self.rtmpStream.videoOrientation.isLandscape {
                newVideoSize = CGSize(
                    width: max(currentVideoSize.width, currentVideoSize.height),
                    height: min(currentVideoSize.width, currentVideoSize.height)
                )
            } else {
                newVideoSize = CGSize(
                    width: min(currentVideoSize.width, currentVideoSize.height),
                    height: max(currentVideoSize.width, currentVideoSize.height)
                )
            }
            self.rtmpStream.videoSettings.videoSize = newVideoSize
        }
    }
    #endif

    #if !os(macOS)
    @objc
    private func didEnterBackground(_: Notification) {
        self.stopStreaming()
    }
    #endif
}

public protocol ApiVideoLiveStreamDelegate: AnyObject {
    /// Called when the connection to the rtmp server is successful
    func connectionSuccess()

    /// Called when the connection to the rtmp server failed
    func connectionFailed(_ code: String)

    /// Called when the connection to the rtmp server is closed
    func disconnection()

    /// Called if an error happened during the audio configuration
    func audioError(_ error: Error)

    /// Called if an error happened during the video configuration
    func videoError(_ error: Error)

    /// Fork: called about once a second while publishing.
    /// - Parameters:
    ///   - sufficient: whether the uplink keeps up with what the encoder makes
    ///   - sentBitsPerSecond: what actually left the phone in the last second
    ///   - videoBitrate: the encoder's current video bitrate
    ///   - maxVideoBitrate: the video bitrate the host chose
    func bandwidthUpdated(sufficient: Bool, sentBitsPerSecond: Int, videoBitrate: Int, maxVideoBitrate: Int)
}

// Fork: RTMPConnection's delegate, which the pod never set.
extension ApiVideoLiveStream: RTMPConnectionDelegate {
    public func connection(_ connection: RTMPConnection, publishInsufficientBWOccured stream: RTMPStream) {
        // A connection still getting up to speed is not a weak one.
        if self.adaptiveStrategy?.isInGrace == true {
            return
        }
        self.isBandwidthSufficient = false
    }

    public func connection(_ connection: RTMPConnection, publishSufficientBWOccured stream: RTMPStream) {
        self.isBandwidthSufficient = true
    }

    public func connection(_ connection: RTMPConnection, updateStats stream: RTMPStream) {
        guard stream === self.rtmpStream else {
            return
        }
        #if DEBUG
        NSLog(
            "[bandwidth] sent=%d bit/s video=%d/%d bit/s sufficient=%@ grace=%@",
            Int(connection.currentBytesOutPerSecond) * 8,
            stream.videoSettings.bitRate,
            self.maxVideoBitrate,
            self.isBandwidthSufficient ? "yes" : "no",
            self.adaptiveStrategy?.isInGrace == true ? "yes" : "no"
        )
        #endif
        self.delegate?.bandwidthUpdated(
            sufficient: self.isBandwidthSufficient,
            sentBitsPerSecond: Int(connection.currentBytesOutPerSecond) * 8,
            videoBitrate: stream.videoSettings.bitRate,
            maxVideoBitrate: self.maxVideoBitrate
        )
    }
}

/// Fork: the bitrate strategy, replacing HaishinKit's
/// IOStreamVideoAdaptiveNetBitRateStrategy. That one cut the bitrate to what
/// went out in the last second on a single "insufficient" verdict — and at
/// the start of a stream, with TCP still ramping up and the first keyframes
/// queued, little goes out. On "Hoch" one such verdict took the bitrate to a
/// fraction of the chosen one, where it then crept back 10 % per 15 s, while
/// the connection had been fine all along.
///
/// This one waits [graceInterval] after each publish start, lowers only after
/// [lowerAfter] verdicts in a row (the same the app's warning waits for), and
/// then by a quarter to a half per step, towards what actually went out.
/// Raising is HaishinKit's: a tenth of the maximum after [raiseAfter] good
/// verdicts in a row. The Android regulator follows the same rules.
final class SteadyAdaptiveBitRateStrategy: IOStreamBitRateStrategyConvertible {
    static let graceInterval: TimeInterval = 8
    static let lowerAfter = 2
    static let raiseAfter = 15
    static let minimumVideoBitRate = 300_000

    weak var stream: IOStream?
    let mamimumVideoBitRate: Int
    let mamimumAudioBitRate: Int = 0

    private var graceUntil = Date.distantPast
    private var insufficientRun = 0
    private var sufficientRun = 0

    init(mamimumVideoBitrate: Int) {
        self.mamimumVideoBitRate = mamimumVideoBitrate
    }

    var isInGrace: Bool {
        Date() < graceUntil
    }

    func setUp() {
        stream?.videoSettings.bitRate = mamimumVideoBitRate
        restart()
    }

    /// A new connection: give it [graceInterval] to get up to speed.
    func restart() {
        graceUntil = Date().addingTimeInterval(Self.graceInterval)
        insufficientRun = 0
        sufficientRun = 0
    }

    func sufficientBWOccured(_ stats: IOStreamBitRateStats) {
        insufficientRun = 0
        guard let stream, stream.videoSettings.bitRate < mamimumVideoBitRate else {
            sufficientRun = 0
            return
        }
        sufficientRun += 1
        if Self.raiseAfter <= sufficientRun {
            stream.videoSettings.bitRate = min(
                stream.videoSettings.bitRate + mamimumVideoBitRate / 10,
                mamimumVideoBitRate
            )
            sufficientRun = 0
        }
    }

    func insufficientBWOccured(_ stats: IOStreamBitRateStats) {
        sufficientRun = 0
        guard let stream, !isInGrace else {
            return
        }
        insufficientRun += 1
        guard Self.lowerAfter <= insufficientRun else {
            return
        }
        insufficientRun = 0
        let current = stream.videoSettings.bitRate
        let sent = Int(stats.currentBytesOutPerSecond) * 8 - stream.audioSettings.bitRate
        let floor = max(mamimumVideoBitRate / 10, Self.minimumVideoBitRate)
        // Towards what went out, but by a quarter at least and half at most.
        let target = max(min(current * 3 / 4, max(sent, current / 2)), floor)
        if target < current {
            stream.videoSettings.bitRate = target
        }
    }
}

extension AVCaptureVideoOrientation {
    var isLandscape: Bool {
        self == .landscapeLeft || self == .landscapeRight
    }
}

public enum LiveStreamError: Error {
    case IllegalArgumentError(String)
    case IllegalOperationError(String)
}
