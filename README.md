# Bulk Face Swap (Android, fully on-device)

Pick one "source" face photo and a batch of "target" photos; the app swaps
the source face into every target and saves the results to
`Pictures/BulkFaceSwap`. Everything — face detection, the warp, and the
blend — runs on the phone's CPU. No image or landmark ever leaves the
device; there's no server, no API key, no network permission at all.

## How it works

1. **Face detection** — Google **ML Kit Face Detection** (bundled model,
   `com.google.mlkit:face-detection`) extracts ~130 contour points per face
   (jaw, brows, eyes, nose, lips).
2. **Face swap** — plain **OpenCV** (`org.opencv:opencv`, pulled straight
   from Maven Central since 4.9.0 — no manual SDK download):
   - convex hull of the target face's points
   - Delaunay-triangulate that hull
   - warp each source triangle onto the matching target triangle
     (`Imgproc.warpAffine`)
   - `Photo.seamlessClone` (Poisson blending) to match skin tone/lighting
3. Bulk mode just loops step 1–2 over every picked target with one cached
   source face.

This is the same "triangulation + seamless clone" technique used by most
lightweight face-swap apps. It's fast (no GPU/NPU needed) and needs no
bundled neural-network weights, at the cost of being landmark/geometry-based
rather than a learned generative model — see Limitations below.

## Opening the project

1. Android Studio (Koala/2024.1 or newer).
2. Open this folder — Gradle will resolve ML Kit, OpenCV and Coil from
   Google's Maven repo and Maven Central automatically.
3. Run on a device or emulator with **API 24+**. A real device is strongly
   recommended: face detection + triangulation on a big batch is CPU-heavy.

No OpenCV Android SDK download, no NDK setup, no signing config needed to
just run it.

## Known limitations (and where to take it next)

- **One face per photo.** The picker rejects photos with zero or multiple
  detected faces (`processOne` returns `NoFace`) to keep the landmark
  correspondence unambiguous. Multi-face target support just needs a face
  picker per target image.
- **Frontal-ish faces work best.** Because the warp is per-triangle affine,
  not a full 3D model, large head-turn or extreme angle mismatches between
  source and target look noticeably warped.
- **Geometry swap, not identity swap.** This pipeline reshapes the target's
  face using the source's landmarks and skin texture — it does not run a
  learned model that reconstructs identity from any angle/lighting. For
  that quality bar you'd swap `FaceSwapEngine` for an on-device TFLite/ONNX
  generative model (e.g., a distilled SimSwap/InSwapper variant) — much
  better results, but adds tens to hundreds of MB to the APK and real model
  licensing/sourcing to sort out.
- **Sequential processing.** The bulk loop processes one target at a time
  on `Dispatchers.Default`; parallelizing with a bounded thread pool would
  speed up large batches on multi-core devices.
- No settings for JPEG quality, output folder, or batch cancel — straightforward
  additions to `BulkSwapViewModel`.

## A note on responsible use

Face-swapped images can be mistaken for real photos. It's worth building in
(or at minimum deciding on) a policy for consent — e.g. only swapping faces
the user has rights to use, and/or watermarking output — before shipping
this beyond a personal prototype.
