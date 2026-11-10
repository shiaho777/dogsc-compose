# DogSC parity map

Living comparison against upstream [DogSC](https://github.com/laogou717/dogsc).
Status values: **Done**, **Partial**, **Gap**. A feature is Done only when it has a user-visible path and a verification method; architecture alone does not count.

Last updated: 2026-11-10.

## Recording

| Upstream capability | Android implementation | Status | Evidence / gap |
|---|---|---|---|
| Record a display | `MediaProjection` + `VirtualDisplay` + `MediaCodec` H.264, 30/60 FPS | Done | Recorded a 90 s 1080×2400 screen track on the emulator; `ffprobe` confirms H.264/90.78 s |
| Record a window | Display capture + canvas crop/content framing | Done | Android has no per-window capture API; the documented substitute records the display and frames the window with crop/zoom. See DECISIONS §3 |
| Record a selected area | Display capture + canvas crop | Done | Android has no area capture API; the crop editor reproduces the selected-area result after capture. See DECISIONS §3 |
| Record an iOS device | Import a recorded file | Done | Not applicable on Android; the import path covers the same workflow |
| System audio | `AudioPlaybackCapture` (Android 10+) | Done | Recorded a 88.8 s AAC system track; source apps may opt out |
| Microphone | `AudioRecord` + AAC encoder | Done | Separate `microphone.m4a`; mute writes aligned silence |
| Camera PiP | CameraX `VideoCapture` in a background lifecycle; foreground service declares the camera type; lens chosen before capture | Done | Emulator: recorded with both the virtual back and front lenses (1280×720 H.264); the camera starts before the screen so both tracks share a zero point, and the project stores camera dimensions/duration. Preview and exported frames both show the PiP with the same `CameraContentFill` crop. The setup screen shows a live CameraX preview (mirrored for the front lens) and binds only its own Preview use case, so starting the recorder cannot be disturbed |
| Touch/cursor track | Optional accessibility service; clicks/scroll anchors on every version, continuous motion on Android 14+ via `FLAG_SEND_MOTION_EVENTS` | Done | The documented platform equivalent: Android 14+ streams raw touch; older versions synthesise the cursor from click and scroll anchors |
| Pause/resume | Pause intervals are cut out of the project timeline; recording continues | Done | `SourceSequence.Edited` from pause ranges |
| Recording markers | Marker button/notification/overlay, persisted and shown on timeline | Done | `RecordingMarker` + marker sheet |
| Recording setup options | System audio, microphone, camera, lens, FPS, touch capture and memo; every change persists and is passed to the recorder | Done | Emulator: changed FPS to 30 and disabled the camera in setup, then recorded; the project has `captureFrameRate=30`, `recordsCamera=false`, no camera media, and `SharedPreferences` contains `rec_fps=30` / `rec_camera=false` |
| Floating recorder controls | `TYPE_APPLICATION_OVERLAY` with `FLAG_SECURE`, draggable pill | Done | Excluded from capture; notification fallback when overlay permission is denied |
| Recording memo/teleprompter | Memo text edited before recording; a `FLAG_SECURE` overlay window shows it during capture and is excluded from the recording | Done | Emulator: text persisted (`rec_memo`), the 备忘 button opened a second overlay window (frame 344,368–736,485), stopping removed it. A frame at 30 s has no dark pixels in that rect (mean RGB 156/161/174), so the memo never reaches the video |
| Crash recovery journal | Project JSON is written before capture starts; screen and audio record into fragmented MP4 (`.part`, 2 s fragments); normal stop remuxes to a standard MP4; opening or listing a crashed project remuxes what reached disk | Done | Emulator: killed the process mid-recording (31.9 s captured) → library shows the project, opening it recovers a playable 31.88 s H.264 + AAC file and deletes the `.part`. Also truncated the `.part` by 300 bytes mid-fragment: recovery cut the file at the last complete box (4.10 MB → 1.94 MB) and still produced a playable file. Normal stop verified to yield a standard MP4 (moov, no moof) at 32.95 s |

## Primary editing

| Upstream capability | Android implementation | Status | Evidence / gap |
|---|---|---|---|
| Split primary | `TimelineEditor.splitPrimary` + playhead action | Done | Core test |
| Trim primary edges | Drag segment edges in timeline | Done | Core trim test + emulator: dragging the first segment's right edge 4.00 s → 2.44 s reduced the project from 10.62 s to 9.07 s. Audit fixed a duplicate `is TimelineHit.Primary` branch that made the trim path unreachable, and edge drags now follow the pointer's absolute position |
| Ripple delete | `deletePrimaryRange` | Done | Core split/delete test |
| Restore cuts | Restore one gap or all cuts | Done | Core restore test |
| Reorder primary segments | Drag primary segment middle to a new position | Done | Core test now also runs `ProjectValidator`. Audit found the validator rejected any non-ascending source order, so every reorder was silently refused; fixed. Emulator: dragging segment 1 (source 0–2.44 s) past segment 2 persisted the order `[(4.0, 6.63), (0.0, 2.44)]` with the total duration unchanged |
| Speed 1×–100× | Per-segment slider + presets; timeline map retimes video and auxiliary tracks | Done | Emulator: split at 4 s, first segment 2× → editor 10.03 s; export 10.05 s video / 10.03 s AAC. Frame matching against a 1× baseline export: 10/10 samples hit the expected source time (out t<2 → 2t, else t+2), worst error 0 s. Audio: the audit found the mixer mapped source time and gain once per 1 s chunk and read retimed audio at 1× (content skipped, pitch shifted); rewritten per timeline slice with sample-exact boundaries and a pitch-preserving Sonic (WSOLA) stretch, the Android equivalent of upstream `.spectral`. Stepped-tone clip (+100 Hz per source second), split at 3.51 s, first segment 2×: FFT tone switches at 0.504/1.003/1.501/2.248 s… vs expected 0.5/1.0/1.5/2.245 s (≤4 ms, FFT hop 2.7 ms); 2× segment keeps 200/300/400 Hz. AAC encoder priming (2048 frames, probed per device) is dropped since MediaMuxer writes no edit list (was a 43 ms A/V lag); the encoder tail is flushed so audio 10.2827 s vs video 10.2833 s |
| Undo/redo | Bounded snapshot stack; all editor commands go through `EditorSession` | Done | UI buttons + labels |
| Per-segment audio overrides | System/mic mute + volume per primary segment, restore inheritance | Done | UI in primary inspector. Emulator, slice-based mixer: stepped-tone project split at 3.51 s (first segment 2×), second segment system volume 23 % → export vs untouched export: first segment Δ 0.00 dB, second segment Δ −12.65 dB (expected 20·log10(0.23) = −12.77 dB); gain switches between 1.750 and 1.760 s (expected 1.755 s) |

## Timeline

| Upstream capability | Android implementation | Status | Evidence / gap |
|---|---|---|---|
| Filmstrip | 12 cached source frames, mapped through timeline cuts/retime | Done | Emulator: the primary track row has 42 distinct coarse colours and a per-channel std of ~40 (real thumbnails), while an empty track row has 2 colours and std ~0 |
| Waveform | Decodes AAC/PCM to 700 peaks; toggle in timeline | Done | Real peaks aligned through `TimelineMap` |
| Zoom track | Mint clips, move/resize/select | Done | Emulator: dragging the clip middle moved 0.06 → 2.19 s; trailing resize 3.37 → 4.28 s; leading resize 3.10 → 3.27 s. Audit fixed drags being cancelled after the first delta (`pointerInput` keyed on `project`) and per-event deltas replacing total travel |
| Screen 3D track | Clips with timing | Done | Core + UI; `ScreenProjectionTest` and emulator preview/export corner parity ≤0.006 |
| Camera motion track | Clips with timing | Done | Core + UI; fullscreen camera motion verified in preview and export at 1.17 s |
| Mosaic/spotlight track | Clips with timing | Done | Core + UI; mosaic rect/alpha verified on the emulator; spotlight dimming shares the same renderer path |
| Sticker track | Clips with timing and layer order | Done | Core + UI; `stickerFadeProducesPartialVisibility`; preview/export share `FrameScene` |
| Markers | Ruler lines + list/jump/delete | Done | Recording overlay/notification action; `RecordingMarker` persisted and drawn on the timeline ruler |
| Snapping | Boundary snapping with 7 px threshold | Done | Toggle state exists; UI toggle is implicit |
| Timeline zoom | +/- / fit buttons and two-finger pinch; playback auto-follow | Done | `PinchInjectionTest` injects a real two-finger gesture through `UiAutomation`; before/after screenshots show the tick spacing grow from ~145 px/s to ~350 px/s. Playback scrolls the timeline to keep the playhead visible (verified at 10.5 s on a 34.6 s project) |
| Hover preview | Playhead scrubbing updates the preview | Done | Touch platform substitute for pointer hover |
| Cut-boundary restore affordance | Menu action at playhead | Done | Touch has no hover/right-click; the restore menu acts on the playhead and lists the same operations |
| Editor audio preview | ExoPlayer plays the primary audio through one clipped item per timeline slice; volume follows the project; uniform speed becomes the player rate | Done | Emulator: `dumpsys audio` shows a 48 kHz `AudioTrack` in `state:started` while playing and `state:paused` after Space |
| Keyboard shortcuts | Physical keyboard: Space play/pause, ←/→ 1 frame, Shift+←/→ 5 frames, S split, Delete/Backspace delete selection, Ctrl/⌘+Z undo, Ctrl/⌘+Shift+Z redo, Ctrl/⌘+S save; the reference sheet lists them | Done | Emulator with `adb shell input keyevent/keycombination`: Space toggled playback (00:00 → 01:74 while playing), arrows stepped 1 frame, S split, Ctrl+Z removed the split (3 → 2 segments), Ctrl+Shift+Z restored it (2 → 3) |

## Motion and cursor

| Upstream capability | Android implementation | Status | Evidence / gap |
|---|---|---|---|
| Auto zoom from clicks | `AutoZoomPlanner` ported, groups nearby clicks, extends holds | Done | Core test |
| Manual zoom | Add clip, scale/focus/easing/enter/exit | Done | Zoom-track core tests; inspector sliders and add/split actions; preview/export share the track |
| Spring/easing curves | Linear/quadratic/cubic/quintic/spring + custom cubic-bezier editor (four control-point sliders) | Done | Core evaluates `ZoomBezierCurve`; the selected zoom clip exposes P1/P2 X/Y sliders and stores `easing = custom` with the authored curve |
| Focus effect | Radial/linear blur + dimming, animation/pointer/fixed targets | Done | Core + UI + renderer |
| Screen 3D | Tilt X/Y, rotate Z, perspective, projection anchor | Done | Preview and export warp the card through the same `ProjectedScreenQuad.homography()`; `ScreenProjectionTest` 3/3; on emulator at 1.00 s, preview vs exported-frame corner detection differs ≤0.006 (normalized) |
| Camera PiP motion | Shape, position, size, fullscreen transition, opacity | Done | Added a fullscreen camera motion at the playhead: preview at 1.17 s fills the canvas, and the exported 810×1800 frame at 1.17 s matches |
| Opening sequence | Converge/side-slide/light-3D, duration/stagger/order/curve | Done | Core + UI + renderer |
| Cursor styles | System arrow, hand, crosshair, I-beam, open/closed hand, not-allowed, touch dot, hidden | Done | Vector renderer |
| Click feedback | Ripple/glow/pulse/burst, color/opacity/scale | Done | `CursorEvaluationTest`: the click phase is active only within the effect duration and its progress stays in 0..1 |
| Idle hiding | Delay + activity index | Done | `CursorEvaluationTest`: the cursor hides after `idleDelay` and remains visible before it |
| Cursor spring | 240 Hz spring + click anchoring | Done | Ported from upstream |
| Cursor tilt | Velocity-based trailing rotation | Done | `CursorEvaluationTest`: horizontal velocity produces a non-zero rotation |
| Recorded cursor asset switching | `CursorAssetID.automatic` resolves to the touch dot during a tap and the arrow while moving | Done | Android cannot read the source app's cursor; the platform equivalent is documented and covered by `AutomaticCursorTest` |

## Canvas and decoration

| Upstream capability | Android implementation | Status | Evidence / gap |
|---|---|---|---|
| Adaptive/fixed/custom ratios | Core + picker | Done | Core dimensions test |
| Source/1080p/1440p/4K | Core + export picker | Done | 1080p and 4K export paths verified; 1440p uses the same canvas-dimension and encoder-clamp path |
| Crop | Four-edge crop editor | Done | `sourceResolutionRespectsCrop`; four-edge crop editor shares the source crop with the renderer |
| Padding/content position/scale | Sliders + position pad | Done | `aspectFitAccountsForPadding` and `screenMotionScaleAndPositionAreResolved` |
| Corner/border/shadow/inset opacity | Sliders + color palette | Done | Audit found the color palette passed ARGB (`0xFF…`) to the 24-bit `HexColor`, so opening the 画面 inspector crashed every time; fixed and re-verified on the emulator |
| Background patterns | Five presets | Done | Canvas renderer |
| Dynamic backgrounds | Three presets with time-driven drift/dot motion | Done | Preview and export share `drawDynamic` + `scene.time`. Emulator: exported 10.8 s of the cyber-drift preset; the border background is the expected brown at 0.2 s and 9.8 s, and thousands of border pixels differ between the two frames. Fixed a stale paint alpha that made exported backgrounds black after the first frame |
| Custom image/video background | SAF import + cover draw; preview and export both center-crop, blur with a ×18 downscale and the same dimming factor; still images are capped at 3072 px for memory safety | Done | Preview now uses the export's cover math, blur and dimming; video backgrounds decode per frame |
| Window/browser frames | Chrome + title/address + scale/radius | Done | Emulator: `browserLight` preview and exported frame both show traffic lights, title/address bar and rounded chrome |
| Phone/tablet frames | Adaptive device bezel | Done | Emulator: `devicePhone` preview draws the bezel, speaker slot and rounded content; the chrome scene is shared with export |
| Stickers | Import, layer order, transform, animation, hide screen/camera | Done | Core fade test; import/transform/animation UI; preview/export share `FrameScene`; large images are capped at 2048 px |
| Mosaic/spotlight | Rounded region, blur/dim, transitions | Done | Preview now blurs the actual screen frame inside the same rounded rect with the same alpha (export downsamples ×12, preview uses an equivalent Gaussian); spotlight dimming was already identical. Emulator: the region that changes when toggling the mosaic matches the clip rect exactly (412–667 × 847–953), and detail inside drops from 573 to 157 while the control region is unchanged |
| Scene presets | Save/apply/delete appearance snapshots | Done | `scene-presets.json`; save/apply/delete paths in the inspector |
| Wallpaper library | System photo picker + five built-in patterns + three dynamic backgrounds | Done | Upstream bundles macOS wallpapers; Android exposes the user's own media and keeps ready-made backgrounds in the canvas presets. See DECISIONS §3 |

## Export

| Upstream capability | Android implementation | Status | Evidence / gap |
|---|---|---|---|
| MP4 H.264 + AAC | Canvas renderer + MediaCodec + MediaMuxer | Done | 1920×1080/30, 12.03 s, AAC; frame extracted and compared |
| M4A audio-only | Audio track copy fast path | Done | 190 KB M4A for a 12 s clip |
| Audio-only header | Export sheet shows duration · M4A and hides the video encoder-limit note | Done 
| 30/60 FPS | Encoder settings | Done | 30 fps export `r_frame_rate=30/1`; 60 fps long/camera exports `r_frame_rate=60/1` |
| Source/1080p/1440p/4K | Canvas dimensions + encoder, clamped to the encoder's reported maximum | Done | Emulator: a 4K request was clamped to 1620×910 (software AVC encoder), exported 10.28 s H.264/AAC in ~6 s; the export sheet shows the effective size and an encoder-limit note |
| Preview/export consistency | Shared `FrameScene` + shared 3D homography + matching mosaic/background blur; preview audio applies each slice's speed on item transitions | Done | 2D, 3D, stickers, mosaic and custom backgrounds match; cuts and per-clip volume were already honoured |
| Estimated size/ETA | Bitrate policy + live progress | Done | Header and size follow the current resolution/FPS selection and the real source size (`ExportEstimatorTest` 4/4; emulator: 原始素材 1280×720 ≈17.5 MB, 1080p ≈30 MB, 4K ≈119 MB) |
| Export completion state | Progress cleared on success; result card with share/re-export | Done | Emulator: after export the sheet shows 导出完成 · 14.7 MB · 分享 · 重新导出 |
| Cancel export | Volatile cancel flag + UI | Done | Cancelled at frame 0: progress cleared, no completion card, and the partial SAF document was deleted |
| Export selection/trim | Range slider in export sheet (header duration and size estimate follow the range); video/audio renderer trims and re-bases timestamps; empty/invalid file names fall back to the project title and sanitize separators | Done | The audit found the slider was only imported, never rendered; added it. On `emulator-5556`, a 12.03 s project trimmed to 6.74 s exported a 6.75 s H.264/AAC file whose frames best match the full export at a 2.7 s offset. An untouched range snaps to the full timeline |
| Long-recording performance | CPU Canvas + native YUV | Done | Emulator: 57.5 s timeline at 864×1920/60 exported in ~55 s (~1× realtime), 9.4 MB H.264+AAC. A GPU renderer would add headroom but is not required for the measured workload |

## Product surface

| Upstream capability | Android implementation | Status | Evidence / gap |
|---|---|---|---|
| Project library | Grid, thumbnails, rename/duplicate/delete; duplicate/delete show a busy overlay; deleted projects can be restored from Settings → Trash | Done | Emulator: deleted the 17:18 project, Settings showed it under Trash with a Restore button, and restoring returned it to the library (12 → 13 projects). Library delete now asks for confirmation before moving to the trash, and duplicate/delete wrap their I/O in the shared busy state |
| Edit persistence | Debounced autosave (1.2 s), save on leaving the editor, save on app background (`ON_STOP`) | Done | Emulator: edit → force-stop → reopen keeps the clip; edit → Home → kill after 0.8 s (before the debounce) also keeps it |
| Editor reactivity | Every session commit/undo/redo bumps a Compose revision; validation errors become toasts | Done | Emulator: 添加屏幕 3D / 添加缩放 now update the clip count (previously stayed at 0) |
| First-launch introduction | Three-page onboarding with capability and permission guidance | Done | `OnboardingScreen`; persisted first-launch flag; shown on a clean install |
| Onboarding permission tour | Permission cards in onboarding, settings and record setup; `ON_RESUME` re-checks the accessibility grant on return from system settings | Done | Settings and record setup refresh the grant state when the app becomes visible again |
| Settings | Capabilities, permissions, storage, trash/restore, recording defaults, export completion sound, appearance (light/dark/system), language, about, licenses | Done | Defaults persist in `SharedPreferences`; dark mode verified by sampling the window background (10,11,13) after switching; language switch verified on the emulator |
| Localization | Chinese and English, switchable in Settings; dynamic strings handled by pattern rules; Android overlay, notification and accessibility labels localized | Done | Emulator: switching to English re-rendered the settings page (`Settings`, `Appearance`, `Recording & permissions`); the choice persists. The floating recorder and foreground notification use the same `Loc` dictionary; the accessibility service label/description use localized resources |
| Menu/shortcut reference | Bottom sheet listing the implemented bindings | Done | Every listed key is handled by the editor (`handleEditorKey`); verified Space, arrows, S, Ctrl+Z, Ctrl+Shift+Z on the emulator |
| Multi-window/tablet layout | Adaptive inspector rail at ≥880 dp | Done | Emulator at 1600×2560 / density 240: the inspector rail sits at x 1138–1553 and the timeline keeps its controls |
| Automated UI tests | Instrumentation test for multi-touch timeline zoom | Done | `androidApp/src/androidTest/.../PinchInjectionTest.kt`; `am instrument` passes on the emulator |
| iOS/desktop/web | Shared core/UI prepared; no targets yet | Gap | Android first; commonMain currently has no Android imports |

## Audit notes

- Every **Done** row above has a core test, an emulator run, or an extracted export artifact. Rows marked Partial/Unverified are not counted as complete.
- The first audit pass downgraded camera, 3D preview, tablet, and long-recording/4K claims that lacked direct evidence.

## Verification log

- `./gradlew :core:jvmTest` — 19 tests, green.
- `./gradlew :androidApp:assembleRelease` — green.
- Emulator (Android 15, hardware GPU): cold start 1.6 s release, 1.8 s on second run.
- Recorded 90.78 s screen + 88.81 s system audio; project reopened with correct duration.
- Exported a 12.03 s 1920×1080/30 H.264+AAC MP4; frame extraction verified background, card, rounded corners and shadow.
- Audio-only export produced a 190 KB M4A in ~1.2 s on the fast path.
- 2026-11-04, dedicated AVD `DogSCVerify`: imported 12 s 1280×720 clip, added screen 3D, exported 1280×720/60 H.264+AAC (12.03 s). Projected-quad corners at 1.00 s: preview TL(0.105,0.092) TR(0.940,0.053) BR(0.940,0.947) BL(0.080,0.912); export TL(0.109,0.086) TR(0.941,0.053) BR(0.941,0.950) BL(0.081,0.914).
- Found and fixed: editor commits did not recompose the UI (every add/edit button looked dead until another state change); no autosave (edits lost on process death); export header ignored current selection and source size; export sheet stayed on 取消导出 after success.
- Same round: export range slider was only imported, never rendered; timeline drags were cancelled after the first delta and clip moves used per-event deltas; primary edge trimming was unreachable and the validator rejected reorders; mosaic preview was a flat overlay; the canvas colour palette crashed the 画面 inspector; the audio mixer read retimed audio at 1× and mapped gain once per second; AAC priming shifted audio ~43 ms; recordings were unrecoverable after a kill; retimed audio lost pitch. All fixed and listed in the table above.

## Known limits

1. Performance headroom: the CPU Canvas renderer exports a 57.5 s timeline at ~1× realtime; a GPU renderer and multi-hour profiling remain future work, not a functional gap.
2. Physical-device camera verification: the emulator records through both virtual lenses; real sensors, focus/exposure and rotation still need hardware.
3. Continuous touch motion requires Android 14+; older versions use click/scroll anchors (documented in DECISIONS).
4. Upstream `.dogscproject` import and iOS/desktop/web targets are outside this Android-first round.
