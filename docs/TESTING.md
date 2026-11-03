# Testing

## Automated

```bash
./gradlew :core:jvmTest
./gradlew :androidApp:assembleRelease
```

Core tests (`core/src/commonTest`) cover:

- zoom enter/hold/exit and adjacent clip hand-off
- automatic focus override only for automatic clips
- composition aspect-fit with padding
- canvas dimensions for fixed ratios, source crop and 4K
- project JSON round-trip
- split/delete/restore/trim timeline operations
- auto-zoom grouping of nearby clicks
- opening opacity/settling
- projected quad identity mapping
- media timeline placement both directions
- sticker fade visibility
- screen motion scale/position

The Android release build also compiles the NDK color-conversion library for all four ABIs.

## Manual Android verification

Environment used: Android 15 emulator, hardware GPU (`-gpu host`), 1080×2400.

1. **Cold start**: release APK, `am start -W`, 1.6–1.8 s.
2. **Import**: photo picker → 12 s test MP4 → editor opens with 1280×720 source.
3. **Recording**: start from setup, grant screen + microphone + camera permissions, choose Entire screen, wait, stop. Project opens with a 90.78 s screen track and 88.81 s system track.
4. **Editor**: scrub, play, split, trim, restore cuts, add zoom/screen 3D/mosaic, toggle waveform, add markers, save scene preset.
5. **Export**: 1080p/30 MP4. `ffprobe` confirms H.264 1920×1080, 30 FPS, AAC, 12.03 s. Extract a frame and compare background, card, radius and shadow with the editor.
6. **Audio-only**: fast path produced a 190 KB M4A for 12 s.

## Visual evidence

| View | Screenshot |
|---|---|
| Library | `docs/images/library.png` |
| Record setup | `docs/images/record-setup.png` |
| Editor | `docs/images/editor.png` |
| Export sheet | `docs/images/export.png` |
| Exported frame | `docs/images/export-frame.png` |

## Known verification gaps

- Camera recording has not been exercised on a physical device; the emulator exposes only one lens, so the front-camera fallback was exercised instead of a real front-camera session.
- Touch capture depends on an accessibility service; click/scroll capture is implemented, continuous motion is not.
- 4K export and hour-long recordings have not been profiled.
- Multi-window/tablet layout has not been visually verified on a tablet.
- The PCM mixer path (multiple sources or non-default volume) needs a dedicated timing test.
- No automated UI/instrumentation tests yet; the current evidence is build + core tests + manual emulator runs + extracted export frames.
