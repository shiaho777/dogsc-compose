# Upstream study notes

These notes record why the Android port is shaped the way it is. They are not a substitute for the parity list.

## Product model

DogSC is a macOS screen recorder plus a lightweight editor. The product is built around one **recorded screen clock** and a ripple timeline:

- Primary screen video is the clock owner. Camera, microphone and pointer tracks are auxiliary tracks aligned to it.
- `SourceSequence` is either the full recording or an ordered list of retained source segments. Output time is derived by accumulating segment durations; no second output-start field can contradict it.
- Every time-varying edit lives in one `ProjectTimeline`: zoom clips, screen motion, camera motion, mosaics, stickers. The primary segment audio overrides live there too.
- A project is a directory package (`project.json` + media). Export is a separate, deterministic rendering of the project, not a mutation of it.

## Scene evaluation

The upstream architecture has a clean boundary worth preserving:

1. `CompositionSceneEvaluator` resolves canvas fit, padding, crop, zoom viewport, screen card geometry and camera geometry.
2. `FrameSceneEvaluator` adds opening motion, decorations, cursor, mosaics, focus effect, stickers and layer order.
3. Renderers consume the immutable `FrameScene`.

That is why the Android port has a pure `core` scene evaluator and a renderer that never reads `RecorderProject` directly.

## Timeline semantics worth copying exactly

- Zoom clips are enter/hold/return envelopes. Touching clips hand off the previous held state; non-touching clips return to base over `exitDuration`.
- Screen 3D and camera motion use `TransitionTiming` (lead-in, hold, optional return). Touching clips chain; otherwise return to base.
- Auto zoom groups clicks that are close in time and space into one stable camera region. It extends the hold through continuous pointer activity and merges touching automatic envelopes so the camera does not stop/start on every click.
- Cursor smoothing is a real 240 Hz spring with click anchoring, not per-interval easing. The synthetic cursor is reconstructed from the recorded pointer path and projected with the screen.
- Opening sequences use a stable roster captured at the first frame, a staggered timeline and nonlinear curves.

## Visual language

- Light workspace: near-white panels on a soft gray stage, near-black primary actions, hairline borders, small radii.
- Mint green for automatic zoom clips; red for the playhead and recording state; blue-ish camera clips; tan stickers; lavender mosaics.
- Typography: Alibaba PuHuiTi 3.0. The port subsets the font for the UI character set and preserves the font license.
- The editor is preview-first: large canvas, compact transport, timeline below, inspector on the right on wide screens.

## Platform limits that shaped the port

- Android has no public per-window or per-area screen capture; it captures the display.
- Android has no global mouse stream; touch capture requires accessibility and is click/scroll-based.
- System audio capture is opt-in per source app.
- MediaProjection requires a foreground service and a per-session consent.
- Android screen recordings are frequently portrait; the canvas must adapt rather than assuming 16:9.
