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

## Thor (2026-11-22, AYN Thor `bfa98654`, USB, android-device gate ticket `60bc12ee…`)
- Cold start 311 ms. Same corpus ROM boots (LeafGreen-based title screen) on the top display (1920x1080).
- Presentation lands on `4:Screen-2` (bottom display), non-focusable; shows the 11-region map and a live IWRAM dump.
- Emulation ~59.7 fps (≈305 frames per 5.1 s probe interval over 60 s). UI thread: 602 frames, 0.33 % janky, p99 5 ms.
- Read cost over ~550 reads: avg 0.2–0.37 ms, max 16.7 ms = one frame-pacing wait (D-MEM-2 confirmed on hardware).
- Activity created once (D-LIFE-1 did not reproduce on the Thor; keep open until Stage 3 confirms it is emulator-only).
- No crash; app force-stopped and lease released afterwards. The debug APK and the test ROM in app storage remain installed.
- Not exercised: controller input (no gameplay input in this run). The bottom display shows the gesture pill (Presentation not immersive) — Stage 3 UI.
- Evidence: `docs/evidence/stage1-thor-top.png`, `stage1-thor-bottom.png`, `stage1-thor-logcat.txt`.

## Acceptance (spec Stage 1 row)
| Criterion | Result |
| --- | --- |
| GBA ROM boots and plays on top | Boots and runs at ~60 fps on top; input not exercised |
| Bottom shows memory map + live read with frame number | Yes |
| No crash over a bounded run | Yes (60 s) |
| Read cost measured | Yes; avg ≈0.3 ms, max 16.7 ms → D-MEM-2 (Stage 2) |

Stage 1 accepted with the input check carried into Stage 3 (controller-first UI) and D-MEM-1/D-MEM-2/D-CORE-1/D-LIFE-1 open.
