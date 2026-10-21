# VEXORA AI

Standalone, fully offline Android video editor. No accounts, no social features, no servers, no cloud, no AI chat,
and the manifest does not request the INTERNET permission.

## What it does (all on-device)
- **Audio analysis** – `BeatAnalyzer`: MediaCodec decode → onset-strength envelope → tempo by autocorrelation → beat grid snapped to onsets.
- **Video analysis** – `VideoAnalyzer`: samples frames, scores motion and contrast per 0.5 s.
- **Planning** – `EditPlanner`: cuts on beats (cut rate set by style/prompt), picks the highest-scoring unused footage, keeps chronological order.
- **Rendering** – `Renderer`: Media3 Transformer (H.264/AAC, hardware encoder): color grade, punch-in zoom on alternate cuts, aspect/resolution crop, music mixed in and trimmed to the edit.
- **Prompt** – keyword rules (`PromptRules`): fast/slow, warm/cool, dark/bright, b&w, vivid, zoom, "20 seconds". It is not a language model.
- **Storage** – projects in `filesDir/projects.json`, videos in `filesDir/videos`, music in `filesDir/music`; export to Movies/VEXORA AI or a chosen folder.

## Build
Push to GitHub: the **Build APK** workflow produces `vexora-ai-debug-apk` under Actions → Artifacts.
Locally: install JDK 17 + Android SDK, run `gradle wrapper --gradle-version 8.7` once, then `./gradlew assembleDebug`.

Requires Android 10 (API 29) or newer.

## Known limitations
- Transitions are hard cuts with beat-aligned punch-in zoom; cross-fades would need a custom GL effect.
- Render must stay in the foreground (screen is kept on while working).
- Languages: English and Spanish for main labels; remaining text is English.
- Not yet tested on a device — expect to fix compile/API issues on first build.
