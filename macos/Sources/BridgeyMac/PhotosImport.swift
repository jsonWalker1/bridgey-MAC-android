import Foundation
import Photos

/// Adds a synced photo/video into the user's Photos library (add-only access — Bridgey never
/// reads or browses the library) so it shows up in Photos.app and syncs via iCloud Photos like
/// any other import, in addition to the plain-folder copy the sync folder already keeps.
enum PhotosImport {
    static func importAsset(at url: URL, isVideo: Bool, completion: @escaping (Bool) -> Void) {
        PHPhotoLibrary.requestAuthorization(for: .addOnly) { status in
            guard status == .authorized || status == .limited else {
                NSLog("PLUGIN photo sync: Photos library access not granted (status=%ld)", status.rawValue)
                DispatchQueue.main.async { completion(false) }
                return
            }
            PHPhotoLibrary.shared().performChanges({
                let request = PHAssetCreationRequest.forAsset()
                request.addResource(with: isVideo ? .video : .photo, fileURL: url, options: nil)
            }, completionHandler: { success, error in
                if let error {
                    NSLog("PLUGIN photo sync: could not add to Photos library: %@", error.localizedDescription)
                }
                DispatchQueue.main.async { completion(success) }
            })
        }
    }
}
