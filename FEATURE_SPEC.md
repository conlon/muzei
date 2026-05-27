# Muzei Custom Framing Features - Specification

## Overview

Three features to enhance how Muzei displays artwork on the home screen, giving users control over how art is framed and positioned.

**Fork target:** `github.com/conlon/muzei`
**Workflow:** Develop and verify each feature on-device, then commit and push to the fork. Each feature gets its own PR to upstream.

---

## Feature 1: Disable Parallax Panning

### Goal
Add a user preference to disable the parallax panning effect that occurs on the home screen wallpaper when swiping between launcher pages.

### Current Behavior
- `MuzeiWallpaperService.onOffsetsChanged()` receives `xOffset` (0.0-1.0) from the launcher and forwards it to `MuzeiBlurRenderer.setNormalOffsetX()`
- `MuzeiBlurRenderer.GLPictureSet.recomputeTransformMatrices()` uses `normalOffsetX` to pan the viewport across up to 1.8 screen-widths of the image

### Implementation Plan
1. Add `PREF_DISABLE_PARALLAX` constant to `Prefs.kt`
2. In `MuzeiWallpaperService.onOffsetsChanged()`, check the pref and skip calling `renderer.setNormalOffsetX()` when parallax is disabled (or pass 0.5 to center the image)
3. Add a toggle to the Gestures settings screen (`GesturesFragment.kt` / `GestureSettings.kt`)

### Key Files
- `main/.../settings/Prefs.kt` - preference constant
- `main/.../MuzeiWallpaperService.kt:293-304` - `onOffsetsChanged()`
- `main/.../render/MuzeiBlurRenderer.kt:244-248` - `setNormalOffsetX()`
- `main/.../settings/GesturesFragment.kt` or `GestureSettings.kt` - UI toggle

### Status: Not started

---

## Feature 2: Save Custom Artwork Framing

### Goal
In the art detail viewer (what the user calls "viewer mode"), add a "Save Framing" button alongside the existing "Next Artwork" button in the bottom chrome bar. When tapped, it persists the current pan/zoom viewport for that artwork. When the artwork next appears in the wallpaper rotation, the saved framing is applied instead of the default centered framing.

### Current Behavior
- The detail viewer is `ArtDetailFragment.kt` with pan/zoom handled by `PanScaleProxyView`
- The current viewport is stored in-memory in `ArtDetailViewport` (a singleton with two `RectF` slots for crossfade transitions), but is never persisted to disk
- The `Artwork` Room entity has no framing/viewport columns
- When a new artwork loads, `MuzeiBlurRenderer.setAndConsumeImageLoader()` calls `ArtDetailViewport.setDefaultViewport()` which centers the image based on aspect ratio

### Implementation Plan
1. **Database migration (v9 -> v10):** Add four nullable `REAL` columns to the `artwork` table: `saved_viewport_left`, `saved_viewport_top`, `saved_viewport_right`, `saved_viewport_bottom`
2. **Artwork entity:** Add corresponding fields to `Artwork.kt`
3. **ArtworkDao:** Add an `updateSavedViewport(id, left, top, right, bottom)` query
4. **UI - Save button:** In `art_detail_fragment.xml`, add an `ImageButton` (save/bookmark icon) next to `next_artwork`. Wire it in `ArtDetailFragment.kt` to read the current viewport from `ArtDetailViewport` and call the DAO to persist it
5. **Renderer - Apply saved framing:** In `MuzeiBlurRenderer.setAndConsumeImageLoader()`, after calling `setDefaultViewport()`, check if the artwork has a saved viewport and override it if so. This requires passing the saved viewport through from the artwork load pipeline
6. **Visual feedback:** Show a brief toast or snackbar confirming the framing was saved. Change the icon state to indicate the artwork has a saved framing (filled vs outline)

### Key Files
- `android-client-common/.../room/Artwork.kt` - entity columns
- `android-client-common/.../room/ArtworkDao.kt` - update query
- `android-client-common/.../room/MuzeiDatabase.kt` - migration
- `main/.../ArtDetailFragment.kt` - save button logic
- `main/.../ArtDetailViewport.kt` - read current viewport
- `main/src/main/res/layout/art_detail_fragment.xml` - button layout
- `main/.../render/MuzeiBlurRenderer.kt:267-316` - apply saved viewport on load
- `main/.../render/RealRenderController.kt` - artwork load pipeline

### Status: Not started

---

## Feature 3: Automatic Subject Detection & Smart Framing (Stretch Goal)

### Goal
When a new artwork is loaded and no saved framing exists, automatically detect the main subject of the image and propose an initial framing that keeps the subject fully in-frame. Stretch: position the subject at a rule-of-thirds intersection rather than dead center.

### Current Behavior
- Default framing is purely aspect-ratio-based centering in `ArtDetailViewport.setDefaultViewport()`

### Implementation Plan
1. **Subject detection:** Use Android's ML Kit Object Detection or CameraX ImageAnalysis APIs to find the primary subject bounding box. Alternatively, use the `android.graphics.Bitmap` saliency APIs if available on the target API level
2. **Framing calculation:** Given the subject bounding box (normalized 0-1 coords within the image) and the screen aspect ratio:
   - **Center mode:** Compute a viewport that fits the entire subject bounding box with some padding
   - **Rule-of-thirds mode (stretch):** Shift the viewport so the subject's center aligns with the nearest rule-of-thirds intersection point (1/3 or 2/3 in both axes), while keeping the subject fully visible
3. **Integration point:** In the same code path where `setDefaultViewport()` is called (in `MuzeiBlurRenderer.setAndConsumeImageLoader()`), run detection on the loaded bitmap and use the result to override the default viewport
4. **Fallback:** If no subject is detected with sufficient confidence, fall back to the existing centered default
5. **User control:** The saved framing from Feature 2 always takes priority over auto-detection

### Dependencies
- ML Kit Object Detection or similar (adds ~5MB to APK)
- Feature 2 (saved framing takes priority)

### Open Questions
- Is ML Kit acceptable as a dependency for an open-source project like Muzei?
- Should auto-framing run every time an artwork loads, or only once and then cache the result?
- Performance budget: detection should complete within the crossfade animation window (~750ms)

### Key Files
- `main/.../render/MuzeiBlurRenderer.kt` - integration point
- `main/.../ArtDetailViewport.kt` - viewport computation
- New file: `main/.../render/SubjectDetector.kt` - detection logic
- `main/build.gradle` - ML Kit dependency

### Status: Not started (stretch goal)

---

## Development Order

1. **Feature 1** (Disable Parallax) - smallest scope, isolated change
2. **Feature 2** (Save Framing) - core feature, database migration
3. **Feature 3** (Auto Subject Detection) - stretch, depends on Feature 2
