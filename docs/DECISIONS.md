# Decisions

## 1. Clean-room product core instead of editing the macOS app

DogSC is a SwiftUI/AppKit application with ScreenCaptureKit, Core Image and AVFoundation. None of that survives on Android. The port keeps the **product semantics** (project schema, timeline, zoom, screen 3D, opening, canvas, cursor, export) in a pure Kotlin `core` module, and writes platform implementations around it. This is the only way the same product can later run on iOS, desktop and Wasm without forking the model.

## 2. Immutable `FrameScene` as the preview/export contract

Upstream evaluates the composition into a scene, then feeds preview and Core Image export from the same geometry. We kept that boundary: `FrameSceneEvaluator` produces an immutable `FrameScene`; Compose draws it for the editor and the Android renderer draws it for export. Neither renderer reads `RecorderProject` directly. This prevents the classic port failure where the editor preview and the exported file slowly diverge.

## 3. Android platform substitutions

| Upstream | Android substitution | Why |
|---|---|---|
| ScreenCaptureKit display/window/area | `MediaProjection` display capture + post crop | Android exposes display capture, not arbitrary window/area capture |
| Global mouse events through Accessibility | Optional accessibility service recording click/scroll positions; synthetic cursor spring | Android has no global pointer stream for other apps |
| System audio through ScreenCaptureKit | `AudioPlaybackCapture` | Android 10+ playback capture; source apps may opt out |
| Floating AppKit recorder panel | `TYPE_APPLICATION_OVERLAY` pill with `FLAG_SECURE` + notification actions | Same control surface, excluded from capture |
| Core Image GPU compositor | Canvas CPU renderer + native YUV conversion | Correctness first; GPU renderer is the next performance step |
| macOS window/browser mockups | Canvas-drawn vector chrome | Same visual language, resolution-independent |

The substitutions are product decisions, not hidden feature deletion. Each is listed in `PARITY.md` with its current fidelity.

## 4. Pause is a timeline cut, not a capture stop

Pausing screen capture on Android would require tearing down the virtual display/encoder and creating a second file. Instead, the recorder keeps capturing and stores pause intervals. On stop, the project is created with `SourceSequence.Edited` segments that exclude those intervals. This keeps one continuous media file, makes pause/resume lossless, and uses the same ripple timeline that the editor already understands.

## 5. Audio fast path before the mixer

Most recordings have exactly one audio source and no per-segment overrides. Re-encoding that through a PCM mixer is unnecessary and slow. `AndroidAudioMixer` first tries to copy the source audio track directly into the M4A container. The PCM mixer is reserved for multiple sources or authored volume/segment overrides. This is why default recordings export audio in about a second instead of minutes.

## 6. Camera recording in the foreground service

CameraX is bound to a custom `LifecycleOwner` owned by the recording coordinator, not to the Activity. Moving DogSC to the background must not stop the camera. The foreground service keeps the process alive; CameraX writes a separate MP4 track. The final project treats camera as an independent media reference, exactly like upstream.

## 7. Touch capture is optional and explicit

The accessibility service only records normalized click/scroll positions. It does not read screen text, input content or window content (`canRetrieveWindowContent="false"`). Without the service, recording still works; the project simply has no pointer track. This is the honest Android equivalent of upstream's accessibility permission.

## 8. Export resolution is authored in canvas pixels

`CanvasStyle.pixelDimensions` is a direct port of upstream's rules: adaptive uses the source aspect, fixed ratios fit inside the preset envelope, source resolution respects crop and never upscales. Padding, radii, borders, shadows and blur are authored against a 1080-pixel short edge and scaled by `canonicalStyleScale`, so 1080p, 1440p and 4K remain compositionally identical.

## 9. Project storage is a directory package

`projects/<uuid>/project.json` plus `media/` mirrors upstream's package model. Media stays with the project; deleting moves the directory to `trash/`. This makes backup, duplication and future `.dogsc` import straightforward.

## 10. Keep the visual language

The workspace uses upstream's Soft Silver / Graphite tokens, Alibaba PuHuiTi typography, pill controls, soft cards, mint auto-zoom clips and red playhead. The layout is adapted for touch (inspector sheet on phones, rail on wide screens), not redesigned into generic Material.

## 11. Edits autosave; the app can be killed at any time

A macOS document app is rarely killed without warning, but Android reclaims background processes routinely. Every committed edit schedules a debounced save (1.2 s). Leaving the editor and the app going to the background (`ON_STOP`) flush immediately. Writes are serialized and atomic (temp file + rename), so a kill mid-write keeps the previous version. The manual "保存" menu item remains for explicit confirmation.

## 12. Recording journals as fragmented MP4

Android gives an app no chance to finalize a file when the process is killed, and a regular MP4 keeps its index (`moov`) only in `MediaMuxer.stop()`, so an interrupted recording would be unreadable. Upstream sets `movieFragmentInterval` for the same reason. Capture therefore writes fragmented MP4 (`.part`, 2 s fragments) and a normal stop remuxes the fragments into a regular MP4. Listing or opening a project whose `.part` survives remuxes whatever reached disk, after truncating a half-written box. The final files stay standard MP4 so the editor, thumbnailer and exporter keep their existing fast paths.

## 13. Edge sliders opt out of system back gestures

Full-width sliders put their thumbs inside Android's gesture-navigation edge zone, so dragging a thumb at either end was read as "back" and closed the sheet. `excludeSystemGestures()` (an expect/actual modifier; no-op on platforms without edge gestures) is applied to shared sliders and the export range slider, so drags behave like upstream's pointer drags.
