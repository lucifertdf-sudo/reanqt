# Mood Player

Mood Player is a lightweight native Android local music player.

## Current build
- Native Android app (min Android 8.0 / API 26)
- Local audio file picker with multi-select
- Playback queue, play/pause, previous/next and seek
- FLAC/WAV/MP3/M4A playback through AndroidX Media3
- Dark navy and blue minimal interface

## Build APK
Every push to `main` and manual workflow dispatch builds a debug APK with GitHub Actions. Open **Actions → Build Mood Player APK → latest successful run → Artifacts** and download `Mood-Player-debug-APK`.

## Audio transparency
This initial build uses Android's normal audio routing via Media3. It does **not** claim verified bit-perfect USB output, exclusive USB access, PEQ, lyrics, embedded cover extraction, or Wrapped analytics yet. Those need separate implementation and device testing. For the cleanest output, disable Android sound effects and other DSP.
