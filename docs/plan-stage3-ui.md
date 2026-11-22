# Stage 3 plan: UI and settings

Source: `docs/spec.md` R4 and the Stage 3 row. Deferrals targeted at Stage 3: D-LIFE-1, D-RA-1, D-SHADER-1.

Device facts (Thor, read-only listing 2026-11-22):
- ROMs are on the SD card in ES-DE layout at `/storage/75D7-DC5F/ROMs/<system>/`, with subfolders such as
  Hacks, Translations and Aftermarket-Unlicensed.
- Most ROMs are `.7z` archives.
- RetroArch keeps its own saves under `/storage/emulated/0/RetroArch`. DualPlay keeps its own saves and does
  not touch RetroArch's.

## Batch 3A: library, launch and in-game menu (one usable loop)
1. Compose launcher (`MainActivity`) on the top display, controller-first:
   - D-pad moves focus. A confirms (the system falls back from BUTTON_A to DPAD_CENTER). B goes back.
   - Screens: storage-access prompt → systems (GB, GBC, GBA, DS, each with a game count) → games, grouped by
     subfolder → launch.
2. ROM library:
   - Roots: every mounted storage volume's `ROMs` folder (ES-DE layout). Folder map: `gb`, `gbc` → Gambatte;
     `gba` → mGBA; `nds` → melonDS DS.
   - Recursive scan for core extensions plus `.zip` and `.7z`.
   - Access: all-files access (`MANAGE_EXTERNAL_STORAGE`), granted once in system settings.
3. Archives:
   - Extract the first entry with a supported extension into `cacheDir/extracted/<key>/`. The key covers path,
     size and modification time.
   - Reuse the extraction on the next launch.
   - Least-recently-used eviction above 4 GiB.
   - Libraries: commons-compress plus xz for 7z; java.util.zip for zip.
4. `GameActivity` replaces `SpikeActivity`:
   - Launched with the playable path and core id. It keeps the presentation, the probe and the app-owned
     test extras.
   - Battery saves: load `saves/<core>/<rom>.srm` into `saveRAMState`. Write `serializeSRAM()` atomically on
     pause and on quit, only when the data is non-empty and has changed.
5. In-game menu, opened with Select+Start or the Back key. It pauses the core and is drawn on the top display:
   - Resume.
   - Save state and load state, 4 slots stored in `states/<core>/<rom>.stN`.
   - Reset.
   - Quit (saves first).

Accept 3A on the Thor:
- The launcher lists the SD-card systems with counts.
- A `.7z` GBA ROM and a `.7z` DS ROM launch from the list.
- A state save and load round trip works.
- Battery-save files are written on quit.

Navigation is driven only through app-owned debug hooks. There is no gameplay input over adb.

## Batch 3B: settings model and per-core menus (R4.2–R4.4)
6. Settings model:
   - Frontend settings: shader preset, rewind on/off and budget, run-ahead frames, fast-forward speed,
     RetroAchievements on/off.
   - Core options: discovered from the core and cached per core with descriptions and allowed values.
   - Stored as JSON: `settings/core/<id>.json` and `settings/game/<id>/<rom-key>.json`.
   - Resolution order: game > core > defaults.
7. Per-core menus:
   - A curated list for each core (frontend settings plus the core options that matter for that core).
   - An Advanced section with all remaining options.
   - One flow covering both frontend settings and core options.
8. Scope:
   - From the launcher, settings edit the core level.
   - In game, Save asks "This game" or "All <core> games".
   - Applied at launch. Options that can change live are applied immediately.

Accept 3B on the Thor:
- A core-level change and a game-level override both persist and resolve in the stated order.
- The in-game save asks for the scope.

## Batch 3C: shaders, accounts, lifecycle
9. Curated slang presets shipped in assets after a licence review, selectable per core or game. Covers
   D-SHADER-1: shade the DS bottom screen, or document it as top-only.
10. RetroAchievements login screen:
    - The token is stored in app-private encrypted storage.
    - Covers D-RA-1. Its acceptance needs the user's account, entered in the app by the user.
11. D-LIFE-1: find the root cause of the activity being recreated at launch, and fix it.

Accept 3C on the Thor: a shader chosen in the menu applies, and the D-LIFE-1 acceptance holds.

## Hands-on checks needing the user
Done once per stage: controller navigation feel (R4.5), the D-TOUCH-1 tap, and the D-RA-1 login.
