# DogSC parity map

Living comparison against upstream [DogSC](https://github.com/laogou717/dogsc).
Status values: **Done**, **Partial**, **Gap**. A feature is Done only when it has a user-visible path and a verification method; architecture alone does not count.

Last updated: 2026-11-04.

## Recording

| Upstream capability | Android implementation | Status | Evidence / gap |
|---|---|---|---|
| Record a display | `MediaProjection` + `VirtualDisplay` + `MediaCodec` H.264, 30/60 FPS | Done | Recorded a 90 s 1080×2400 screen track on the emulator; `ffprobe` confirms H.264/90.78 s |
| Record a window | Android has no public per-window capture API | Partial | Post-crop/zoom can frame an app window; the recorder captures the display. Documented platform substitution |
| Record a selected area | No public area capture API | Partial | Canvas crop and content position; recording is full display |
| Record an iOS device | Not applicable on Android | Gap | Import a file instead |
| System audio | `AudioPlaybackCapture` (Android 10+) | Done | Recorded a 88.8 s AAC system track; source apps may opt out |
| Microphone | `AudioRecord` + AAC encoder | Done | Separate `microphone.m4a`; mute writes aligned silence |
| Camera PiP | CameraX `VideoCapture` in a background lifecycle | Partial | Implemented; not yet verified on physical hardware |
| Touch/cursor track | Optional accessibility service; click/scroll positions → JSONL → `ProjectPointerTrack` | Partial | Clicks and scroll centers work; continuous motion depends on platform events |
| Pause/resume | Pause intervals are cut out of the project timeline; recording continues | Done | `SourceSequence.Edited` from pause ranges |
| Recording markers | Marker button/notification/overlay, persisted and shown on timeline | Done | `RecordingMarker` + marker sheet |
| Floating recorder controls | `TYPE_APPLICATION_OVERLAY` with `FLAG_SECURE`, draggable pill | Done | Excluded from capture; notification fallback when overlay permission is denied |
| Recording memo/teleprompter | Not implemented | Gap | |
| Crash recovery journal | Preliminary project JSON is written at recording start; an interrupted recording appears in the library and can be opened | Partial | Shell implemented; interrupted-recording recovery not yet exercised |

## Primary editing

| Upstream capability | Android implementation | Status | Evidence / gap |
|---|---|---|---|
| Split primary | `TimelineEditor.splitPrimary` + playhead action | Done | Core test |
| Trim primary edges | Drag segment edges in timeline | Done | Core trim test |
| Ripple delete | `deletePrimaryRange` | Done | Core split/delete test |
| Restore cuts | Restore one gap or all cuts | Done | Core restore test |
| Reorder primary segments | Drag primary segment middle to a new position | Partial | Core reorder test; drag UI not yet visually verified |
| Speed 1×–100× | Per-segment slider + presets; timeline map retimes video and auxiliary tracks | Done | Emulator: split at 4 s, first segment 2× → editor 10.03 s; export 10.05 s video / 10.03 s AAC. Frame matching against a 1× baseline export: 10/10 samples hit the expected source time (out t<2 → 2t, else t+2), worst error 0 s. Audio content mapping not separately measured (sine test tone) |
| Undo/redo | Bounded snapshot stack; all editor commands go through `EditorSession` | Done | UI buttons + labels |
| Per-segment audio overrides | System/mic mute + volume per primary segment, restore inheritance | Partial | UI in primary inspector; mixer honors overrides; authored-volume export not re-verified end-to-end |

## Timeline

| Upstream capability | Android implementation | Status | Evidence / gap |
|---|---|---|---|
| Filmstrip | 12 cached source frames, mapped through timeline cuts/retime | Partial | `loadFilmstrip` + timeline drawing; not yet visually verified |
| Waveform | Decodes AAC/PCM to 700 peaks; toggle in timeline | Done | Real peaks aligned through `TimelineMap` |
| Zoom track | Mint clips, move/resize/select | Done | |
| Screen 3D track | Clips with timing | Done | |
| Camera motion track | Clips with timing | Done | |
| Mosaic/spotlight track | Clips with timing | Done | |
| Sticker track | Clips with timing and layer order | Done | |
| Markers | Ruler lines + list/jump/delete | Done | |
| Snapping | Boundary snapping with 7 px threshold | Done | Toggle state exists; UI toggle is implicit |
| Timeline zoom | +/- / fit buttons and two-finger pinch | Partial | Pinch implemented; not yet verified with multi-touch input |
| Hover preview | Playhead scrubbing updates the preview; no separate hover mode | Partial | Touch platform substitute; no pointer hover | |
| Multi-select | Not implemented | Gap | |
| Cut-boundary restore affordance | Menu action at playhead | Partial | No hover/right-click affordance |
| Keyboard shortcuts | Reference sheet only | Partial | No physical keyboard handling yet |

## Motion and cursor

| Upstream capability | Android implementation | Status | Evidence / gap |
|---|---|---|---|
| Auto zoom from clicks | `AutoZoomPlanner` ported, groups nearby clicks, extends holds | Done | Core test |
| Manual zoom | Add clip, scale/focus/easing/enter/exit | Done | |
| Spring/easing curves | Linear/quadratic/cubic/quintic/spring/custom model | Partial | UI exposes spring/cubic/quintic/linear; custom bezier editor absent |
| Focus effect | Radial/linear blur + dimming, animation/pointer/fixed targets | Done | Core + UI + renderer |
| Screen 3D | Tilt X/Y, rotate Z, perspective, projection anchor | Done | Preview and export warp the card through the same `ProjectedScreenQuad.homography()`; `ScreenProjectionTest` 3/3; on emulator at 1.00 s, preview vs exported-frame corner detection differs ≤0.006 (normalized) |
| Camera PiP motion | Shape, position, size, fullscreen transition, opacity | Partial | Core + UI; camera track unverified on physical device, emulator exposes one lens |
| Opening sequence | Converge/side-slide/light-3D, duration/stagger/order/curve | Done | Core + UI + renderer |
| Cursor styles | System arrow, hand, crosshair, I-beam, open/closed hand, not-allowed, touch dot, hidden | Done | Vector renderer |
| Click feedback | Ripple/glow/pulse/burst, color/opacity/scale | Done | |
| Idle hiding | Delay + activity index | Done | |
| Cursor spring | 240 Hz spring + click anchoring | Done | Ported from upstream |
| Cursor tilt | Velocity-based trailing rotation | Done | |
| Recorded cursor asset switching | `CursorAssetID.automatic` uses recorded asset | Partial | Accessibility service records only clicks, not asset identity |

## Canvas and decoration

| Upstream capability | Android implementation | Status | Evidence / gap |
|---|---|---|---|
| Adaptive/fixed/custom ratios | Core + picker | Done | Core dimensions test |
| Source/1080p/1440p/4K | Core + export picker | Done | |
| Crop | Four-edge crop editor | Done | |
| Padding/content position/scale | Sliders + position pad | Done | |
| Corner/border/shadow/inset opacity | Sliders + color palette | Done | |
| Background patterns | Five presets | Done | Canvas renderer |
| Dynamic backgrounds | Three presets with time-driven drift/dot motion | Partial | Preview and export both read `scene.time`; visual verification pending |
| Custom image/video background | SAF import + cover draw | Partial | Video background decoded per frame; blur is approximate |
| Window/browser frames | Chrome + title/address + scale/radius | Done | |
| Phone/tablet frames | Adaptive device bezel | Done | |
| Stickers | Import, layer order, transform, animation, hide screen/camera | Done | |
| Mosaic/spotlight | Rounded region, blur/dim, transitions | Partial | Preview blur is an overlay approximation; export uses downscale blur |
| Scene presets | Save/apply/delete appearance snapshots | Done | `scene-presets.json` |
| Wallpaper library | Not implemented | Gap | Use photo picker |

## Export

| Upstream capability | Android implementation | Status | Evidence / gap |
|---|---|---|---|
| MP4 H.264 + AAC | Canvas renderer + MediaCodec + MediaMuxer | Done | 1920×1080/30, 12.03 s, AAC; frame extracted and compared |
| M4A audio-only | Audio track copy fast path | Done | 190 KB M4A for a 12 s clip |
| 30/60 FPS | Encoder settings | Done | |
| Source/1080p/1440p/4K | Canvas dimensions + encoder | Partial | 4K not performance-verified |
| Preview/export consistency | Shared `FrameScene` + shared 3D homography | Partial | 2D and 3D card geometry match (corner test above); mosaic/background blur in preview is still an approximation |
| Estimated size/ETA | Bitrate policy + live progress | Done | Header and size follow the current resolution/FPS selection and the real source size (`ExportEstimatorTest` 4/4; emulator: 原始素材 1280×720 ≈17.5 MB, 1080p ≈30 MB, 4K ≈119 MB) |
| Export completion state | Progress cleared on success; result card with share/re-export | Done | Emulator: after export the sheet shows 导出完成 · 14.7 MB · 分享 · 重新导出 |
| Cancel export | Volatile cancel flag + UI | Done | |
| Export selection/trim | Range slider in export sheet (header duration and size estimate follow the range); video/audio renderer trims and re-bases timestamps | Done | The audit found the slider was only imported, never rendered; added it. On `emulator-5556`, a 12.03 s project trimmed to 6.74 s exported a 6.75 s H.264/AAC file whose frames best match the full export at a 2.7 s offset. An untouched range snaps to the full timeline |
| Long-recording performance | CPU Canvas + native YUV | Partial | ~4× realtime at 1080p30 on emulator; GL renderer required |

## Product surface

| Upstream capability | Android implementation | Status | Evidence / gap |
|---|---|---|---|
| Project library | Grid, thumbnails, rename/duplicate/delete | Done | |
| Edit persistence | Debounced autosave (1.2 s), save on leaving the editor, save on app background (`ON_STOP`) | Done | Emulator: edit → force-stop → reopen keeps the clip; edit → Home → kill after 0.8 s (before the debounce) also keeps it |
| Editor reactivity | Every session commit/undo/redo bumps a Compose revision; validation errors become toasts | Done | Emulator: 添加屏幕 3D / 添加缩放 now update the clip count (previously stayed at 0) |
| First-launch introduction | Three-page onboarding with capability and permission guidance | Done | `OnboardingScreen`; persisted first-launch flag | |
| Onboarding permission tour | Screen/accessibility permission cards in onboarding and settings | Partial | No automatic return-state recheck tour | |
| Settings | Capabilities, permissions, storage, about, licenses | Partial | No theme/language/recording-default settings |
| Localization | Chinese | Partial | Upstream English strings not ported |
| Menu/shortcut reference | Bottom sheet | Partial | No key handling |
| Multi-window/tablet layout | Adaptive inspector rail at ≥880 dp | Unverified | Not visually verified on tablet |
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

## Next gaps to close

1. GPU preview/export renderer; 3D preview/export exactness and long/4K performance.
2. Physical-device camera verification, lens flip, and camera motion on real hardware.
3. Continuous touch motion or a stronger gesture-based substitute; accessibility service enablement reliability.
4. Reliable PCM mixer performance for authored multi-source volume, plus long-recording/4K stress tests.
5. Pinch timeline zoom, multi-select, hover preview, physical keyboard handling.
6. Cut-boundary hover/right-click affordance.
7. Wallpaper library, English localization, tablet visual verification, iOS/desktop/web targets.
8. Import upstream `.dogsc` project packages and crash-recovery journal.
