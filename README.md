# Splatter — personal build for HONOR Magic 8 Pro (v1.4-magic8pro)

## What this is
A personal build of the open-source [Splatter](https://github.com/damonjess/Splatter) app: record a video-like scan with ARCore, and the phone reconstructs it on-device — into a Gaussian splat/point cloud (PLY + .splat) or a textured triangle mesh (PLY + OBJ + GLB) — viewable and measurable in the built-in 3D viewer. Built for a HONOR Magic 8 Pro, which is on [Google's official ARCore supported devices list](https://developers.google.com/ar/devices) with Depth API support, so raw depth capture works.

Everything runs on-device: no upload, no account, no cloud reconstruction. Four capture modes feed a shared, unit-tested geometry core:

| Mode | Geometry from | Best for |
| --- | --- | --- |
| 📦 **Object** | ARCore raw depth | Single objects, 0.3–2.5 m, 5 mm voxels |
| 🏠 **Room** | ARCore raw depth + plane detection | Whole rooms, 0.5–5 m, 15 mm voxels |
| 📷 **Photo Mesh** | ARCore raw depth, fused and photo-textured | Solid matte subjects where you want a clean mesh |
| 🔭 **Photo SfM** | Photos only (feature matching + stereo) | Shiny, thin, dark or transparent subjects where depth fails |
| 🌐 **360 Photo** | Camera rotations (no geometry) | Stand in one spot and sweep — stitches a 360° panorama for Google Maps |

## What's new in this build — 360 Photo mode (one spot → Google Maps)

Pick **🌐 360 Photo**, stand still, tap REC and sweep the phone all the way around. Nothing is voxelised: the frames become one **4096 × 2048 equirectangular JPEG** carrying the GPano and GPS metadata Google Maps needs, viewable from the inside in the app before you publish it.

- **Stitched from camera rotations, not features.** ARCore already knows how the phone was oriented for every captured frame, so each output pixel is ray-cast onto the sphere, projected into every frame that sees it and blended by viewing angle and distance (`PanoramaStitcher`, pure Kotlin). That is why this works on the blank sky and plain walls where feature-matching panorama apps fail — and why it is checked against a synthetic 360° scene it is compared back to, pixel by pixel.
- **Frame selection.** Only frames at least 12° apart from the previous kept frame (a stationary phone must not contribute forty copies of one direction), spread around the circle, capped at 40. If the sweep covered less than 35% of the sphere the stitch is refused with "stand in one spot and sweep all the way around" rather than publishing a hole.
- **Metadata written by hand, byte for byte.** `GPano:ProjectionType=equirectangular`, the cropped and full pixel sizes, `PoseHeadingDegrees` taken from a **compass reading recorded at capture** (ARCore's world frame is arbitrary — it has no idea where north is, so the phone's rotation vector supplies the missing reference), plus EXIF GPS from `LocationManager`. `PanoramaMetadataTest` asserts the exact tags and the DMS rationals.
- **It checks its own output.** `PanoramaReadiness` reads the finished JPEG back off disk and grades it against Google's Photo Sphere requirements — 2:1, ≥7.5 MP, ≤75 MB, GPano tags, GPS position, compass heading — and the viewer shows ✓/✕ for each. The grading logic is tested on real bytes, including a western/southern hemisphere round trip.
- **Getting it onto Google Maps.** "Save to Photos" writes the JPEG into `Pictures/Splatter360` via MediaStore (the Maps contribution screen can only pick from your media library, so that copy is what makes *Contribute → Add photo* work); "Share 360°" hands the file to Google Photos' *Share → Add to Maps* flow; the ⓘ panel spells out the steps and can deep-link to the capture location in Maps. There is **no intent that uploads directly into Maps** — Google's documented routes are the Maps app itself or the Street View Publish API (OAuth scope `streetviewpublish`, 2,000 queries/day, free), so this builds a file that passes the requirements instead of pretending to post it.
- **A real 360 viewer.** Drag to look around, pinch to zoom, ray-cast perspective (`EquirectSampler`) — a flat window crop of an equirectangular image looks wrong the moment you look away from the horizon.
- **Honest about cost and drift.** Stitching 40 frames at 4096 × 2048 takes about **18 s** on a desktop-class JVM and **50–75 s on a Magic 8 Pro** (two runs; output resolution dominates, so the progress screen sits at 25% while the sphere is ray-cast). The scan's own note says `camera moved 2.3 m` when the capture was a walk rather than a pivot, which is where smearing comes from.

### Verified on the device, not just compiled

A synthetic 360° sweep — 40 frames of a scene that is an analytic function of direction, written in the app's own dataset format — was pushed into a scan folder and stitched by the app on a Magic 8 Pro (`diagnostics/make_synthetic_360.py`). The produced `panorama.jpg` was then pulled back off the phone and read by two independent parsers, the app's own `PanoramaReadiness` and the script's:

| Check | Result |
| --- | --- |
| Output | 4096 × 2048 (8.4 MP), 0.34 MB |
| GPano tags in the bytes | `ProjectionType=equirectangular`, full + cropped pixel sizes, `PoseHeadingDegrees=137.5` |
| EXIF GPS in the bytes | 51.500729, -0.124625 |
| App's readiness panel | all six requirements ✓, "Ready to publish to Google Maps" |
| **Geometry**: panorama compared with the scene that generated it | mean colour error **0.85 / 255**, 95th percentile **2.0 / 255** |

That last row is the one that matters: it is not a "the file is valid" check, it is the stitched image being measured against ground truth, so a wrong pose convention or a bad sphere mapping could not pass it.

## What's new in this build — scans that actually complete


> The build's `versionName` is still `1.4-magic8pro` (see `app/build.gradle.kts`); the changes below are what's in the working tree on top of it.

Scans used to come out empty ("0 points") or fail with "Photo SfM failed". Root cause found: **the camera poses were being read in the wrong frame.** ARCore stores `Camera.getPose()` in the OpenGL convention (+X right, +Y up, **-Z forward**), but every reconstruction stage in the app (depth unprojection, plane-sweep stereo, mesh colour baking, splat training) assumed the pixel-aligned frame (+Z forward, y down). The two differ by a 180° rotation about the camera's X axis, so each frame's cloud was rotated 180° about *its own* axis — clouds from different viewpoints never lined up, and the fused model came out as noise or nothing at all.

- **One pose conversion, applied at every read site** — new `CameraPose` helper converts `pose_*.txt` into pipeline space (`pose * diag(1,-1,-1)`); the depth, photo-mesh, photo-SfM and splat-training paths all use it, so no stage can drift again
- **Verified** by `CameraPoseTest`: reconstructed points are compared against the equations Google documents for ARCore, plus an integration test that fuses a synthetic wall and asserts it lands *in front of* the camera at the right depth
- **Photo SfM no longer dead-ends** — if SfM can't solve the camera path it retries once with looser matching, then falls back to the ARCore tracking poses captured with the photos (metric, drift-free, always self-consistent) and still produces a textured mesh
- **Point-cloud last resort** — if even that yields no triangles, the frames are unprojected into a coloured point cloud, so a scan is always viewable instead of failing to a toast
- **Outlier cleanup** — statistical outlier removal drops floating speckle before export (new `ModelCleanup`, unit tested), so models read as one clean object
- **Every scan explains itself** — the pipeline's own summary ("Photo SfM couldn't solve the camera path — rebuilt from AR tracking over 411 frames") is stored with the scan and shown on the gallery card and in the viewer's ⓘ measurements panel
- **GLB export** — new `GlbWriter` emits binary glTF 2.0 with baked vertex colours (`model_mesh.glb`), openable in Blender, Windows 3D Viewer, three.js and shareable straight from the gallery or viewer
- **Measurements panel** — the viewer's ⓘ button shows the model's real-world bounding-box size (cm/m, AR poses are metric), triangle/point counts, mode and exported file sizes

### Fixed after the first real-device test

The pose-convention fix above works: re-processing a 411-frame scan went from **0 points** to 4,933 triangles. The model itself, though, came out as scattered triangle shards rather than a solid object. Pulling the dataset off the phone and measuring it — see [diagnostics/](diagnostics/README.md) — showed why:

- The capture was **good**: 411 depth maps with **93.6% valid pixels**, and unprojecting each frame's depth and reprojecting it into the next frame agrees to **0.2–1.6 cm**. Pose convention, poses and depth sensor are all consistent.
- The app was throwing that away: Photo SfM ran **plane-sweep stereo** — depth guessed from census costs on a 128×96 grid — instead of the metric, per-pixel depth the phone had already recorded. A dozen coarse, noisy sweeps can only produce isolated triangles, which is exactly what the shards were.
- **Photo SfM now prefers real depth.** When the dataset carries usable depth it fuses that (through the depth pipeline, which also bakes the photos) and reports `Fused ARCore depth from N frames + photo colours into N triangles`; the stereo route still runs when depth is missing or unusable — the shiny / thin / dark subjects the mode exists for. `DepthAvailability` makes the call on the sampled frames, and it is unit tested.
- The depth-free stereo path is also hardened: a sweep cell is now accepted only when **at least two supporting views agree** on its depth (one view dipping below the cost threshold is how a sweep fabricates a lone triangle), and photo-only meshes drop connected components under **40 triangles** instead of 15.
- `diagnostics/analyze_scan.py` ships with it: pull any scan off the phone and get its pose/depth consistency, depth coverage and an orthographic render of the reconstruction as an HTML report, so a bad scan can be diagnosed from the data rather than from symptoms.

Expected on a re-process: a dense textured mesh of the captured area rather than shards. Known limitation — the same drift check measures **~15 cm over a second of motion**, so long scans will smear slightly where the two ends of the capture meet.

### Visual overhaul

The app used to run on the stock Material purple defaults — `SplatterTheme` existed but was never invoked, so every screen silently fell back to template colours. It's now one curated dark "studio" theme, applied app-wide, plus a rebuilt gallery and viewer:

- **Theme** — mint/periwinkle accents on deep ink surfaces, fully dark-first, no white launch flash (`ui/theme/Color.kt`, `Theme.kt`, `Type.kt`, `res/values/themes.xml`). The viewport is the hero: chrome stays quiet so the model reads clearly.
- **Gallery** — 2-column grid with thumbnails, live search, sorting (newest / oldest / name / points), a favourites filter, and a per-scan ⋮ menu (open, favourite, rename, save, share GLB, share ZIP, delete). Each card explains its own scan (see above) and shows a ⚡ Process button for scans that have frames but no model yet.
- **Viewer** — floating top bar (back, title, ⓘ, Share) over a bottom control sheet: **Spin** (turntable), **Wireframe**, **Background** (Studio / Grey / Light), **Reset**, and a splat-size slider. Save/Share rows for PLY, OBJ and GLB.
- **Framing fix** — `Reset` and double-tap used to snap the orbit camera back to the **AR world origin** (wherever you started recording, usually a metre from the subject), which read as "I can't view my scan". Reset now re-frames the model's bounding box captured at load time, and the splat viewer auto-frames the point-cloud radius instead of a hardcoded 2.5 m.
- **Gesture parity** — the splat viewer got the mesh viewer's turntable spin, flick inertia, double-tap reset and pinch-zoom, so both viewers behave the same.

## What's new in v1.4 — Photo SfM mode (true photo-only photogrammetry)

Pick **🔭 Photo SfM** on the scan screen and reconstruction runs from the **photos alone** — no depth sensor involvement. This is the mode that works where raw depth fails: shiny, thin, dark, or transparent subjects.

- **Feature detection & matching** — Shi-Tomasi corners (grid-spread, 800/frame) matched by census-signature shortlist + mutual-best check + NCC patch verification. Pure Kotlin, zero new dependencies.
- **Incremental Structure-from-Motion** — RANSAC 8-point essential matrix seeds the reconstruction (custom one-sided-Jacobi SVD), then each further frame registers against an already-posed neighbour via essential matching + a 1D scale search over known 3D points, followed by a DLT PnP polish. Alternating pose/point refinement rounds tidy the map.
- **Track building** — union-find linking over consecutive and short-range wider-baseline matches, deduped to one observation per frame, drift-bridging without poisoning tracks.
- **Dense plane-sweep stereo** — per keyframe, census-consistency depth along rays sampled at 40 depths into up to 5 supporting views, with texture/flatness/boundary validation so blank regions don't fabricate surfaces.
- **Mesh fusion & colors** — pseudo depth maps feed the same unit-tested DepthMeshFuser used by Photo Mesh (voxel dedup, depth-continuity, small-fragment cleanup), then MeshColorBaker bakes photo colors from all posed views.
- **Exports** — same as Photo Mesh: `model_mesh.ply` + `model_mesh.obj`, savable/shareable.
- **Covered by tests** — the whole geometry stack lives in the pure-Kotlin `processor/sfm` package, verified on JVM by synthetic-scene tests (analytic textured scene rendered from known poses: rotations, triangulation, two-view pose recovery with outlier rejection, full SfM registration, plane-sweep depth accuracy).

### Tips for Photo SfM
- Orbit **slowly** and keep **~60% view overlap** — SfM needs the same corners visible in 3+ views
- Matte, textured subjects reconstruct best (it's still stereo — blank surfaces have nothing to match)
- Expect cm-level detail at object distances (12 mm fusion voxel); the win is robustness, not resolution
- Processing takes noticeably longer than Photo Mesh (SfM + sweeps on-device); watch the progress steps

## What's new in v1.3 — Photo Mesh mode (Polycam-style photogrammetry)

Pick **📷 Photo Mesh** on the scan screen and the app now produces a **textured triangle mesh** from your scan, in addition to the existing splat output:

- **Depth fusion into a mesh** — up to 72 keyframes are unprojected and stitched into a single triangle mesh. Neighbouring depth samples become triangles, with rejection of triangles that span depth jumps or stretch beyond 10 voxels, and coverage suppression so overlapping views don't create duplicated surfaces. Vertices are shared across frames via a voxel-quantized spatial hash (4 mm voxel)
- **v1.3.1 mesh quality fixes** — the first Photo Mesh build produced hole-riddled meshes at close range. Root cause: the 10 mm fusion voxel was coarser than the 2–4 mm spacing of raw depth samples at object distance, so most triangles collapsed into degenerates and were rejected. Fixed by: 4 mm fusion voxel; a per-frame adaptive sampling stride (quad edges ≈ 1.5 voxels of world space, so corners never collapse); a 3×3 median filter on raw depth before fusion; low-confidence depth masking (per-pixel confidence < 75/255 is dropped); loosened depth-continuity tolerance to match ARCore raw-depth noise; and small-fragment cleanup (connected components under 15 triangles are removed as noise)
- **Photo color baking** — every mesh vertex is projected into up to 60 captured photos and its color is blended from all views that see it, weighted by viewing angle and distance. A raw-depth occlusion test prevents vertices from being colored by views where they are hidden behind other geometry — this is what gives the model the crisp "photogrammetry" look instead of a single-frame color snapshot
- **Mesh viewer** — the built-in 3D viewer renders the mesh with per-vertex photo colors, headlight shading, a wireframe toggle, and the same orbit/pan/zoom gestures as the splat viewer
- **PLY + OBJ export** — Photo Mesh scans are saved as `model_mesh.ply` (binary, vertices + faces + vertex colors, openable in MeshLab/Blender) and `model_mesh.obj`, both shareable and savable to Downloads
- The mesh pipeline is pure Kotlin (no Android dependencies in the geometry/coloring core) and covered by 16 JVM unit tests (`MeshPipelineTest`), including a regression test that reproduces the v1.3 hole-riddled failure mode with the old parameters

### Tips for good Photo Mesh results
- Stay **0.5–1.5 m** from the subject and orbit slowly; the mesh fuses best with overlapping views
- Matte, well-lit, non-reflective subjects reconstruct far better than shiny/dark ones (raw depth struggles with both)
- 4 mm resolution is tuned for object-scale scans; a whole room will hit the 600k triangle cap and lose detail — use Room mode splats for room-scale captures

Note: this is ARCore-assisted photogrammetry — camera poses come from AR tracking and the surface geometry comes from the raw depth sensor, then the captured photos are projected onto the fused mesh. For fully photo-only reconstruction, use the v1.4 **Photo SfM** mode above.

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
0. Pick a mode on the scan screen before you start (table above). Photo Mesh and Photo SfM produce a textured mesh; Object and Room produce a splat cloud; 360 Photo produces a panorama JPEG.
1. Open the app → tap to start a new scan.
2. Move slowly around the object, keeping 0.3–1.5 m away. Watch the status: "Ready (Tracking Locked)" means good tracking; "Searching for features" means slow down.
3. Press stop when done — processing and training run on the phone, then the 3D model opens in the viewer.
4. Models are stored in `Android/data/com.example.splatter/files/scans/` on your phone. What each scan folder contains:
   - `model.ply` + `model.splat` — point-cloud / Gaussian-splat scans (openable in most 3D and splat viewers)
   - `model_mesh.ply` + `model_mesh.obj` + `model_mesh.glb` — Photo Mesh and Photo SfM scans (the GLB carries the baked vertex colours)
   - `panorama.jpg` — the stitched 360° photo from 360 Photo mode, with its GPano XMP and EXIF GPS blocks written in
   - `heading.txt` / `location.txt` — the compass reading and position recorded at capture, which the panorama is oriented and placed by
   - `note.txt` — the pipeline's own one-line summary of how that scan was reconstructed
   - the raw capture: `rgb_*.jpg`, `pose_*.txt`, `intrinsics_*.txt`, and `depth_*.raw` where the depth sensor was used

Re-processing: tapping ⚡ Process on a scan that has frames but no model re-runs reconstruction from those raw frames, using the mode stored with that scan (`mode.txt`).

## How to view a finished model (Polycam-style)
When processing finishes (or from the gallery), the model opens in the 3D viewer:
- **One finger** — orbit around the object
- **Two fingers** — pan the model across the screen
- **Pinch** — zoom in/out
- **Flick** — spins the model with momentum
- **Spin** — turntable auto-rotate (touch pauses it)
- **Wireframe** — inspect the mesh triangles
- **Background** — cycle Studio / Grey / Light for the viewport
- **Splat size** — scale the Gaussians up for a solid look or down for detail
- **Double-tap / Reset** — re-frame the whole model (not the AR origin)
- **ⓘ** — measurements panel: real-world bounding-box size, counts, mode, exported files, and how the scan was reconstructed
- **Share / Save** — PLY, OBJ and GLB (mesh scans) or PLY/.splat (point-cloud scans), plus a ZIP of the whole scan folder from the gallery

## Signing secrets (kept out of this repo)
Release signing uses two files at the project root that are **git-ignored and never pushed**:
- `splatter-magic8pro.keystore` — the signing key (any future Splatter build must be signed with this same key, or Android will force an uninstall first)
- `keystore.properties` — points the build at the keystore (storeFile, storePassword, keyAlias, keyPassword)

If either is missing, the release build still works — it just produces an unsigned APK. Keep backups of both files somewhere safe (offline storage, not in this repo).

## If something looks wrong
This was built without access to your physical device, so if a scan comes out warped or empty, note what happened and re-run it — the capture pipeline saves exact depth metadata, and scan datasets can be pulled from the folder above for debugging. Point clouds may show noise at distance (>3 m) — that's ARCore raw depth, not a bug.

Start with the scan's own explanation: the line under its gallery card (and in the viewer's ⓘ panel) says which pose source was used — "photo SfM" means feature matching solved the camera path, "rebuilt from AR tracking" means it fell back because SfM couldn't, and a point-cloud note means no triangles could be built at all.

Scans captured **before** the pose-convention fix have scrambled geometry baked into their stored world coordinates. Their raw frames are still fine, so re-process them from the gallery (⚡ Process) rather than re-shooting.

What is tested and what isn't: 114 JVM unit tests pass (`./gradlew :app:testDebugUnitTest`), including the pose round-trip against Google's documented ARCore projection, a fused-mesh placement test, and for 360 Photo mode the whole way from synthetic frames to a graded publishable JPEG (`PanoramaPublishPipelineTest`), plus the viewer's projection maths and the metadata bytes. What no JVM test can cover is the Android half: `PanoramaProcessor`'s decode/encode path (it needs `BitmapFactory`), the compass and location sensors, and the actual stitch on your capture. Re-processing a real 360 sweep on the phone is still the decisive test.
