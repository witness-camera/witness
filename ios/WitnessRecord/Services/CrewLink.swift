import CoreImage.CIFilterBuiltins
import Foundation
import UIKit

/// A crew packed into a link, for a QR code shown in person or an invite
/// sent by message.
///
/// The crew travels in the URL's fragment, after the `#`: browsers never send
/// a fragment to the server, so opening the web link reveals nothing about
/// the crew to witnesscamera.com, which only hands it on to the app.
enum CrewLink {
    static let webBase = "https://witnesscamera.com/crew"

    /// The link to share. The web form, because a `witnessrecord://` link
    /// isn't tappable in most messaging apps; the page passes it to the app.
    static func url(for crew: Crew) -> URL? {
        guard let token = token(for: crew) else { return nil }
        return URL(string: "\(webBase)#\(token)")
    }

    /// Accepts both the web link and the app's own scheme
    /// (`witnessrecord://crew#…`), which the web page forwards to.
    static func crew(from url: URL) -> Crew? {
        let isWeb = url.host == "witnesscamera.com" && url.path.hasPrefix("/crew")
        let isApp = url.scheme == "witnessrecord" && url.host == "crew"
        guard isWeb || isApp, let token = url.fragment, !token.isEmpty else { return nil }
        return crew(fromToken: token)
    }

    static func token(for crew: Crew) -> String? {
        guard let json = try? encoder.encode(crew),
              let packed = try? (json as NSData).compressed(using: .zlib) as Data else { return nil }
        return packed.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    static func crew(fromToken token: String) -> Crew? {
        var base64 = token
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        base64 += String(repeating: "=", count: (4 - base64.count % 4) % 4)
        guard let packed = Data(base64Encoded: base64),
              let json = try? (packed as NSData).decompressed(using: .zlib) as Data else { return nil }
        return try? decoder.decode(Crew.self, from: json)
    }

    static func qrImage(for url: URL, scale: CGFloat = 10) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(url.absoluteString.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage?
                .transformed(by: CGAffineTransform(scaleX: scale, y: scale)),
              let cg = CIContext().createCGImage(output, from: output.extent) else { return nil }
        return UIImage(cgImage: cg)
    }

    private static let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .secondsSince1970
        return e
    }()

    private static let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .secondsSince1970
        return d
    }()
}
