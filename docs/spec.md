# DualPlay — specification

Status: draft v1, 2026-11-22. Owner: the DualPlay thread. Parser work stays in the DualDex thread.

## 1. Goal

A console-first Android libretro frontend for dual-screen handhelds (primary target: AYN Thor) that
fully replaces RetroArch's menu, runs pinned libretro cores in-process, renders the game on the top
display and hosts DualDex on the bottom display, reading core memory in-process.

## 2. Repositories and licences

| Repo | Role | Licence |
| --- | --- | --- |
| `Darkaxt/DualPlay` (public) | The app: UI, settings model, core pinning, DualDex host | GPL-3.0 (links LibretroDroid) |
| `Darkaxt/LibretroDroid` (public fork of `Swordfish90/LibretroDroid`) | Runtime: libretro frontend library. Consumed as a git submodule at `runtime/LibretroDroid` | GPL-3.0 |
| `Darkaxt/DualScreenDex` | DualDex. Becomes a library dependency in Stage 4; the standalone app + UDP stays as the stock-RetroArch fallback | MIT (combined build is GPL-3.0) |
| `Darkaxt/G-Shop` | Separate Flutter app. Launches DualPlay through an intent contract (Stage 5) | MIT |

Not used: `BluetileEntertainment/LibretroDroid` (stale 2024 copy), RetroArch's C menu, any code or keys
from the Beacon/Radikal launcher (design reference only, `D:/Projects/GameLauncherResearch`).

## 3. Requirements

### R1 Runtime
- R1.1 Run libretro cores in-process through the LibretroDroid fork.
- R1.2 Memory API: read core memory by 32-bit **bus address** with the same semantics as RetroArch's
  `READ_CORE_MEMORY` (memory-map descriptors from `RETRO_ENVIRONMENT_SET_MEMORY_MAPS`), falling back to
  `retro_get_memory_data(RETRO_MEMORY_SYSTEM_RAM)` when a core publishes no map. Reads happen between
  frames (never during `retro_run`). Read-only for DualDex; no write API is exposed to DualDex.
- R1.3 Rewind, run-ahead, achievements (rcheevos), shader presets (librashader candidate — Android
  support must be verified before adoption; fall back to the existing GLSL shader pipeline otherwise).
- R1.4 Hardware-rendered cores work, specifically melonDS (main technical risk).

### R2 Cores
- R2.1 Pinned cores: mGBA (GBA, also GB/GBC), a GB/GBC core (Gambatte or SameBoy, chosen in Stage 2),
  melonDS (DS). Each pin records source repo, commit, build recipe and SHA-256 of the shipped `.so`.
- R2.2 Spike uses a buildbot nightly binary pinned by SHA-256; from Stage 2 cores are built from pinned
  source in CI.

### R3 Displays
- R3.1 Game renders on the top (default) display.
- R3.2 Bottom display shows DualDex through an Android `Presentation` on the secondary display.
- R3.3 Controller input stays with the game (DualDex controller-focus contract); the bottom screen is touch.

### R4 Frontend UI (replaces RetroArch's menu)
- R4.1 Console first: pick system/core, then games.
- R4.2 One curated settings menu per core covering the features that core benefits from, with an
  Advanced section for uncommon options. Frontend settings and core options are one flow.
- R4.3 Outside a game, settings apply to the core.
- R4.4 Only while a game runs, the save action asks whether the change applies to the whole core or to
  this game. Resolution order: game override > core settings > defaults. No other layers.
- R4.5 Controller-first navigation; touch secondary.

### R5 DualDex hosted
- R5.1 DualDex runs as a library on the bottom display, fed by R1.2 instead of UDP.
- R5.2 The standalone DualDex app with UDP remains working for stock RetroArch.

### R6 G-Shop contract
- R6.1 Explicit intent: ROM path/URI + console + core id (+ optional game id). Versioned and validated;
  unknown cores rejected with a clear error.

### R7 Widescreen (last)
- R7.1 GB/GBC/GBA per-game expanded view (PokeDaisy EXPAND-style), possibly fed by DualDex map data.
- R7.2 DS widescreen via a melonDS core fork.

## 4. Stages and acceptance

| Stage | Scope | Acceptance |
| --- | --- | --- |
| 1 Spike | Fork memory API (linear descriptor translation + SYSTEM_RAM fallback, between-frame read under the core lock, frame counter); DualPlay spike app: mGBA on top display, placeholder DualDex `Presentation` on bottom showing live memory | On the Thor: a GBA ROM boots and plays on top; bottom display shows the memory map and a live-updating EWRAM/IWRAM read with frame number; no crash over a bounded run; read cost measured |
| 2 Runtime | Full RetroArch-compatible descriptor preprocessing (select/disconnect/mirrors) with unit tests against RA's algorithm; rewind; run-ahead; rcheevos; shader presets; melonDS HW rendering; source-built pinned cores in CI | Each feature demonstrated on the Thor; melonDS boots a DS game with HW renderer; descriptor tests pass |
| 3 UI + settings | Console-first UI, per-core curated menus, core/game save scope | R4 acceptance checklist on device |
| 4 DualDex library | Extract DualDex reader/UI into a library consumable by DualPlay; in-process adapter for `CoreMemoryReader` | DualDex bottom screen live in DualPlay; standalone UDP path still passes its tests |
| 5 G-Shop | Intent contract + G-Shop launcher entry | G-Shop launches a ROM into DualPlay with the named core |
| 6 Widescreen | R7 | Per-game expanded view on Thor |

## 5. Deferral ledger

| ID | Item | Target | Closing condition |
| --- | --- | --- | --- |
| D-MEM-1 | Spike translates only each descriptor's primary `[start, start+len)` range; RA select/disconnect mirror semantics | Stage 2 | **Closed 2026-11-22** (Batch A): RA-exact port, host test 51/51 against RA `a9850c6f`, mGBA/Gambatte/melonDS DS maps live on Thor |
| D-MEM-2 | Reads wait on the core lock, which `step()` also holds during frame pacing (`fpsSync->wait()`); emulator max 76 ms | Stage 2 | **Closed 2026-11-22** (Batch A): pacing outside the lock; Thor p99 251–906 µs on three cores |
| D-TOUCH-1 | DS bottom-screen touch not exercised live (no game input over adb) | Stage 2 hands-on check | A tap on the Thor bottom screen selects a Zoo Keeper menu item |
| D-GL-1 | melonDS DS Android build exposes no OpenGL renderer (software only, full speed at native res) | Sub-stage 2-GL, after Batch E | GLES renderer in a `Darkaxt/melonds-ds` fork runs ≥2x internal res at full speed on Thor |
| D-LIFE-1 | Spike activity is recreated once at launch | Stage 3 | Root cause identified; single `onCreate` per launch on Thor |
| D-CORE-1 | Spike mGBA is a buildbot nightly pinned by hash | Stage 2 | mGBA built from a pinned commit in CI |

## 6. Constraints
- Builds through the Coordinator gradle-build-gate; device work through the android-device route with
  ownership checks; persistent repos on D:, scratch on E:/Temp or D:/Temp, cleaned promptly.
- Commit and push every change.
