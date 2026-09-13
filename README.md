# Splatter — personal build for HONOR Magic 8 Pro (v1.2-magic8pro)

## What this is
A personal build of the open-source [Splatter](https://github.com/damonjess/Splatter) app: record a video-like scan with ARCore, and the phone reconstructs it on-device into a 3D splat/point cloud (PLY + .splat), viewable in the built-in 3D viewer. Built for a HONOR Magic 8 Pro, which is on [Google's official ARCore supported devices list](https://developers.google.com/ar/devices) with Depth API support, so raw depth capture works.

## What's new in v1.2 — On-device training
After the scan finishes and the initial point cloud is unprojected, the app now runs an **on-device Gaussian splat training** pass before exporting the model. This iteratively refines splat parameters using multi-view observations from the captured frames:

- **Multi-view color fusion** — each splat's RGB is refined by projecting it into multiple camera viewpoints and gradient-descenting toward the observed pixel color, instead of using a single-view color
- **Depth-gated visibility** — before comparing a splat to an RGB pixel, the projected depth is checked against the saved raw depth at that pixel. Only splats whose depth matches (within 8 cm tolerance) are trained, preventing occluded/background points from learning wrong colors
- **Opacity confidence** — splats with high cross-view color error have their opacity reduced (likely edge/noise points); consistent splats get increased opacity
- **Scale smoothing** — splat sizes are nudged toward a distance-aware target for more uniform appearance
- **Pruning** — splats with very low opacity after training are removed

The training runs 10 iterations by default, using 6 frames per iteration (rotated each iteration for coverage). Images are downscaled to 1/4 resolution for the comparison pass to keep it fast on mobile. The processing screen shows live training progress: iteration count, loss, and Gaussian count.

This is not full differentiable 3DGS training (which requires CUDA-style differentiable rasterization), but a practical on-device refinement pass that measurably improves color accuracy and reduces noise.

## What's tuned for your Magic 8 Pro (vs. stock repo)
- Capture rate raised from ~6-7 to ~10 frames per second
- Full-density reconstruction: every valid depth pixel is used (4x denser than stock)
- Bulk pixel reads — processing is significantly faster than stock
- Exact depth dimensions saved at capture time and read during processing (fixes a stock bug that guessed the depth image size)
- Depth range extended from 3.5 m to 5 m
- 3 million point safety cap so room-scale scans can't exhaust memory
- Screen stays awake while scanning
- Signed release build so you can update over the same install
- On-device training pass after reconstruction (v1.2)

## Install on your phone (once)
1. Copy the APK to your phone (download, USB, or cloud drive).
2. Tap the file. If asked, allow "Install unknown apps" for the app you opened it with — MagicOS will walk you through it.
3. On first launch, grant the camera permission and let it install/update "Google Play Services for AR" if prompted.

## How to scan (like Scaniverse)
1. Open the app → tap to start a new scan.
2. Move slowly around the object, keeping 0.3–1.5 m away. Watch the status: "Ready (Tracking Locked)" means good tracking; "Searching for features" means slow down.
3. Press stop when done — processing and training run on the phone, then the 3D model opens in the viewer.
4. Models are stored in `Android/data/com.example.splatter/files/scans/` on your phone — each scan has `model.ply` (openable in most 3D/splat software) and `model.splat`.

## Signing secrets (kept out of this repo)
Release signing uses two files at the project root that are **git-ignored and never pushed**:
- `splatter-magic8pro.keystore` — the signing key (any future Splatter build must be signed with this same key, or Android will force an uninstall first)
- `keystore.properties` — points the build at the keystore (storeFile, storePassword, keyAlias, keyPassword)

If either is missing, the release build still works — it just produces an unsigned APK. Keep backups of both files somewhere safe (offline storage, not in this repo).

## If something looks wrong
This was built without access to your physical device, so if a scan comes out warped or empty, note what happened and re-run it — the capture pipeline now saves exact depth metadata, and scan datasets can be pulled from the folder above for debugging. Point clouds may show noise at distance (>3 m) — that's ARCore raw depth, not a bug.
