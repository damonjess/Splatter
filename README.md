# Splatter — personal build for HONOR Magic 8 Pro (v1.1-magic8pro)

## What this is
A personal build of the open-source [Splatter](https://github.com/damonjess/Splatter) app: record a video-like scan with ARCore, and the phone reconstructs it on-device into a 3D splat/point cloud (PLY + .splat), viewable in the built-in 3D viewer. Built for a HONOR Magic 8 Pro, which is on [Google's official ARCore supported devices list](https://developers.google.com/ar/devices) with Depth API support, so raw depth capture works.

## What's tuned for your Magic 8 Pro (vs. stock repo)
- Capture rate raised from ~6-7 to ~10 frames per second
- Full-density reconstruction: every valid depth pixel is used (4x denser than stock)
- Bulk pixel reads — processing is significantly faster than stock
- Exact depth dimensions saved at capture time and row-padding stripped, so reconstruction geometry is always correct (fixes a stock bug that guessed the depth image size)
- Depth range extended from 3.5 m to 5 m
- 3 million point safety cap so room-scale scans can't exhaust memory
- Screen stays awake while scanning
- Signed release build (v1.1-magic8pro) so you can update over the same install

## Install on your phone (once)
1. Copy `Splatter-Magic8Pro-v1.1.apk` to your phone (download, USB, or cloud drive).
2. Tap the file. If asked, allow "Install unknown apps" for the app you opened it with (e.g. Files or Chrome) — MagicOS will walk you through it.
3. On first launch, grant the camera permission and let it install/update "Google Play Services for AR" if prompted (it should already be on your phone since the device is ARCore-certified).

## How to scan (like Scaniverse)
1. Open the app → tap to start a new scan.
2. Move slowly around the object, keeping 0.3–1.5 m away. Watch the status: "Ready (Tracking Locked)" means good tracking; "Searching for features" means slow down.
3. Press stop when done — processing runs on the phone, then the 3D model opens in the viewer.
4. Models are stored in `Android/data/com.example.splatter/files/scans/` on your phone — each scan has `model.ply` (openable in most 3D/splat software) and `model.splat`.

## Signing secrets (kept out of this repo)
Release signing uses two files at the project root that are **git-ignored and never pushed**:
- `splatter-magic8pro.keystore` — the signing key (any future Splatter build must be signed with this same key, or Android will force an uninstall first)
- `keystore.properties` — points the build at the keystore (storeFile, storePassword, keyAlias, keyPassword)

If either is missing, the release build still works — it just produces an unsigned APK. Keep backups of both files somewhere safe (offline storage, not in this repo).

## If something looks wrong
This was built without access to your physical device, so if a scan comes out warped or empty, note what happened and re-run it — the capture pipeline now saves exact depth metadata, and scan datasets can be pulled from the folder above for debugging. Point clouds may show noise at distance (>3 m) — that's ARCore raw depth, not a bug.
