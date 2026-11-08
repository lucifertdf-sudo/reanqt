# Mood Player
Lightweight Android music player with local library and optional direct USB DAC output.

## v1.5.0
- Artwork-led Apple Music-inspired Home, Library and Now Playing screens.
- Local music scan, file import, and 5-band Android graphic EQ.
- Native USB Audio Class 1/2 direct output engine for FLAC/WAV, with USB permission and diagnostic screen.
- MP3/AAC and unsupported formats remain on Android's regular output.
- TIDAL remains a search handoff to the official app/site, not in-app playback.

## Audio accuracy
Direct USB output can bypass Android's AudioFlinger mixer for compatible FLAC/WAV streams when the DAC is initialized and supports the requested stream format. The native libusb engine owns USB interface claims; the Android USB connection is kept open only as the file-descriptor owner and is closed after native shutdown. This avoids competing Java/libusb claims and ensures native transfers stop before the descriptor owner closes.

The bit-perfect indicator reports the engine's internal integer-pass state. It is not an external digital loopback measurement or proof that a specific DAC accepted the intended source bit depth and sample rate. Physical compatibility with individual DACs, including JCALLY JM12/JA11, must be verified on the target device. Software volume changes, DSP, resampling, or Android fallback output are not bit-perfect.

The integrated USB engine is adapted from [tsrossa](https://github.com/yukaatsu/tsrossa), licensed under Apache-2.0; see `third_party/tsrossa/LICENSE`.
