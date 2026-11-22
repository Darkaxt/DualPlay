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

## Batch B: rewind + run-ahead (fork `22b3c4e`, async encode `39dfd3a`)

Rewind stores each capture as a sparse XOR delta to the previous state: changed 64-bit word runs (a header word
`unchanged << 32 | changed`, then the XOR words), zlib level 1. The emulation thread only serializes into a handoff
buffer; a worker thread builds and compresses the delta. Run-ahead is RetroArch's single-instance algorithm
(N frames with video/audio suppressed, serialize, show, unserialize). The first rewind version (full-state XOR on
the emulation thread) cost melonDS DS ~20 ms per capture; the numbers below are the final version.

### Thor (2026-11-22, `bfa98654`, batch `b9`; first-version numbers from `b7`/`b8`)
| Core | Setting | Emulation-thread capture | Worker encode | Entry size | Result |
| --- | --- | --- | --- | --- | --- |
| mGBA | rewind 64 MB, every 4 frames | 0.3–0.5 ms | 0.3–1.9 ms | ~1.1 KB | 3 s hold walked back 180 entries × 4 = 720 frames ≈ 12 s; probe p99 ≤ 996 µs |
| mGBA | run-ahead 2 | – | – | – | 300 frames / 5 s (full speed); step 3.6–6.7 ms |
| Gambatte | rewind every frame + run-ahead 2 | 15–18 µs | 0.4–1.9 ms | ~0.2 KB | full speed; step ~0.8 ms; 3 s hold walked back 180 frames |
| melonDS DS | rewind 128 MB, every 4 frames | 3.8–4.3 ms | 6.5–6.8 ms | ~6–14 KB | ~60 fps; probe p99 76–431 µs (first version: 14–28 ms); 3 s hold walked back 177 × 4 frames ≈ 11.8 s, rewinding step ~15.5 ms |

### Acceptance (plan Batch B)
| Item | Result |
| --- | --- |
| 6 Rewind ≥10 s on GBA at full speed | Met (~12 s in a 3 s hold) |
| 7 Run-ahead 1–2 on mGBA/Gambatte at full speed, state round-trip measured | Met (run-ahead 2 on both) |

## Batch C: achievements (fork `32d0f4d`)

rcheevos v12.5.0 (MIT, vendored at `libretrodroid/src/main/cpp/rcheevos`) runs inside the runtime:
- `rc_client`: every call runs on the emulation thread, and requests from other threads are queued.
- Memory: read through `rc_libretro` regions built from the core's raw memory descriptors, the same mapping
  RetroArch uses.
- Per frame: `rc_client_do_frame`.
- HTTP: done by the app (`RetroAchievementsBridge`, User-Agent `DualPlay/<ver> (Android x) rcheevos/12.5`).
- Mode: softcore only, so rewind, run-ahead and state loads stay allowed.

Identification without an account hashes the ROM (`rc_hash`) and sends `gameid` (resolve hash).

### Thor (batch `b8`)
| ROM | Hash | RetroAchievements game id |
| --- | --- | --- |
| corpus `0001` GBA | `45487c50d750d30f4f465f6ed3fb6d31` | **14655** (identified) |
| Zoo Keeper (USA) NDS | `8e2d2aa06485576fdeafc3a1067f33d8` | 0 (this dump is not in the RA database) |
| µCity 1.3 GBC | `83fdaac59da9d3e8b1f73f529401a626` | 0 (homebrew, expected) |

Each request returned in 0.4–0.7 s.

### Acceptance (plan Batch C)
| Item | Result |
| --- | --- |
| 8 Identification by hash for a known ROM, no account | Met (game 14655) |
| Login / game load / unlocks | Implemented, untested without the user's account → D-RA-1 (Stage 3) |

## Batch D: slang shader presets (fork `99833a9`)

librashader 0.12.1 (upstream `9d0116f`, C API, `runtime-opengl` only) is cross-built for `aarch64-linux-android`
through rust-build-gate. It ships as `app/src/main/jniLibs/arm64-v8a/librashader.so` with the NDK 27.2
`libc++_shared.so` (hashes in `cores/README.md`).

The runtime opens it with `dlopen` via the vendored `librashader_ld.h`, so the library stays optional. Without
it, a preset request fails and the built-in GLSL path keeps rendering.

`ShaderPreset` (fork `shaderpreset.{h,cpp}`) does the work, frame by frame:
1. Runs the filter chain on the core's frame texture.
2. Renders into an owned `GL_RGBA8` output texture sized to the viewport.
3. Restores the renderer's GL state.
4. Blits the output into the foreground viewport.

The GLSL ES version is detected (`glsl_version = 0`): `crt-geom` needs ESSL 310 or newer.

Two fixes were found on the Thor:
- **b10: `crt-geom` failed to compile under ESSL 300.** Fixed by version detection.
- **b10/b11: the chain loaded and ran, but its output was black.** librashader binds mipmap-filtered samplers.
  The frame texture had one level and the default `GL_TEXTURE_MAX_LEVEL` of 1000, so the GPU treated it as
  incomplete and sampled black. Fixed by limiting the frame texture to level 0 (`imagerendereres3.cpp`).

### Thor (batch `b12`, evidence `docs/evidence/stage2d-thor-*`)
| Preset | Core / ROM | Passes | Load | Output | Chain cost per frame | Speed |
| --- | --- | --- | --- | --- | --- | --- |
| `crt/crt-geom` | mGBA / corpus GBA | 1 | 310 ms | 1620x1080 | 99–126 µs | full (300 frames per 5 s) |
| `handheld/lcd-grid-v2` | Gambatte / µCity | 1 | 142 ms | 1200x1080 | 94–201 µs | full |
| `crt/crt-guest-advanced-fast` | melonDS DS / Zoo Keeper (top) | 9 | 326 ms | 1440x2160 | 281–379 µs | full |

The screenshots show each effect on the top display: curvature with scanlines, the LCD grid, and the CRT mask.
The one-shot diagnostic at frame 120 reports `glError=0`.

Preset files come from libretro/slang-shaders. They have mixed licences, so they are device test data and
are not committed. Stage 3 ships a curated preset set with licence review in app storage (`files/shaders`).

The DS bottom screen, drawn on the secondary surface, stays unshaded. Tracked as D-SHADER-1.

### Acceptance (plan Batch D)
| Item | Result |
| --- | --- |
| librashader cross-built for aarch64-linux-android through rust-build-gate and loaded in the runtime | Met |
| A slang preset loads and renders on GLES 3 on the Thor | Met (three presets, 1 to 9 passes, full speed) |

