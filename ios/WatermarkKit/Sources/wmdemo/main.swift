import Foundation
import AVFoundation
import CryptoKit
import WatermarkKit

let key = SymmetricKey(data: Data(repeating: 0xAB, count: 32))
let accountID = "70001234567"
let W = 1280, H = 720, N = 450
let fps: Int32 = 30
let bitRate = Int(0.129 * Double(W * H) * Double(fps))

let wm = TemporalWatermark()
let payload = WatermarkPayload.make(appTransactionID: accountID, key: key, nonce: 0x0BADC0FFEE)
let offsets = wm.frameOffsets(bits: payload.bits, frameCount: N)

let url = URL(fileURLWithPath: "/tmp/wm-demo.mp4")
try? FileManager.default.removeItem(at: url)
let writer = try! AVAssetWriter(outputURL: url, fileType: .mp4)
let input = AVAssetWriterInput(mediaType: .video, outputSettings: [
    AVVideoCodecKey: AVVideoCodecType.h264, AVVideoWidthKey: W, AVVideoHeightKey: H,
    AVVideoCompressionPropertiesKey: [AVVideoAverageBitRateKey: bitRate,
                                      AVVideoExpectedSourceFrameRateKey: fps]])
input.expectsMediaDataInRealTime = false
let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input,
    sourcePixelBufferAttributes: [
        kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
        kCVPixelBufferWidthKey as String: W, kCVPixelBufferHeightKey as String: H])
writer.add(input); writer.startWriting(); writer.startSession(atSourceTime: .zero)

for i in 0..<N {
    while !input.isReadyForMoreMediaData { usleep(500) }
    var buf: CVPixelBuffer?
    CVPixelBufferPoolCreatePixelBuffer(nil, adaptor.pixelBufferPool!, &buf)
    guard let pb = buf else { continue }
    CVPixelBufferLockBaseAddress(pb, [])
    let y = CVPixelBufferGetBaseAddressOfPlane(pb, 0)!.assumingMemoryBound(to: UInt8.self)
    let ys = CVPixelBufferGetBytesPerRowOfPlane(pb, 0)
    var s = UInt64(i &+ 1) &* 0x9E3779B97F4A7C15
    let t = Double(i)
    for r in 0..<H {
        let line = y + r * ys
        for c in 0..<W {
            let g = 70.0 + 50.0 * sin((Double(c) + t*2)/140) + 30.0 * cos((Double(r) - t*1.5)/190)
            s = (s &* 6364136223846793005) &+ 1442695040888963407
            line[c] = UInt8(clamping: Int(g + Double((s >> 33) % 13) - 6.0))
        }
    }
    let uv = CVPixelBufferGetBaseAddressOfPlane(pb, 1)!.assumingMemoryBound(to: UInt8.self)
    let uvs = CVPixelBufferGetBytesPerRowOfPlane(pb, 1)
    for r in 0..<(H/2) { let l = uv + r*uvs; for i2 in 0..<W { l[i2] = 128 } }
    CVPixelBufferUnlockBaseAddress(pb, [])
    VideoLuma.apply(offset: offsets[i], to: pb)
    adaptor.append(pb, withPresentationTime: CMTime(value: CMTimeValue(i), timescale: fps))
}
input.markAsFinished()
let sem = DispatchSemaphore(value: 0)
writer.finishWriting { sem.signal() }
sem.wait()
print("wrote /tmp/wm-demo.mp4 for account \(accountID)")
