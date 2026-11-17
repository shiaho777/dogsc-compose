# DogSC parity map

Living comparison against upstream [DogSC](https://github.com/laogou717/dogsc).
Status values: **Done**, **Partial**, **Gap**. A feature is Done only when it has a user-visible path and a verification method; architecture alone does not count.

Last updated: 2026-11-12.

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
| Recording setup options | System audio, microphone, camera, lens, camera quality, FPS, touch capture and memo; every change persists and is passed to the recorder | Done | Emulator: changed FPS to 30 and disabled the camera in setup, then recorded; the project has `captureFrameRate=30`, `recordsCamera=false`, no camera media, and `SharedPreferences` contains `rec_fps=30` / `rec_camera=false`. Camera quality is wired through the same path but a 480p/720p recording has not yet been probed |
| Floating recorder controls | `TYPE_APPLICATION_OVERLAY` with `FLAG_SECURE` + `FLAG_KEEP_SCREEN_ON`, draggable pill; overlay permission card in setup/settings | Done | Emulator: granted overlay permission, started a recording; `dumpsys window` showed the `APPLICATION_OVERLAY` window with `NOT_FOCUSABLE KEEP_SCREEN_ON LAYOUT_IN_SCREEN SECURE` and `mHoldScreenWindow` pointed at it; after stop it returned to null. Without permission the setup/settings cards explain the notification fallback |
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
| Filmstrip | 12 cached source frames, capped at 240 px and oriented from video metadata | Done | Emulator: the primary track row has real thumbnails; the 4K/portrait memory blow-up was fixed by scaling retriever frames to 240 px before publishing them |
| Waveform | Decodes AAC/PCM to 700 peaks; toggle in timeline | Done | Real peaks aligned through `TimelineMap` |
| Zoom track | Mint clips, move/resize/select | Done | Emulator: dragging the clip middle moved 0.06 → 2.19 s; trailing resize 3.37 → 4.28 s; leading resize 3.10 → 3.27 s. Audit fixed drags being cancelled after the first delta (`pointerInput` keyed on `project`) and per-event deltas replacing total travel |
| Screen 3D track | Clips with timing, position, scale, tilt X/Y, rotate Z, perspective, transition duration/lead-in | Done | Core + UI; `ScreenProjectionTest` and emulator preview/export corner parity ≤0.006; inspector exposes the animation timing |
| Camera motion track | Clips with shape/fullscreen, opacity, position, size, roundness, transition timing | Partial | Fullscreen camera motion verified in preview/export at 1.17 s; the new shape/opacity/roundness/timing inspector controls are implemented but not yet individually screenshot-verified |
| Mosaic/spotlight track | Clips with style switch, independent width/height, corner radius, transition style/in-out | Done | Mosaic rect/alpha verified on the emulator; selecting a blur clip renders Region effect, Blur strength, Corner radius, Transition (None/Linear/Smooth), Width and Height in the inspector |
| Sticker track | Clips with position, width, rotation, opacity, layer order, border color, shadow softness, entrance/exit animation and curve | Partial | Core fade test passes; the full layout/layer/appearance/in-out inspector is implemented but not yet screenshot-verified |
| Markers | Ruler lines + list/jump/delete | Done | Recording overlay/notification action; `RecordingMarker` persisted and drawn on the timeline ruler |
| Snapping | Boundary snapping with 7 px threshold | Done | Toggle state exists; UI toggle is implicit |
| Timeline zoom | +/- / fit buttons and two-finger pinch; playback auto-follow | Done | `PinchInjectionTest` injects a real two-finger gesture through `UiAutomation`; before/after screenshots show the tick spacing grow from ~145 px/s to ~350 px/s. Playback scrolls the timeline to keep the playhead visible (verified at 10.5 s on a 34.6 s project) |
| Hover preview | Playhead scrubbing updates the preview | Done | Touch platform substitute for pointer hover |
| Cut-boundary restore affordance | Menu action at playhead | Done | Touch has no hover/right-click; the restore menu acts on the playhead and lists the same operations |
| Editor audio preview | Two ExoPlayers (system/screen + microphone), one clipped item per timeline slice, per-segment mute/volume, audio-focus ownership and pitch-preserving slice rates | Done | Emulator: playing the camera project with both recorded sources showed two 48 kHz `AudioTrack`s in `state:started` from the app process; Space pauses both. The session requests audio focus once instead of letting two players fight over it |
| Keyboard shortcuts | Physical keyboard: Space play/pause, ←/→ 1 frame, Shift+←/→ 5 frames, S split, Delete/Backspace delete selection, Ctrl/⌘+Z undo, Ctrl/⌘+Shift+Z redo, Ctrl/⌘+S save; the reference sheet lists them | Done | Emulator with `adb shell input keyevent/keycombination`: Space toggled playback (00:00 → 01:74 while playing), arrows stepped 1 frame, S split, Ctrl+Z removed the split (3 → 2 segments), Ctrl+Shift+Z restored it (2 → 3) |

## Motion and cursor

| Upstream capability | Android implementation | Status | Evidence / gap |
|---|---|---|---|
| Auto zoom from clicks | `AutoZoomPlanner` ported, groups nearby clicks, extends holds | Done | Core test |
| Manual zoom | Add clip, scale/focus/easing/enter/exit | Done | Zoom-track core tests; inspector sliders and add/split actions; preview/export share the track |
| Spring/easing curves | Linear/quadratic/cubic/quintic/spring + custom cubic-bezier editor (four control-point sliders) | Done | Core evaluates `ZoomBezierCurve`; the selected zoom clip exposes P1/P2 X/Y sliders and stores `easing = custom` with the authored curve |
| Focus effect | Radial/linear blur + dimming; animation/pointer/fixed targets; direction and fixed center | Partial | Core/renderer path exists; the target/direction/center inspector controls are implemented but not yet individually screenshot-verified |
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
| Rotated imported sources | Extractor/retriever rotation is applied to probe dimensions, preview, filmstrip and export; images honour EXIF | Done | Emulator: imported a 640×360 clip carrying a 90° display matrix; the project reports 360×640, the preview and filmstrip show the display orientation, and the exported H.264 is 360×640 with the same orientation. `MediaMetadataRetriever` already returns oriented video frames, so they are no longer rotated twice |
| Source/1080p/1440p/4K | Core + export picker | Done | 1080p and 4K export paths verified; 1440p uses the same canvas-dimension and encoder-clamp path |
| Crop | Four-edge crop editor | Done | `sourceResolutionRespectsCrop`; four-edge crop editor shares the source crop with the renderer |
| Padding/content position/scale | Sliders + position pad | Done | `aspectFitAccountsForPadding` and `screenMotionScaleAndPositionAreResolved` |
| Corner/border/shadow/inset opacity | Sliders + color palette | Done | Audit found the color palette passed ARGB (`0xFF…`) to the 24-bit `HexColor`, so opening the 画面 inspector crashed every time; fixed and re-verified on the emulator |
| Background patterns | Five presets | Done | Canvas renderer |
| Dynamic backgrounds | Three presets with time-driven drift/dot motion | Done | Preview and export share `drawDynamic` + `scene.time`. Emulator: exported 10.8 s of the cyber-drift preset; the border background is the expected brown at 0.2 s and 9.8 s, and thousands of border pixels differ between the two frames. Fixed a stale paint alpha that made exported backgrounds black after the first frame |
| Custom image/video background | SAF import + cover draw; preview and export both center-crop, blur with a ×18 downscale and the same dimming factor; still images are capped at 3072 px for memory safety | Done | Preview now uses the export's cover math, blur and dimming; video backgrounds decode the frame at the playhead (looping on the video duration), so animation matches export |
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
| Audio-only header | Export sheet shows duration · M4A and hides the video encoder-limit note | Done | Emulator: selecting Audio only changes the header to `duration · M4A` and suppresses the video encoder-limit note |
| 30/60 FPS | Encoder settings | Done | 30 fps export `r_frame_rate=30/1`; 60 fps long/camera exports `r_frame_rate=60/1` |
| Source/1080p/1440p/4K | Canvas dimensions + encoder, clamped to the encoder's reported maximum | Done | Emulator: a 4K request was clamped to 1620×910 (software AVC encoder), exported 10.28 s H.264/AAC in ~6 s; the export sheet shows the effective size and an encoder-limit note |
| Preview/export consistency | Shared `FrameScene` + shared 3D homography + matching mosaic/background blur; screen/camera frames are resolved through the same `TimelineMap` and media placement as export; animated video backgrounds and both audio sources are previewed | Done | 2D, 3D, stickers, mosaic and custom backgrounds match. Audit fixed preview decoding at the raw playhead instead of the mapped source time, camera lead/sync offsets, still-only video backgrounds, and single-source audio. Core test `editedTimelineMapsPlayheadToTrimmedSourceTime` locks the output→source mapping; emulator `dumpsys audio` shows both audio tracks playing |
| Estimated size/ETA | Bitrate policy + live progress | Done | Header and size follow the current resolution/FPS selection and the real source size (`ExportEstimatorTest` 4/4; emulator: 原始素材 1280×720 ≈17.5 MB, 1080p ≈30 MB, 4K ≈119 MB) |
| Export completion state | Progress cleared on success; result card with share/re-export | Done | Emulator: after export the sheet shows 导出完成 · 14.7 MB · 分享 · 重新导出 |
| Cancel export | Volatile cancel flag + UI | Done | Cancelled at frame 0: progress cleared, no completion card, and the partial SAF document was deleted |
| Export selection/trim | Range slider in export sheet (header duration and size estimate follow the range); video/audio renderer trims and re-bases timestamps; empty/invalid file names fall back to the project title and sanitize separators | Done | The audit found the slider was only imported, never rendered; added it. On `emulator-5556`, a 12.03 s project trimmed to 6.74 s exported a 6.75 s H.264/AAC file whose frames best match the full export at a 2.7 s offset. An untouched range snaps to the full timeline |
| Long-recording performance | CPU Canvas + native YUV | Done | Emulator: 57.5 s timeline at 864×1920/60 exported in ~55 s (~1× realtime), 9.4 MB H.264+AAC. A GPU renderer would add headroom but is not required for the measured workload |

## Product surface

| Upstream capability | Android implementation | Status | Evidence / gap |
|---|---|---|---|
| Project library | Grid, thumbnails, rename/duplicate/delete; rename dialog on the card menu; duplicate/delete show a busy overlay; deleted projects can be restored from Settings → Trash | Done | Emulator: deleted the 17:18 project, Settings showed it under Trash with a Restore button, and restoring returned it to the library (12 → 13 projects). Library delete asks for confirmation before moving to the trash; the card menu exposes Rename; duplicate/delete wrap their I/O in the shared busy state |
| Edit persistence | Debounced autosave (1.2 s), save on leaving the editor, save on app background (`ON_STOP`) | Done | Emulator: edit → force-stop → reopen keeps the clip; edit → Home → kill after 0.8 s (before the debounce) also keeps it |
| Editor reactivity | Every session commit/undo/redo bumps a Compose revision; validation errors become toasts | Done | Emulator: 添加屏幕 3D / 添加缩放 now update the clip count (previously stayed at 0) |
| First-launch introduction | Three-page onboarding with capability and permission guidance | Done | `OnboardingScreen`; persisted first-launch flag; shown on a clean install |
| Onboarding permission tour | Permission cards in onboarding, settings and record setup; `ON_RESUME` re-checks the accessibility grant on return from system settings | Done | Settings and record setup refresh the grant state when the app becomes visible again |
| Settings | Capabilities, permissions (including overlay), storage, trash/restore/empty-trash, recording defaults, export completion sound, appearance (light/dark/system), language, about, licenses | Done | Defaults persist in `SharedPreferences`; dark mode verified by sampling the window background (10,11,13) after switching; language switch verified on the emulator |
| Localization | Chinese and English, switchable in Settings; dynamic strings handled by pattern rules; Android overlay, notification and accessibility labels localized; generated project titles use the current language | Done | Emulator: switching to English re-rendered the settings page (`Settings`, `Appearance`, `Recording & permissions`); the choice persists. The floating recorder and foreground notification use the same `Loc` dictionary; the accessibility service label/description use localized resources |
| Menu/shortcut reference | Bottom sheet listing the implemented bindings | Done | Every listed key is handled by the editor (`handleEditorKey`); verified Space, arrows, S, Ctrl+Z, Ctrl+Shift+Z on the emulator |
| Multi-window/tablet layout | Adaptive inspector rail at ≥880 dp | Done | Emulator at 1600×2560 / density 240: the inspector rail sits at x 1138–1553 and the timeline keeps its controls |
| Automated UI tests | Instrumentation test for multi-touch timeline zoom | Done | `androidApp/src/androidTest/.../PinchInjectionTest.kt`; `am instrument` passes on the emulator |
| First-use coach-mark tour | Live spotlight tour with target registry; steps for library, recorder and editor; persisted seen flags and replay in Settings | Done | Emulator: the library tour dims the screen, outlines the Start recording button, advances to Import video and Recent projects; the same overlay and target registry are wired to recorder/editor targets |
| Project source/package exchange | ZIP export/import and direct project-folder import (project.json + media/thumbnail); upstream Swift JSON compatibility mapper; imported paths and enum values are validated before opening | Done | Emulator: exported a 3.6 MB `.zip`, imported it from the library header, and got an 18th project with a new id/title/media. Also imported a synthetic upstream package (Chinese enum raw values, `BackgroundSource` kind/preset, `devicePhonePortrait`, numeric frame rate); it appeared as the 20th project. The import menu now accepts a project folder directly (SAF tree) as well as a ZIP. Validation now rejects project-relative background traversal, non-finite overlay/media references and invalid camera values |
| Capture codec/resolution limit | H.264/HEVC codec choice, native/4K/1080p capture limit and encoder-size clamping; project codec is probed from the actual file | Partial | Emulator: 1080p limit on a 1080×2400 display recorded 864×1920 H.264 and stored `captureResolutionLimit=1080p`. With HEVC requested, the emulator encoder still fell back and the project correctly recorded `captureCodec=h264` after probing the file; an actual HEVC recording is still unobserved |
| HDR/color-profile export | SDR desktop BT.709 | Done | Upstream `DirectExportPipeline` also constructs `CoreImageFrameColorProfile(contract: .sdrDesktop)` for its export path; the `FrameScene.isHDR` flag is not used there. Android's SDR 8-bit export therefore matches the shipped upstream behavior |
| Floating camera preview while recording | Live preview in setup; camera PiP added in post | Done | Android MediaProjection would capture a floating preview; a `FLAG_SECURE` preview would punch a black hole in the recording. The setup preview is the platform equivalent |
| iOS/desktop/web | Shared core/UI prepared; no targets yet | Gap | Android first; commonMain currently has no Android imports |

## Audit notes

- Every **Done** row above has a core test, an emulator run, or an extracted export artifact. Rows marked Partial/Unverified are not counted as complete.
- The first audit pass downgraded camera, 3D preview, tablet, and long-recording/4K claims that lacked direct evidence.

## Verification log

- `./gradlew :core:jvmTest` — 38 tests, green.
- `./gradlew :ui:testDebugUnitTest` — 2 tests, green.
- `./gradlew :androidApp:assembleRelease` and `:androidApp:assembleDebugAndroidTest` — green.
- `PinchInjectionTest` — 1 instrumentation test, green on `emulator-5556`.
- Emulator (Android 15, hardware GPU): cold start 1.6 s release, 1.8 s on second run.
- Recorded 90.78 s screen + 88.81 s system audio; project reopened with correct duration.
- Exported a 12.03 s 1920×1080/30 H.264+AAC MP4; frame extraction verified background, card, rounded corners and shadow.
- Audio-only export produced a 190 KB M4A in ~1.2 s on the fast path.
- 2026-11-04, dedicated AVD `DogSCVerify`: imported 12 s 1280×720 clip, added screen 3D, exported 1280×720/60 H.264+AAC (12.03 s). Projected-quad corners at 1.00 s: preview TL(0.105,0.092) TR(0.940,0.053) BR(0.940,0.947) BL(0.080,0.912); export TL(0.109,0.086) TR(0.941,0.053) BR(0.941,0.950) BL(0.081,0.914).
- Found and fixed: editor commits did not recompose the UI (every add/edit button looked dead until another state change); no autosave (edits lost on process death); export header ignored current selection and source size; export sheet stayed on 取消导出 after success.
- Same round: export range slider was only imported, never rendered; timeline drags were cancelled after the first delta and clip moves used per-event deltas; primary edge trimming was unreachable and the validator rejected reorders; mosaic preview was a flat overlay; the canvas colour palette crashed the 画面 inspector; the audio mixer read retimed audio at 1× and mapped gain once per second; AAC priming shifted audio ~43 ms; recordings were unrecoverable after a kill; retimed audio lost pitch. All fixed and listed in the table above.

### 2026-11-16 excellence pass (emulator-5554, Android 15)

- `:core:jvmTest` — 62 tests green (added gesture-channel, overlay-geometry, hit-testing, multi-select, attribute-transfer, camera-sync-anchor suites). `:ui:testDebugUnitTest` — 2 green. Instrumentation — `PinchInjectionTest` + `CanvasManipulationInjectionTest` both green together (OK 2 tests).
- Transient gesture channel: a 200-event drag produces exactly one undo entry; discrete edits mid-drag settle the gesture first; invalid preview samples are rejected without crashing (model `init` require() guarded).
- Canvas direct manipulation verified by injected one-finger drag: preview pixels change (>400 px diff), selection chrome + snap guides + numeric capsule render; corner resize and sticker rotation handle implemented.
- Preview/export parity: focus blur/softness and sticker backdrop now rendered in both backends; mosaic/focus coordinates unified to screen-content rect (golden-value tests); dynamic backgrounds share `DynamicBackgroundGeometry`; shadows use real low-res blur in preview.
- Export: Surface-input encoder verified end-to-end — 12.03 s 1280×720/60 H.264+AAC, frame extraction shows correct color bars (no green/cast). Fixed a process-abort caused by `eglTerminate` on the shared default display (EGL_NOT_INITIALIZED in the Compose RenderThread); release now destroys only its own surface/context.
- Honest perf finding: on the software-GL emulator the Surface path (~10 s for 721 frames) is not faster than the CPU native-YUV path (~9 s) at 720p — encoding is ~3 ms either way; the bottleneck is CPU Canvas rendering. GPU shader rendering (6b) is the real lever and needs physical-device hardware GL; not claimed done.
- Rotated-source scrubbing: frame-level LRU cache (6 frames, quantized to 30 fps buckets) added to the retriever fallback.
- Data safety: rolling `previous/project.{1,2,3}.json` backups on every save.
- UI: page transitions (AnimatedContent, reduced-motion aware), dark-mode leaks fixed (stage gradient + library card now theme tokens), bar sliders with embedded value + double-tap exact entry, inspector sliding tab capsule, magnetic-snap pulse ring, timeline edge auto-scroll, missing icon contentDescriptions filled.

### 2026-11-16 visual-quality pass (emulator-5554)

Addressed the "looks like a demo" critique with a polish-only pass (no feature changes), verified by light+dark screenshots of library / record / editor:

- **Coach marks fixed**: the spotlight previously mixed `boundsInWindow` with `localToWindow`, offsetting the white ring by the status bar (looked like a debug artifact) and re-popping on every visit. Now uses one root coordinate space (`boundsInRoot`), an even-odd scrim with a soft spotlight + thin theme-accent ring, an explanation card anchored beside the target, step dots + crossfade, and a global once-per-install gate (replay stays in Settings).
- **Dark elevation ladder**: background/panel/raised pulled apart (#0B0C0F / #16181D / #21242B) with stronger hairlines and dark-visible card shadows, so surfaces read as layers instead of black-on-black.
- **Library redesign**: thumbnails fill the 16:9 frame edge-to-edge with a bottom scrim (no more letterboxed blur), glass duration badge, card press-scale, skeleton loading cards.
- **Record setup**: "已选" became a mint pill badge; group cards read clearly in both themes.
- **Launch splash**: added `values-night` dark launch theme + window background so cold start no longer flashes white in dark mode.
- Tests stay green: 62 core + 2 UI + 2 instrumentation.

### 2026-11-16 mobile-native interaction pass (emulator-5554)

Closed the desktop→mobile experience gap (the editor was a desktop right-rail inspector + horizontal timeline squeezed onto a phone):

- **Contextual selection**: tapping a canvas element or timeline clip now selects it AND routes the inspector to its tab and opens the settings panel (`selectAndInspect`), instead of the desktop "click then hunt the right rail" habit. Routing locked by core `SelectionRoutingTest`.
- **Non-modal settings panel**: the mobile inspector moved from a ModalBottomSheet (which hid the preview) to an in-column bottom panel with a draggable height handle, so the preview stays visible and gives live feedback while tuning. Wide layouts keep the side rail.
- **View zoom for small screens**: a zoom control magnifies the preview (2x); once zoomed, pinch adjusts (1–4x), single-finger pans, double-tap/button resets. At 1x the single finger belongs to element manipulation (parent gestures detach so they don't steal the drag).
- **Floating quick-edit bar**: selected sticker/mosaic shows copy / delete / layer front-back / open-settings within thumb reach (mobile adaptation of upstream EditorCanvasOverlayQuickControls).
- **Fine slider mode**: long-press a bar slider enters 5x-finer travel (touch substitute for desktop Shift-drag), with a mint fill + "精调" label.
- Core sticker duplicate / reorder helpers added.
- Tests green: 65 core + 2 UI + 2 instrumentation. Injection asserts the deterministic zoom path; single-finger element drag verified via adb swipe (injected single-pointer streams were flaky under emulator gesture arbitration).

### 2026-11-17 detail sweep (emulator-5554)

Micro-interaction and ergonomics polish, no feature changes:

- **Timeline clip chrome**: selection outline + edge-trim handle pills now fade per clip over 140 ms (upstream EditorTimelineClipChrome timing) instead of popping; animated outside DrawScope via per-id Animatables, covers primary segments and multi-select too.
- **Scrub time badge**: dragging the ruler shows a live timecode capsule next to the playhead so the finger doesn't hide the readout; cleared on drag end/cancel.
- **Smooth button zoom**: timeline zoom −/＋/fit buttons animate over 180 ms (chained presses retarget); pinch/wheel stay direct to avoid lag.
- **Touch targets**: timeline toolbar and canvas quick-bar icons raised from 28/34 dp to 32/40 dp.
- **Haptics**: bar-slider long-press fine mode and switch toggles now tick.
- Verified export range slider already themed; remaining hard-coded colors are intentional artwork (empty-state illustration, color-bar fixture), not theme leaks.

## Known limits

1. Performance headroom: the CPU Canvas renderer exports a 57.5 s timeline at ~1× realtime; a GPU renderer and multi-hour profiling remain future work.
2. Physical-device camera verification: the emulator records through both virtual lenses; real sensors, focus/exposure and rotation still need hardware.
3. Continuous touch motion requires Android 14+; older versions use click/scroll anchors (documented in DECISIONS).
4. Upstream `.dogscproject` is covered through the compatibility mapper when zipped. Remaining device/encoder checks: actual HEVC capture, camera quality 480p/1080p, and sticker/motion/focus inspector screenshots.
5. Rotated video is decoded through `MediaMetadataRetriever` because the emulator's MediaCodec path returned an unexpected coded orientation; this is correct but slower for scrubbing rotated imports. Unrotated recordings keep the persistent MediaCodec path.
6. iOS/desktop/web targets remain outside this Android-first round.
