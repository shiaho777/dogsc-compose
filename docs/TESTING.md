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

- Camera recording is verified on the emulator's virtual back camera (front requests fall back to the only lens); a physical-device front-camera session is still the remaining hardware check.
- Touch capture depends on an accessibility service; clicks and scroll anchors are verified, continuous motion uses `FLAG_SEND_MOTION_EVENTS` on Android 14+.
- 4K requests fall back to the encoder's largest supported size; hour-long recordings have not been profiled (57.5 s was measured at ~1× realtime).
- Multi-window/tablet layout is verified at 1600×2560 / density 240 on the emulator.
- The PCM mixer path (multiple sources or non-default volume) needs a dedicated timing test.
- Automated instrumentation coverage is currently the timeline pinch (`PinchInjectionTest`); the rest of the evidence is build + core tests + manual emulator runs + extracted export frames.

## Performance checks

- `adb shell dumpsys gfxinfo dev.dogsc.app reset`, play for a few seconds, then `dumpsys gfxinfo dev.dogsc.app` — look at the 50th/90th percentiles and "Slow issue draw commands".
- Preview decoding is the hot path: a `MediaMetadataRetriever` per request cost 50–110 ms/frame on a 1080×2400 recording. The editor now uses one persistent MediaCodec decoder per file (`PreviewFrameDecoder`) with scaled ring buffers; a full-layer shadow blur was replaced by expanding strokes.
- `adb shell dumpsys audio` while playing shows the preview `AudioTrack` state; Space pauses it.
- **4K fallback**: request 4K on a device whose AVC encoder lacks 3840×2160; the export sheet must show the effective size and the encoder-limit note, and the file must match it (emulator: 3840×2160 → 1620×910).
- **Long recording**: export a ~1 minute timeline and compare wall time with duration; log timestamps `video start` / `video encoded` / `DogSCAudio: encode done`.
- **Frame rates**: export a short range at 30 and 60 FPS; `ffprobe -select_streams v:0 -show_entries stream=r_frame_rate` must report `30/1` and `60/1`.
- **Cancel**: start a full export, tap Cancel while the progress card is visible. Progress must clear, no result card must appear, and the partial SAF document must be deleted (`ls /sdcard/Download`).
- **Recorder localization**: switch the app to English, start a recording and inspect the floating bar and notification; labels must read Pause/Marker/Stop and the notification must say DogSC recording. The system accessibility settings must show “DogSC touch capture”.
- **Chrome frames**: set `browserLight` and `devicePhone`; the preview must draw the traffic lights/address bar or the bezel/speaker slot. Export a short `browserLight` range and confirm the exported frame contains the same chrome.
- **Setup options**: change FPS to 30, disable the camera and toggle the microphone; start a recording. `project.json` must show `captureFrameRate=30`, `recordsCamera=false` and the chosen mic state, and `shared_prefs/dogsc_prefs.xml` must persist the values.
- **Camera preview**: enable Camera overlay and grant the camera permission. The setup screen must show a live preview; switching Back/Front must rebind the preview and the front preview must be mirrored. Press Start: the preview must disappear and the recording must still start.
- **Trash/restore**: the library delete action must show a confirmation dialog first; confirm, open Settings → Trash, confirm the project appears, tap Restore, and confirm it returns to the library and leaves the trash. The delete dialog must say Settings, not a system file manager.
- **Camera PiP**: enable Camera overlay, grant the camera permission, record 10 s, stop. `media/camera.mp4` must exist (H.264, non-black); the editor must draw the PiP with the same crop as the export. Add a fullscreen camera motion at the playhead and export a short range; the exported frame must match the preview. The screen recorder starts from CameraX's `Start` event; check `project.json` (`media.camera.sourceStartTime`, `startOffset`) and compare camera/screen durations. Both virtual lenses were exercised (back: 1280×720; front: 1280×720).
- **Dynamic background export**: set a dynamic background, export a range that includes two visible background moments, extract both frames and compare the border regions. Emulator: 10.8 s cyber-drift export, border mean difference 2.9–4.2 with thousands of pixels above a 4/255 threshold; the background must never turn black after the first frame.
- **Cursor evaluation**: `CursorEvaluationTest` covers idle hiding, click-effect lifetime and velocity tilt; `AutomaticCursorTest` covers the automatic arrow/touch-dot switch.
- **Timeline auto-follow**: play a project longer than the visible timeline; the ruler must scroll so the red playhead stays visible. Pause and scroll manually; the view must stay where the user left it while paused.
- **Timeline pinch**: with a project open, run `adb shell am instrument -w -e class dev.dogsc.app.PinchInjectionTest dev.dogsc.app.test/androidx.test.runner.AndroidJUnitRunner`. The test opens the first project, injects a two-finger spread, and asserts the timeline region changed; screenshots land in the app's external files as `pinch_before.png` / `pinch_after.png`.
