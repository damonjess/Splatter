# Scan diagnostics

When a scan comes out wrong, the app can only tell you what the pipeline *did* (the note on the
gallery card). This folder tells you **why**, from the captured data itself, on your computer
instead of your phone.

## Use it

```bash
# 1. pull a scan off the phone (read-only; nothing is changed on the device)
adb pull /sdcard/Android/data/com.example.splatter/files/scans/scan_XXXX ./scan_XXXX

# 2. analyse it (needs python3 + numpy)
python3 diagnostics/analyze_scan.py ./scan_XXXX
```

It prints a summary and writes `diagnostics/report-scan_XXXX.html` — a self-contained report with
embedded renders, so it can be opened anywhere or shared as-is.

## What it measures

| Check | What it tells you |
| --- | --- |
| Frames / path extent | How much the camera actually moved. A path of a few centimetres means the scan never orbited the subject. |
| Raw depth availability | How many frames carry depth and what share of pixels is valid in 0.3–5 m. Low values are why the app must fall back to photo-only stereo. |
| Pose / depth consistency | Each frame's depth is unprojected with the corrected ARCore poses and reprojected into the next frame, then compared with that frame's measured depth. Sub-centimetre agreement means the capture is geometrically sound; tens of centimetres means the poses (or the depth noise) are the problem. |
| Drift check | The same test across ~1 s of motion. This one is expected to be worse: it measures tracking drift plus noise amplification, and it is what makes long scans smear. |
| Reconstruction render | The raw depth fused with the poses in the app's camera convention, rendered as orthographic front/side/top projections. **Continuous surfaces = usable capture; isolated speckle = the capture is the problem, not the pipeline.** |

## Why this exists

The 411-frame object scan in this repo's history produced 4,933 scattered triangles in the app
while carrying 411 depth maps with 93.6% valid pixels and sub-centimetre pose/depth agreement.
The numbers in the report — not the symptom — identified the dense stage as the culprit and
confirmed that fusing the captured depth is the right fix.

`analyze_scan.py` reads only `pose_*.txt`, `intrinsics_*.txt`, `depth_*.raw` and `depthdims_*.txt`.
It never writes to the scan folder.

## Testing the 360° panorama path without turning around

`make_synthetic_360.py` writes a fake 360° capture in the app's own dataset format — 40 frames of
a scene that is an analytic function of direction, plus the `heading.txt` / `location.txt` the
capture side records — so the whole on-device panorama path (decode → stitch → metadata → the
readiness panel) can be exercised without holding a phone up and sweeping it:

```bash
python3 diagnostics/make_synthetic_360.py /tmp/pano360
adb shell mkdir -p /data/local/tmp/pano360 && adb push /tmp/pano360/. /data/local/tmp/pano360/
adb shell cp /data/local/tmp/pano360/* \
  /storage/emulated/0/Android/data/com.example.splatter/files/scans/scan_XXXX/
# then open that scan in the app
```

It also grades the result, from the file, against the requirements Google Maps applies:

```bash
adb pull .../scan_XXXX/panorama.jpg /tmp/device-panorama.jpg
python3 diagnostics/make_synthetic_360.py --verify  /tmp/device-panorama.jpg   # tags, GPS, size, verdict
python3 diagnostics/make_synthetic_360.py --compare /tmp/device-panorama.jpg  # pixels vs ground truth
```

`--compare` is the interesting one. Because the scene is known analytically and the stitcher's
reference frame is the first captured pose (identity for the generated sweep), every output pixel
has a correct answer, so the check measures the stitch itself rather than asserting that a file
exists. On a Magic 8 Pro this reproduced the scene to a mean error of **0.85 / 255** and a 95th
percentile of **2.0 / 255** over 523,264 sampled pixels — which would not be possible if the pose
convention, the sphere mapping or the blending were wrong.
