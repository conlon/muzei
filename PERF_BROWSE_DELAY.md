# Browse-tab next-photo delay — performance tracking

Tracks every measured >500ms cost on the "tap Next → photo visible" critical path.
Update the Status column as fixes land and are confirmed in logs.

## Critical path tree (tap Next → photo visible)

```
tap Next
├─ ArtworkLoadWorker.doWork              [ProviderSync single thread]
│  ├─ delay(250ms throttle)                                              ~250ms    ✓ by design
│  ├─ METHOD_GET_LOAD_INFO               (binder call)
│  ├─ query-new + query-all                                              ⚠ ~8s     FIX 1 (below)
│  │     ROOT: GalleryScanWorker rescan contends w/ the content provider
│  ├─ checkForValidArtwork × N           (opens candidate images)
│  └─ METHOD_REQUEST_LOAD
│       └─ GalleryArtProvider.onLoadRequested(initial=false)
│            └─ GalleryScanWorker.enqueueRescan → addAllImagesFromTree   ⚠ every tap  FIX 1
│                 (full breadth-first SAF tree crawl, re-adds all artwork on every tap)
│
├─ RC.pendingSavedViewportFor            [Main → IO, AWAITED, blocks the switch]
│  └─ AutoFramingEngine.computeFraming / computeFramingAndFaceRegions
│     ├─ decode(512)
│     ├─ face-await   (ML Kit FaceDetection)                             ⚠ ~100ms  FIX 2
│     └─ object-await (ML Kit ObjectDetection)                           ⚠ ~452ms  FIX 2
│
├─ RC.openDownloadedCurrentArtwork       [Main, AWAITED]
│  ├─ loader.prefetch()                  [Dispatchers.IO, concurrent]    ⚠ ~3134ms FIX 1 (contention)
│  │     readBytes() on the SAF URI — inflated while rescan crawl runs
│  └─ detectFaceRegions (GLITCH2 cache-miss) [IO, AWAITED]               ⚠ ~100ms  FIX 3
│        redundant: browse tab is always sharp, GLITCH2 never visible here
│
└─ GL.setAndConsumeImageLoader           [GL thread]
   ├─ getSize                                                             ✓ ~0ms    FIXED
   │     was ~3046ms; fixed by IO prefetch (byte-buffer ContentUriImageLoader)
   └─ load → decode64 + sharp-decode → crossfade → PHOTO VISIBLE
```

## Delay inventory

| ID | Stage | Measured | Root cause | Fix | Status |
|----|-------|----------|------------|-----|--------|
| D1 | GL getSize | ~3046ms | GL thread triggered slow content-provider read | Byte-buffer + IO prefetch | ✅ Fixed (`798ad04f`, `02a70f74`) |
| D2 | Worker query-new/all span | ~8000ms intermittent | GalleryScanWorker full SAF tree crawl contends w/ GalleryDatabase + content provider | Throttle `onLoadRequested(initial=false)` | ⏳ Fix 1 |
| D3 | RC.prefetch | ~3134ms intermittent | readBytes() on SAF URI while rescan crawl hammers same provider | Same root as D2 | ⏳ Fix 1 |
| D4 | object-await (auto-framing) | ~452ms every tap | ObjectDetection awaited synchronously before photo appears | Make auto-framing async+debounced; cancel on rapid Next | ⏳ Fix 2 |
| D5 | face-await (auto-framing) | ~100ms every tap | FaceDetection awaited synchronously before photo appears | Same as D4 | ⏳ Fix 2 |
| D6 | detectFaceRegions (GLITCH2) | ~100ms every tap | Redundant face pass on browse path; bake already deferred | Move face detection to async post-load job, off browse path | ⏳ Fix 3 |

## Fix 1 — Throttle per-tap rescan

**Files:** `source-gallery/.../GalleryArtProvider.kt`, `source-gallery/.../GalleryScanWorker.kt`

- Record `lastRescanTime` in SharedPreferences when a full scan completes (`GalleryScanWorker`).
- In `GalleryArtProvider.onLoadRequested(initial)`: always rescan when `initial=true`
  (folder change); skip `enqueueRescan` for `initial=false` when within the throttle window.
- Expected: D2 (~8s) and D3 (~3s) collapse to uncontended cost (~90ms). Intermittent spinner gone.

## Fix 2 — Async+debounced auto-framing

**Files:** `main/.../render/RealRenderController.kt`

- Remove the synchronous `pendingSavedViewportFor` ML Kit await from the critical path.
- Fast path: read only the saved-viewport DB row; call `reloadCurrentArtwork()` immediately.
- Async path: `var autoFrameJob: Job?` — cancel on each new emission, launch fresh job.
  Only the image you settle on runs ML Kit. On completion, if still current, apply via
  `ArtDetailViewport.setViewport(SwitchingPhotosStateFlow.value?.viewportId, viewport)`.
- Expected: D4 (~452ms) and D5 (~100ms) gone from critical path. Crop eases in a beat later.

## Fix 3 — GLITCH2 face detection off browse path

**Files:** `main/.../render/RealRenderController.kt`, `main/.../render/MuzeiBlurRenderer.kt`

- Remove awaited `detectFaceRegions` from `openDownloadedCurrentArtwork`. Browse is always
  sharp; `bakeEffectKeyframes` (which consumes face regions) is already deferred until blurred.
- When GLITCH2 is active, the async auto-framing job (Fix 2 combined path) already yields
  face regions. For auto-framing-OFF + GLITCH2: run a standalone async face-only pass.
- If effect is already baked when regions arrive, trigger `bakeEffectKeyframes`; otherwise
  stash for the deferred bake.
- Expected: D6 (~100ms) gone from critical path.
