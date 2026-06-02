# Handoff: mosaic tiling effect

## What this branch adds

A new **Mosaic** option alongside the existing **Blur** effect in Effects Settings.
Square tiles only for this first pass — triangles/hexagons are an explicit follow-up.

User-visible:

- A segmented Blur / Mosaic selector at the top of the Effects screen
  (per home/lock tab, like the other sliders).
- When **Mosaic** is selected, the "Blur" slider is replaced with a
  **Tile size** slider (0–500). Dim and Grey continue to work in both modes.
- New prefs:
  - `effect_mode` / `lock_effect_mode` (string: `"blur"` or `"mosaic"`,
    default `"blur"`).
  - `mosaic_amount` / `lock_mosaic_amount` (int 0–500, default 100).
- Link-effects (chain icon) syncs mode + tile-size across home/lock just
  like blur/dim/grey. Reset-defaults resets them too.

Existing behaviour is preserved: with default prefs the wallpaper still
blurs exactly as before.

## How it's wired

- `android-client-common/.../util/ImageMosaicer.kt` — new util.
  `mosaicBitmap(source, tileSizePx)` bilinear-downscales then
  nearest-neighbour upscales. Simple and fast; no RenderScript.
- `main/.../render/MuzeiBlurRenderer.kt` — added `currentEffectMode` and
  `mosaicAmount` plus `recomputeEffectMode()` / `recomputeMosaicAmount()`.
  `GLPictureSet.load()` now branches: blur keyframes (existing path) or
  mosaic keyframes (`generateMosaicKeyframes`). Keyframe animation, dim,
  and grey continue to work for both modes.
- Tile size at keyframe `f` is
  `(mosaicAmount/500) * MOSAIC_MAX_TILE_FRACTION * scaledHeight * f/blurKeyframes`,
  where `MOSAIC_MAX_TILE_FRACTION = 0.20f` (so at max the image is ~5
  tiles tall — chunky but still recognisable). Tweak if you want a
  different ceiling.
- `main/.../render/RenderController.kt` — listens for the new pref keys
  and switches them when the lock-screen flag flips.
- `main/.../settings/EffectsScreen.kt` — added segmented button +
  conditional slider. Still uses the same custom `Layout` for the 3-row
  title/slider grid.
- `main/.../settings/EffectsSettings.kt` — passes new pref keys through,
  syncs them in link-effects mode, resets them in reset-defaults.
- `main/.../settings/SharedPreferences.kt` — added
  `rememberPreferenceSourcedStringValue` overload.
- `main/.../res/values/strings.xml` — three new strings:
  `settings_mosaic_amount_title`, `settings_effect_mode_blur`,
  `settings_effect_mode_mosaic`.

## What I haven't done (need a machine with the Android SDK)

I couldn't build or test this — no Android SDK is installed in the
environment I was working in. Everything below is your job:

1. **Build it.**
   - Set `sdk.dir` in `local.properties` to a real SDK path
     (copy from `local.properties.example` if needed).
   - `./gradlew :main:assembleDebug` (or the IDE).
   - Fix anything that doesn't compile. Most likely failure points,
     ordered by suspicion:
     - `SegmentedButton` / `SingleChoiceSegmentedButtonRow` API
       — the M3 API around these is opt-in
       (`@OptIn(ExperimentalMaterial3Api::class)` is already on the
       composable). If the version of compose-material3 in use is older
       and doesn't have `SegmentedButton`, swap to a plain
       `Row { FilterChip(...) }` or two `OutlinedButton`s.
     - `Bitmap?.config` nullable interaction in `ImageMosaicer.kt`. Safe
       fallback to `Bitmap.Config.ARGB_8888` is already there.
     - The fully-qualified `android.graphics.Bitmap` references in
       `MuzeiBlurRenderer.generateBlurKeyframes` /
       `generateMosaicKeyframes`. If the file already imports `Bitmap`
       elsewhere, prefer the import.

2. **Run it on a device / emulator.**
   - Open Effects Settings. Confirm the segmented Blur/Mosaic selector
     appears and the tile-size slider replaces the blur slider when
     Mosaic is chosen.
   - Slide tile size up and down. The wallpaper should re-render with
     visible square tiles. At slider=0 there should be no mosaic effect.
   - Try Dim and Grey while in Mosaic mode — both should still apply on
     top of the mosaic.
   - Toggle the link-effects icon. Changing mode on Home should mirror
     to Lock and vice-versa.
   - Reset-defaults should put both screens back to Blur with the old
     defaults.

3. **Edge cases worth eyeballing.**
   - Very small / very large source images — the mosaic util protects
     against zero sizes, but it's worth seeing what happens with a 1:1
     square source.
   - Switching mode while a crossfade is in progress (the throttled
     reload should handle it; verify visually).
   - Low-RAM devices use `blurKeyframes=1`, so there's only one mosaic
     keyframe (full mosaic). Should still look right.

4. **Polish ideas (not done, optional).**
   - The segmented selector colours are a quick first pass; tweak to
     match the rest of the screen aesthetic if needed.
   - Default mosaic tile fraction (`MOSAIC_MAX_TILE_FRACTION = 0.20f` in
     `MuzeiBlurRenderer`) was picked by gut feel. Tune after looking at
     it on real wallpapers.
   - The mosaic source is decoded at full `currentHeight` (vs blur which
     decodes at `currentHeight / blurredSampleSize`). If memory is an
     issue, give mosaic its own sample-size constant.

## Branch / merge

- This work is on `wip/mosaic-effect`.
- Once it builds and runs, the user wants it merged into their
  **combined personal branch**. Ask which branch (e.g. `personal`,
  `combined`, etc.) — I didn't see one locally on the fresh clone.

## Tasks I marked complete in tracker (for context, not authoritative)

- ImageMosaicer util written.
- Prefs constants added.
- Renderer keyframe pipeline branched on mode.
- RenderController listens for new prefs.
- EffectsScreen UI (selector + conditional slider).
- EffectsSettings link/reset logic updated.
- Strings added.

The "verify build" step was the one I couldn't finish — that's all you.
