import SwiftUI
import AVFoundation

/// Hosts the capture layer's two display layers: the back camera fills the
/// view, the front camera sits as a small rounded picture-in-picture in the
/// top-right corner (shown only when dual-camera is active).
struct CameraPreviewView: UIViewRepresentable {
    let backLayer: AVSampleBufferDisplayLayer
    let frontLayer: AVSampleBufferDisplayLayer
    /// Present only for 10-bit capture, where the sample-buffer layer renders
    /// black; it replaces the back layer for those recordings.
    var hardwareLayer: AVCaptureVideoPreviewLayer?
    let showFront: Bool
    /// Swaps which camera fills the screen and which is the small inset —
    /// for someone using dual capture who wants the front camera as the
    /// main view. Only has an effect while `showFront` is true; ignored
    /// entirely in single-camera mode, so a stray `true` here can never
    /// shrink the only camera being recorded into the corner.
    var swapped: Bool = false

    func makeUIView(context: Context) -> DualPreviewUIView {
        let view = DualPreviewUIView()
        view.configure(back: backLayer, front: frontLayer)
        view.setHardwareLayer(hardwareLayer)
        return view
    }

    func updateUIView(_ uiView: DualPreviewUIView, context: Context) {
        uiView.setHardwareLayer(hardwareLayer)
        uiView.showFront = showFront
        uiView.swapped = swapped
        uiView.setNeedsLayout()
    }
}

final class DualPreviewUIView: UIView {
    private var backLayer: AVSampleBufferDisplayLayer?
    private var frontLayer: AVSampleBufferDisplayLayer?
    private var hardwareLayer: AVCaptureVideoPreviewLayer?
    var showFront = false
    var swapped = false

    /// Swaps the back viewfinder between the sample-buffer layer and a
    /// session-backed one. Idempotent: SwiftUI re-runs updateUIView freely.
    func setHardwareLayer(_ layer: AVCaptureVideoPreviewLayer?) {
        if layer !== hardwareLayer {
            hardwareLayer?.removeFromSuperlayer()
            hardwareLayer = layer
            if let layer {
                self.layer.insertSublayer(layer, at: 0)
            }
            setNeedsLayout()
        }
        // Applied every time, not only when the layer changes. This view is
        // recreated per recording while the sample-buffer layer it hides is
        // owned by the view model and outlives it: coming back from a 10-bit
        // recording, a fresh view holds no hardware layer and is handed none,
        // so an early exit here would leave the back camera hidden from the
        // previous session with nothing to turn it on again — a black rear
        // preview that only an app restart cleared.
        backLayer?.isHidden = layer != nil
    }

    func configure(back: AVSampleBufferDisplayLayer, front: AVSampleBufferDisplayLayer) {
        backLayer = back
        frontLayer = front
        backgroundColor = .black
        layer.addSublayer(back)
        layer.addSublayer(front)
        // Cosmetic corner-radius/border for whichever layer is currently the
        // small inset is applied fresh every layout pass below, since which
        // one that is can change when swapped.
    }

    /// Rounds and borders whichever layer is currently the small inset;
    /// clears both on whichever is currently filling the screen. Applied
    /// every layout pass rather than once in `configure`, because swapping
    /// moves this styling from one physical layer to the other.
    private func stylePip(_ layer: CALayer?, isPip: Bool) {
        layer?.cornerRadius = isPip ? 12 : 0
        layer?.masksToBounds = isPip
        layer?.borderWidth = isPip ? 2 : 0
        layer?.borderColor = isPip ? UIColor.white.withAlphaComponent(0.6).cgColor : nil
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        // Disable implicit animation so the layers track rotation/resize cleanly.
        CATransaction.begin()
        CATransaction.setDisableActions(true)

        let pipWidth = bounds.width * 0.28
        let pipHeight = pipWidth * 16.0 / 9.0
        let margin: CGFloat = 16
        let topInset = safeAreaInsets.top + margin
        let pipFrame = CGRect(
            x: bounds.width - pipWidth - margin,
            y: topInset,
            width: pipWidth,
            height: pipHeight)

        // Ignored outside dual-camera mode: with only one camera recording,
        // it always fills the screen regardless of a stray `swapped` value.
        let effectiveSwap = swapped && showFront

        // No transform on whichever layer is filling the screen. The
        // viewfinder does not turn with the phone, does not switch how it
        // scales, and does not change when a recording starts: it shows the
        // frame being recorded, at its own proportions, and stays put.
        // Turning the phone turns the screen with it, which is all a
        // viewfinder attached to a camera has ever done.
        let backFrame = effectiveSwap ? pipFrame : bounds
        let frontFrame = effectiveSwap ? bounds : pipFrame

        backLayer?.frame = backFrame
        // Substitutes visually for the back layer, so it always shares
        // whichever slot the back layer is currently in.
        hardwareLayer?.frame = backFrame
        frontLayer?.frame = frontFrame
        frontLayer?.isHidden = !showFront

        stylePip(backLayer, isPip: effectiveSwap)
        stylePip(hardwareLayer, isPip: effectiveSwap)
        stylePip(frontLayer, isPip: !effectiveSwap)

        // Whichever layer is the small inset must sit above whichever is
        // filling the screen, or the main layer's full-bounds frame
        // visually covers it. `configure()` fixes back below front once,
        // at setup, but swapping flips which one needs to be on top.
        if effectiveSwap {
            (hardwareLayer ?? backLayer)?.removeFromSuperlayer()
            if let hardwareLayer {
                layer.insertSublayer(hardwareLayer, above: frontLayer)
            } else if let backLayer {
                layer.insertSublayer(backLayer, above: frontLayer)
            }
        } else {
            frontLayer?.removeFromSuperlayer()
            if let frontLayer {
                if let hardwareLayer {
                    layer.insertSublayer(frontLayer, above: hardwareLayer)
                } else if let backLayer {
                    layer.insertSublayer(frontLayer, above: backLayer)
                }
            }
        }

        CATransaction.commit()
    }
}
