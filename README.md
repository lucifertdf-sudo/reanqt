# Mood Player
Lightweight native Android local audio player.

## v1.1
- Android 8.0+ (API 26), navy/blue dark interface and adaptive launcher icon.
- Home, Library, EQ, Wrapped and Settings tabs.
- Multi-file local playback, queue list, basic queue search, play/pause, previous/next and seek.
- Local listening recap for playback sessions and approximate playing time.
- 5-band Android graphic equalizer, subject to device AudioEffect support.

## Audio accuracy
This release uses Media3 through Android's regular audio route. It does **not** implement verified exclusive USB output, bit-perfect playback, or parametric EQ with adjustable per-band frequency/Q controls. The graphic EQ changes the signal; turn it off for an unprocessed path. Android system mixing may resample audio.

A push to `main` runs GitHub Actions and publishes the debug APK to GitHub Releases.
