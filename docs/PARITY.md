# DogSC parity map

Living comparison against upstream [DogSC](https://github.com/laogou717/dogsc).
Status values: **Done**, **Partial**, **Gap**. A feature is Done only when it has a user-visible path and a verification method; architecture alone does not count.

Last updated: 2026-11-03.

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
| Crash recovery journal | Not implemented | Gap | Project JSON is saved atomically after a normal stop |

## Primary editing

| Upstream capability | Android implementation | Status | Evidence / gap |
|---|---|---|---|
| Split primary | `TimelineEditor.splitPrimary` + playhead action | Done | Core test |
| Trim primary edges | Drag segment edges in timeline | Done | Core trim test |
| Ripple delete | `deletePrimaryRange` | Done | Core split/delete test |
| Restore cuts | Restore one gap or all cuts | Done | Core restore test |
| Reorder primary segments | Not exposed | Gap | Model supports reorder through `SourceSequence.Edited` |
| Speed 1×–100× | Model supports `playbackRate` | Partial | No inspector slider yet |
| Undo/redo | Bounded snapshot stack; all editor commands go through `EditorSession` | Done | UI buttons + labels |
| Per-segment audio overrides | Model + inheritance display | Partial | No per-segment sliders yet |

## Timeline

| Upstream capability | Android implementation | Status | Evidence / gap |
|---|---|---|---|
| Filmstrip | Repeats decoded preview frames across primary track | Partial | Uses one real frame; no thumbnail strip cache yet |
| Waveform | Decodes AAC/PCM to 700 peaks; toggle in timeline | Done | Real peaks aligned through `TimelineMap` |
| Zoom track | Mint clips, move/resize/select | Done | |
| Screen 3D track | Clips with timing | Done | |
| Camera motion track | Clips with timing | Done | |
| Mosaic/spotlight track | Clips with timing | Done | |
| Sticker track | Clips with timing and layer order | Done | |
| Markers | Ruler lines + list/jump/delete | Done | |
| Snapping | Boundary snapping with 7 px threshold | Done | Toggle state exists; UI toggle is implicit |
| Timeline zoom | +/- and fit buttons | Partial | Pinch gesture not implemented |
| Hover preview | Not implemented | Gap | |
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
| Screen 3D | Tilt X/Y, rotate Z, perspective, projection anchor | Partial | Export exact; preview `graphicsLayer` approximation |
| Camera PiP motion | Shape, position, size, fullscreen transition, opacity | Done | Core + UI; camera media untested |
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
| Dynamic backgrounds | Three presets | Partial | Export/preview are static; upstream animates |
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
| Preview/export consistency | Shared `FrameScene` | Partial | 2D matches; 3D preview is approximate; GPU renderer pending |
| Estimated size/ETA | Bitrate policy + live progress | Done | |
| Cancel export | Volatile cancel flag + UI | Done | |
| Export selection/trim | Not implemented | Gap | |
| Long-recording performance | CPU Canvas + native YUV | Partial | ~4× realtime at 1080p30 on emulator; GL renderer required |

## Product surface

| Upstream capability | Android implementation | Status | Evidence / gap |
|---|---|---|---|
| Project library | Grid, thumbnails, rename/duplicate/delete | Done | |
| First-launch introduction | Not implemented | Gap | |
| Onboarding permission tour | Not implemented | Gap | |
| Settings | Capabilities, permissions, storage, about, licenses | Partial | No theme/language/recording-default settings |
| Localization | Chinese | Partial | Upstream English strings not ported |
| Menu/shortcut reference | Bottom sheet | Partial | No key handling |
| Multi-window/tablet layout | Adaptive inspector rail at ≥880 dp | Partial | Not visually verified on tablet |
| iOS/desktop/web | Shared core/UI prepared; no targets yet | Gap | Android first |

## Verification log

- `./gradlew :core:jvmTest` — 20 tests, green.
- `./gradlew :androidApp:assembleRelease` — green.
- Emulator (Android 15, hardware GPU): cold start 1.6 s release, 1.8 s on second run.
- Recorded 90.78 s screen + 88.81 s system audio; project reopened with correct duration.
- Exported a 12.03 s 1920×1080/30 H.264+AAC MP4; frame extraction verified background, card, rounded corners and shadow.
- Audio-only export produced a 190 KB M4A in ~1.2 s on the fast path.

## Next gaps to close

1. GPU preview/export renderer (3D, blur, performance).
2. Physical-device camera verification and lens flip.
3. Continuous touch motion or a documented gesture-based substitute.
4. Per-segment audio controls and reliable PCM mixer performance.
5. Export selection/trim and long-recording resilience.
6. Thumbnail strip cache, pinch timeline zoom, multi-select, hover preview.
7. Onboarding, English localization, desktop/iOS targets.
