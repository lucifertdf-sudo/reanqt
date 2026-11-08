# Mood Player
Lightweight Android music player with local library and optional direct USB DAC output.

## v1.5.0
- Artwork-led Apple Music-inspired Home, Library and Now Playing screens.
- Local music scan, file import, and 5-band Android graphic EQ.
- Native USB Audio Class 1/2 direct output engine for FLAC/WAV, with USB permission and diagnostic screen.
- MP3/AAC and unsupported formats remain on Android's regular output.
- TIDAL remains a search handoff to the official app/site, not in-app playback.

## Audio accuracy
Direct USB output can bypass Android's AudioFlinger mixer for compatible FLAC/WAV streams when the DAC is initialized and supports the requested stream format. The UI only displays bit-perfect status when the native engine reports an active integer pass. Physical compatibility with individual DACs, including JCALLY JM12/JA11, must be verified on the target device. Android fallback output is not bit-perfect.

The integrated USB engine is adapted from [tsrossa](https://github.com/yukaatsu/tsrossa), licensed under Apache-2.0; see `third_party/tsrossa/LICENSE`.
