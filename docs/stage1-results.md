# Stage 1 results

## Build
- Gradle gate ticket `e12cddd0e342631faa052de6aa2dfd34`; attempt 3 (`aa0cd26f…`) COMPLETED, tree exit verified.
  - Attempt 1 failed: malformed `local.properties` SDK path (fixed).
  - Attempt 2 failed: upstream headers missing `#include <functional>` under NDK 27 libc++ (fork `50b0ec5`).
- APK: `lib/arm64-v8a/liblibretrodroid.so` + `libmgba_libretro_android.so`.

## Emulator (2026-11-22, disposable API 35 x86_64 AVD, arm64 via native bridge, overlay display 1280x720)
- ROM (corpus `0001`, 16 MiB GBA) boots; Game Freak intro renders on display 0.
- Presentation lands on `3:Overlay #1`, non-focusable.
- mGBA publishes 11 descriptors through `SET_MEMORY_MAPS`, e.g. IWRAM `0x03000000+0x8000`, EWRAM `0x02000000+0x40000`,
  VRAM, OAM, palette, IO, ROM mirrors, SRAM `0x0E000000`. Reads of `0x03000000` return 256/256 bytes.
- Read cost (incl. lock wait): avg 1.5–2.0 ms, max 76.6 ms. Cause: `step()` holds the core lock across
  `fpsSync->wait()` (frame pacing sleep), so a read can wait a full frame (worse on the slow translated emulator).
  → deferral D-MEM-2.
- Frame rate on the emulator ~17–26 fps (ARM translation); not representative.
- Activity was created twice at launch (second `onCreate` ~3 s later) → D-LIFE-1.
- No crash (empty crash buffer); process alive after 25 s.
- Evidence: `docs/evidence/stage1-emulator-top.png`, `docs/evidence/stage1-emulator-logcat.txt`.

## Thor
Pending: device owned by another thread at the time; request queued in the android-device gate.
