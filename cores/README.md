# Pinned cores

| Core | ABI | Source | Version | SHA-256 |
| --- | --- | --- | --- | --- |
| mGBA (`libmgba_libretro_android.so`) | arm64-v8a | buildbot.libretro.com nightly `android/latest/arm64-v8a`, Last-Modified 2026-11-21 07:34 GMT | `0.11-212-7a12d6d` | `b5a6ed40ede735ea7f1d56916f4ceed8c2aabe4d481fe3383ae2b5e5485b6545` |
| Gambatte (`libgambatte_libretro_android.so`) | arm64-v8a | buildbot nightly `android/latest/arm64-v8a`, Last-Modified 2026-11-21 | `v0.5.0-netlink 138bcbb` | `513e953c2c20cd209cf5ee3ef85a8428ec048d961434d896c01e4b1fe783ba58` |
| melonDS DS (`libmelondsds_libretro_android.so`) | arm64-v8a | buildbot nightly `android/latest/arm64-v8a`, Last-Modified 2026-11-21 | `1.4.0` (string in binary) | `9915df9386e7dec21ef1a9584e56d1fb1386ff60e5cc3b5ea411b5926a02d8a0` |

Licences and sources:
- mGBA: MPL-2.0, https://github.com/libretro/mgba (upstream https://github.com/mgba-emu/mgba).
- Gambatte: GPL-2.0, https://github.com/libretro/gambatte-libretro.
- melonDS DS: GPL-3.0, https://github.com/JesseTG/melonds-ds.

GB/GBC core choice (R2.1, 2026-11-22): Gambatte. It publishes a libretro memory map and is the
common RetroArch GB/GBC core; the SameBoy nightly was only 307 KB at download time and was not
adopted.
The binary lives in `app/src/main/jniLibs/<abi>/`. Building cores from pinned source in CI is deferral D-CORE-1 (Stage 2).
