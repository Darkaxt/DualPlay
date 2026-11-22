# Stage 2 results

## Batch A: runtime correctness, multi-core, DS on two displays

### Build
- Fork `Darkaxt/LibretroDroid` branch `dualplay` commit `dbe5723`.
- Gradle gate readiness ticket `329bde44866b1e6f6be16c5b2d229cea`, attempt 1 (workflow `eb2406a2…`), BUILD SUCCESSFUL, tree exit verified.

### Host test (D-MEM-1)
`libretrodroid/src/test/cpp/memorymap_test.cpp`, run via pipeline-gate: 51 checks, 0 failures. The oracle is
RetroArch master `a9850c6f` (`runloop.c` preprocessing, `command.c` address resolution), copied verbatim.
Every address in each fixture matches (mismatch 0):
- mGBA GBA map (2.02 M addresses; preprocessing stops at the non-power-of-two VRAM, as in RA);
- dense SNES LoROM (16 M addresses);
- derived select, GB echo RAM, invalid start.

### Thor (2026-11-22, AYN Thor `bfa98654`, USB, android-device gate)
The screens had to be awake: with the panels asleep the GL surface is never created. The first batch captured black
frames and logged no probes. Later batches send `KEYCODE_WAKEUP` first and `KEYCODE_SLEEP` at the end.

| Core | ROM | Result | Read cost (256 B every frame, incl. lock) |
| --- | --- | --- | --- |
| mGBA | corpus `0001` GBA (16 MiB) | Boots, 59.7 fps; 11-region map with select/disconnect | p50 57–67 µs, p99 470–906 µs, max 3.7 ms (first window) |
| Gambatte | µCity 1.3 (GPL-3.0 homebrew, CGB) | Title screen renders; WRAM/HRAM/VRAM descriptors with select masks | p50 29–55 µs, p99 271–428 µs, max 545 µs |
| melonDS DS | Zoo Keeper (USA) | Boots via FreeBIOS + built-in firmware; top screen on top display, bottom screen on bottom display; ~59.8 fps (≈301 frames per 5.03 s) | p50 46–48 µs, p99 251–662 µs, max 2.4 ms |

- D-MEM-2: p99 < 1 ms on all three cores. The maxima (2.4–3.7 ms) are isolated and well under one 16.7 ms frame.
  The Stage 1 one-frame wait (16.7 ms) is gone.
- DS split: the secondary EGL surface is created on the same context (`SecondarySurface: created EGL surface with
  config 6`). The bottom presentation shows the bottom DS screen with the read-cost overlay
  (`p50/p99/max 52/611/3946 µs` at frame 2296).
- melonDS HW-render probe: this build exposes **no render-mode option** (106 options logged; none for the
  renderer/OpenGL), so it is software-only. `melonds_threaded_renderer=enabled` runs at full speed at native
  resolution. A GPU upscaling renderer needs a GLES build of melonDS DS → D-GL-1.
- Logged but harmless: `Failed to allocate memory using ftruncate` (JIT fast-memory arena falls back), PCap/Wi-Fi
  init failed (no networking needed).
- Not exercised: bottom-screen touch. No game input is sent over adb; this needs a hands-on tap → D-TOUCH-1.
- Evidence: `docs/evidence/stage2a-thor-{gba-up,gba-down,gbc-up,nds-up,nds-down}.png`,
  `stage2a-thor-gba-gbc-logcat.txt`, `stage2a-thor-nds-logcat.txt`.

### Acceptance (plan Batch A)
| Item | Result |
| --- | --- |
| 1 D-MEM-2 (p99 < 1 ms, max not tied to the frame period) | Met on mGBA, Gambatte, melonDS DS → closed |
| 2 D-MEM-1 (RA preprocessing + host test; mGBA unchanged on device) | Met → closed |
| 3 Core registry (pinned `.so` + SHA-256) | Met (`cores/README.md`) |
| 4 DS on two displays, touch works, fps recorded | Split + fps met; touch pending a hands-on check (D-TOUCH-1) |
| 5 melonDS HW-render probe | Done: no GL renderer in this build → sub-stage 2-GL tracked as D-GL-1 |
