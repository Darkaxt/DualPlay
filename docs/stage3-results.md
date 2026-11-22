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

## Batch 3B: settings model and per-core menus (Thor, b18): accepted

| Check | Result |
| --- | --- |
| Core options discovered and cached | `files/settings/catalog/mgba.json` (17 options), `gambatte.json` (31, b17), `melondsds.json` (80). DS layout options the runtime fixes are `locked` and hidden from the menus. |
| In-game Save asks for the scope | Leaving Settings with a pending change opens "This game / All mGBA games / This session only" (b17 screenshot; b18 steps go through the same `commitPending` path). |
| Core-level change persists | Advance Wars: `mgba_color_correction OFF -> GBA`, saved for all mGBA games → `core/mgba.json = {"mgba_color_correction":"GBA"}`; core reports `GBA` live. |
| Game-level override persists | Advance Wars: `GBA -> GBC`, saved for this game → `game/mgba/Advance Wars (USA).json = {"mgba_color_correction":"GBC"}`; core reports `GBC` live. |
| Resolution order game > core > default | Kirby launches with `GBA(core)`, core reports `GBA`. Advance Wars relaunches with `GBC(game)`, core reports `GBC`. |
| Launcher settings edit the core level | `screen=settings:mgba` shows DualPlay settings, the curated mGBA options and Advanced; changes call `saveCore`. |
| Controller focus | Every screen now opens with a highlighted first row (in-game menu, settings page, launcher home and core settings). b17 had no highlight because Compose stayed in touch mode; `rememberInitialFocus` now requests keyboard input mode first. |

Notes:
- Options marked "(Restart)" by the core are saved but applied on the next launch; the menu says so.
- Hold L2 to rewind and hold R2 to fast-forward are implemented but need a hands-on controller check.

Evidence: `docs/evidence/stage3b-thor-*.png` and `docs/evidence/stage3b-thor-b18-log.txt`.

## Batch 3C: shaders, RetroAchievements sign-in, lifecycle (Thor b19/b20, emulator): implemented

| Check | Result |
| --- | --- |
| Bundled presets | 11 licence-reviewed presets (LCD, colour, CRT) in `assets/shaders/builtin`, licences in its `LICENSES.md`. Installed once per APK: `builtin shaders installed files=24`. |
| Preset per core/game | GBA Advance Wars with `lcd-sameboy` (game layer) loads in 173 ms, shaderUs 70–113, full speed. |
| DS bottom screen shaded (D-SHADER-1) | Zoo Keeper with `crt-lottes-fast`: both screens shaded, shaderUs 77–96, bottomShaderUs 51–84, ~300 frames per 5 s. |
| Shader chosen in the menu applies | selfTest `shader:builtin/crt-geom.slangp` goes through the menu's change path: `setting changed dualplay_shader=builtin/crt-geom.slangp live=true`, loaded on both surfaces (246 ms, 32 ms). GBA Kirby: `color-gba` then `none` (shaderUs back to 0). |
| RetroAchievements sign-in screen | Launcher → Settings → RetroAchievements renders with focus on User name. Sign-in uses RA `login2`; only `{user, token}` are kept, AES-GCM with an Android Keystore key in `files/ra/account.bin`. The token is never logged. |
| D-LIFE-1 | Root cause: a boot-time overlay change (`CONFIG_ASSETS_PATHS`) relaunched the activity. Reproduced on an API 35 emulator by toggling a cutout overlay (`configDiff=0x80000000`, n=2). With `assetsPaths` declared the same toggles give `configChanged (handled)`. A forced recreation (`selfTest recreate`) hands over a 182 KB resume state, `resume state ok=true`, and the old runtime thread stops. Thor: one `onCreate` per launch in every b16–b20 launch. |
| Recreation on Thor (b21) | Zoo Keeper with `crt-lottes-fast` chosen in the menu (not yet saved), then `selfTest recreate`: `handover state bytes=19228045 pending=1`, n=2, `resume state ok=true` 0.6 s after destroy, preset reloaded on both surfaces, ~300 frames per 5 s afterwards. The in-game menu is closed after a recreation. |

Findings:
- **D-SHADER-2 (new).** Curvature presets bend both DS screens as one tube (see `stage3c-thor-nds-*-crt.png`).
  Ledgered for Stage 6.
- The Gambatte core is GPL-2.0, loaded as a separate plugin library.

Still needs the user (Stage 3 acceptance): RetroAchievements sign-in and one softcore unlock (D-RA-1), the feel of
controller navigation and the L2/R2 rewind and fast-forward hotkeys, and the DS bottom-screen tap (D-TOUCH-1).

Evidence: `docs/evidence/stage3c-thor-*.png`, `docs/evidence/stage3c-thor-logs.txt` and
`docs/evidence/stage3c-life1-emulator.txt`.
