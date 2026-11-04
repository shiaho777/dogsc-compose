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
- playback-rate output clock and auxiliary media plan
- 3D quad corner mapping
- 3D homography: corners, interior points and flat identity (`ScreenProjectionTest`)
- export estimate follows selected resolution/FPS and real source size (`ExportEstimatorTest`)
- primary segment reordering
- export range/trim math (manual runtime)

The Android release build also compiles the NDK color-conversion library for all four ABIs.

## Manual Android verification

Environment used: Android 15 emulator, hardware GPU (`-gpu host`), 1080×2400.

1. **Cold start**: release APK, `am start -W`, 1.6–1.8 s.
2. **Import**: photo picker → 12 s test MP4 → editor opens with 1280×720 source.
3. **Recording**: start from setup, grant screen + microphone + camera permissions, choose Entire screen, wait, stop. Project opens with a 90.78 s screen track and 88.81 s system track.
4. **Editor**: scrub, play, split, trim, restore cuts, add zoom/screen 3D/mosaic, toggle waveform, add markers, save scene preset.
5. **Export**: 1080p/30 MP4. `ffprobe` confirms H.264 1920×1080, 30 FPS, AAC, 12.03 s. Extract a frame and compare background, card, radius and shadow with the editor.
6. **Audio-only**: fast path produced a 190 KB M4A for 12 s.
7. **Audio timing**: import a stepped-tone clip (`ffmpeg` sine, +100 Hz per source second), split at a non-whole second, set one segment to 2×, export. Decode to mono 48 kHz and run short-window FFT: every tone switch must land within one FFT hop of the expected output time, the retimed segment must keep its source pitch, and the AAC track duration must match the video within one AAC frame (21.3 ms).
8. **Segment volume**: set one primary segment's system volume to 23 %, export, and measure mean volume per time window with `ffmpeg -af volumedetect`; the overridden window must drop by 20·log10(0.23) ≈ −12.8 dB.
9. **Recorder memo**: type memo text in record setup, start recording, tap 备忘 in the floating bar. `dumpsys window windows` must show a second DogSC overlay window; stopping must remove it. Extract a frame from the recording at a time when the memo was visible: the memo rect must contain no dark card pixels, proving `FLAG_SECURE` excluded it.
10. **Crash recovery**: start a recording, wait, then `adb shell am force-stop dev.dogsc.app` (kills the foreground service with the process). The media directory must contain `screen.mp4.part`; relaunching the app must show the project with its recovered duration, and opening it must remux the fragments to `screen.mp4` and delete the part. Repeat after truncating the part by a few hundred bytes to simulate a half-written fragment; recovery must cut at the last complete MP4 box and still produce a playable file. A normal stop must leave a standard MP4 (moov present, no moof).
7. **Onboarding**: first-launch three-page flow renders and persists the seen flag.
8. **Timeline polish**: real filmstrip frames, waveform toggle, marker sheet, scene preset sheet, per-segment speed/audio controls and export range slider are present in the editor.
9. **3D preview vs export** (dedicated AVD `DogSCVerify`, `emulator-5556`): add screen 3D at 0 s, step the playhead to 1.00 s, screencap; export 1280×720/60; extract the 1.00 s frame with ffmpeg. Detect the saturated test-pattern corners in the preview canvas region and in the exported frame with PIL. Normalized corners differ by ≤0.006.
10. **Edit persistence**: add a clip → `am force-stop` → reopen: clip kept. Add a clip → Home → kill after 0.8 s (shorter than the 1.2 s autosave debounce): clip kept, proving the `ON_STOP` save path.
11. **Export sheet state**: switching 原始素材/1080p/4K updates the header size and estimate live; after a finished export the sheet shows 导出完成 + size + 分享 + 重新导出.

Screenshots are judged by UI dumps (`uiautomator dump`) and PIL pixel sampling, not by eye, so evidence is reproducible from the commands above.

## Visual evidence

| View | Screenshot |
|---|---|
| Library | `docs/images/library.png` |
| Record setup | `docs/images/record-setup.png` |
| Editor | `docs/images/editor.png` |
| Export sheet | `docs/images/export.png` |
| Exported frame | `docs/images/export-frame.png` |
| Onboarding | `docs/images/onboarding.png` |

## Known verification gaps

- Camera recording has not been exercised on a physical device; the emulator exposes only one lens, so the front-camera fallback was exercised instead of a real front-camera session.
- Touch capture depends on an accessibility service; click/scroll capture is implemented, continuous motion is not.
- 4K export and hour-long recordings have not been profiled.
- Multi-window/tablet layout has not been visually verified on a tablet.
- The PCM mixer path (multiple sources or non-default volume) needs a dedicated timing test.
- No automated UI/instrumentation tests yet; the current evidence is build + core tests + manual emulator runs + extracted export frames.
