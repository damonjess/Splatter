# Splatter Android Studio fix package

## Changes included

### Shared camera lifecycle

The AR camera preview is hosted by a shared `GLSurfaceView` and ARCore `Session`. Navigating back to Gallery removes the Compose `AndroidView`, but the Activity remains alive. The previous implementation left the camera/GL resources running and reused them on the next scan without an explicit restart, which could leave a black viewfinder across all five modes.

`MainActivity.kt` now pauses the AR session and GL surface when leaving Scan or starting processing, resumes them when entering Scan, rebinds the camera texture, and only resumes the preview on Activity return when Scan is active.

### Scan-mode improvements

- **All modes:** YUV camera frames are converted to JPEG using each plane's row and pixel strides. This handles padded rows and interleaved chroma correctly, instead of assuming the camera image is tightly packed.
- **Photo SfM and 360 Photo:** live feedback no longer treats missing depth data as a scan failure or displays irrelevant depth/distance metrics. SfM guidance calls out the overlap requirement; panorama feedback shows the live 72-cell sweep count and reminds users to stay in one spot.
- **Object, Room, and Photo Mesh:** depth coverage and distance feedback remain active because these modes use ARCore depth.
- **Photo Mesh:** its description now says 5 mm, matching the configured fusion voxel size.
- Added regression tests for mode-aware depth warnings and tracking quality.

## Open and build in Android Studio

1. Extract `Splatter-AndroidStudio-Fix.zip`.
2. In Android Studio, choose **Open** and select the extracted `Splatter` folder.
3. Allow Gradle sync and install the Android SDK platform/build tools requested by the project if prompted.
4. Build with **Build > Make Project**, or run `./gradlew assembleDebug` from the project root.
5. The debug APK is normally created at `app/build/outputs/apk/debug/app-debug.apk`.

This sandbox does not have an Android SDK installed, so I could not produce or device-test an APK here. `git diff --check` is used as a whitespace validation; JVM tests and APK compilation must run in Android Studio or another environment with the Android SDK. On the target ARCore-supported phone, test repeat scans, then test all five modes. For the mode-specific guidance, check low-depth scenes in SfM and panorama, and verify captured JPEGs from the phone camera render without visible color/blocking artifacts.
