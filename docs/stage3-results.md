# Stage 3 results

Plan: `docs/plan-stage3-ui.md`.

## Batch 3A: library, launch and in-game menu

### Build
- Fork `Darkaxt/LibretroDroid` branch `dualplay` commit `674e4f3`: `LibretroDroid.setPaused(boolean)`. While
  paused, the core does not run and audio stops. The last frame and the secondary surface are still redrawn, and
  frame pacing continues. `paused` and `rewinding` are reset on destroy.
- Gradle gate ticket `e2e4ae57…`:
  - Attempts 1 and 2 failed at `checkDebugAarMetadata`. Compose BOM 2026.11.01 (material3 1.5.0, animation
    1.13.0) needs compileSdk 37.1.
  - Attempt 3 (workflow `6c39ded6…`) built successfully with `compileSdk { version = release(37) { minorApiLevel = 1 } }`.
    The tree exit was verified. targetSdk is still 36.

### Thor (2026-11-22, `bfa98654`, USB, android-device gate, batch b16)
All-files access was granted with `appops set … MANAGE_EXTERNAL_STORAGE allow`. Navigation used only the app's own
test extras (`screen`, `autoLaunch`, `selfTest`); there was no gameplay input.

| Check | Result |
| --- | --- |
| Launcher lists the SD-card systems with counts | `library scan ms=212 gb=0, gbc=0, gba=7, nds=1` (`/storage/75D7-DC5F/ROMs`). The GBA list groups the `Hacks` subfolder. |
| `.7z` GBA ROM launches from the list | Advance Wars (USA).7z → extracted to `cache/extracted/295f…/Advance Wars (USA).gba`; mGBA runs |
| `.7z` DS ROM launches from the list | 0034 - Zoo Keeper (USA).7z → `cache/extracted/9ce1…/…nds`; melonDS DS runs on both displays |
| State save and load round trip | mGBA: 462,912 B saved in 13 ms, loaded in 1 ms, `ok=true`. melonDS DS: 19,228,045 B saved in 127 ms, loaded in 24 ms, `ok=true`. |
| The menu pauses the core | The frame counter stays at 366 (GBA) and 362 (DS) across 18 s with the menu open. `stepUs` drops from 449 to about 8. |
| Battery-save files written | `files/saves/mgba/Advance Wars (USA).srm` (65,536 B) and `files/saves/melondsds/0034 - Zoo Keeper (USA).srm` (8,192 B) |

Notes:
- **Battery-save timing.** The save was written when the menu opened. On the later Quit nothing had changed, so
  nothing was rewritten, which is the intended behaviour: write only non-empty, changed data.
- **Save folders.**
  - melonDS DS also creates its own `melonDS DS/` folder inside its per-core save folder.
  - `files/saves/melonDS DS/` at the top level is left over from Stage 2. Nothing uses it.
- **Menu focus (fixed after this build).** The menu opened with no focus outline on its first row, because
  GLRetroView kept view focus. The overlay ComposeView is now focusable and takes focus when the menu opens. This fix
  is built and checked on the Thor in Batch 3B.
- **Gesture handle.** Android's gesture handle stays visible on both panels while system bars are hidden. It was
  not investigated here.

Evidence: `docs/evidence/stage3a-thor-*.png` and `docs/evidence/stage3a-thor-b16-log.txt`.
