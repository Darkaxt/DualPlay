# Stage 4 plan: DualDex on the bottom display

Status: 4A done 2026-11-22; 4B in progress. Spec: `spec.md` R5 and Stage 4 ("DualDex bottom screen live in DualPlay; standalone UDP
path still passes its tests").

## What DualDex needs from its host

Read from `Darkaxt/DualScreenDex` at `751196a` (module `retroarch-session`):

| Need | RetroArch provides it by | DualDex code |
| --- | --- | --- |
| Session identity | `GET_STATUS` → `PLAYING <system>,<basename>,crc32=<CRC>` | `RetroArchStatusParser`, `RomSessionResolver` (matches the CRC32 against its own granted ROM index) |
| Live memory | `READ_CORE_MEMORY <addr> <len>` → `READ_CORE_MEMORY <addr> <hex bytes>` | `CoreMemoryReadSession` (chunked, heartbeat-driven) |
| Save files | `GET_CONFIG_PARAM savefile_directory`; DualDex reads `.srm` directly with all-files access | `RetroArchSetupCoordinator.directSaveDirectories` |
| Version, other dirs | `VERSION`, `GET_CONFIG_PARAM savestate_directory / system_directory` | `NetworkCommandClient` |

All of it goes through one seam: `NetworkCommandTransport { send(ByteArray); poll(): ByteArray? }`, injected as
`transportFactory` (for example in `BattleMemoryCoordinator`). Parsing never sees UDP.

## Approach

Two steps. Both share one command handler in DualPlay, so the second step reuses the first.

### 4A: DualPlay speaks the RetroArch network-command protocol (DualPlay only)

- `NetworkCommandServer`: a UDP listener on 127.0.0.1:55355 while a game runs. It answers `VERSION`, `GET_STATUS`
  (system id DualDex understands, basename, CRC32 of the loaded ROM), `GET_CONFIG_PARAM` and
  `READ_CORE_MEMORY` through the runtime's RetroArch-exact memory API (`LibretroDroid.readMemory`, read-only, between
  frames). Unknown commands are ignored, as RetroArch does. No write command is implemented (R1.2).
- Battery saves move to shared storage so DualDex can read them: `/storage/emulated/0/DualPlay/saves/<core>/`.
  Existing app-private saves are migrated once. RetroArch's folders are never touched.
- Setting "DualDex link, read-only" (default on) turns the server off. DualPlay does not launch the DualDex app:
  on the Thor (b22) the launched DualDex window took the top focused display (`mTopFocusedDisplayId=4`), which
  would steal controller input from the game, and it opened on its unconnected start screen. In-process hosting
  (4B) replaces launching it.
- Correction (4B code reading): DualDex's command monitor starts when its coordinator is created
  (`RetroArchSetupCoordinator` `init` schedules the heartbeat at once), not after "Connect RetroArch" is chosen. The
  b22 "DualDex sent no packets" was most likely the `::1` bind (fixed in b25), not a UI gate. Not re-checked on the
  device with the standalone app.
- Acceptance 4A: on the Thor, the server answers DualDex's commands over real UDP from 127.0.0.1, in formats its
  parsers accept (verified, see below). The end-to-end check with the unmodified DualDex app is an optional hands-on
  check.

#### 4A results (2026-11-22)

- Thor b24: the server received nothing. It was bound to `InetAddress.getLoopbackAddress()`, which is `::1` on
  Android, while clients send to `127.0.0.1`. It now binds `127.0.0.1` explicitly.
- Thor b25 (Pokemon Unbound, mGBA, debug selfTest `netcmd`, which sends the commands from inside the app over
  UDP, plus `toybox nc` from the shell):
  - `VERSION` → `1.19.1`
  - `GET_STATUS` → `GET_STATUS PLAYING game_boy_advance,Pokemon Unbound (v2.1.1.1),crc32=4b3d4957`, and `PAUSED`
    while the in-game menu is open
  - `GET_CONFIG_PARAM savefile_directory` → `/storage/emulated/0/DualPlay/saves/mgba`; unknown names →
    `unsupported`
  - `READ_CORE_MEMORY 2000000 16` → 16 hex bytes (EWRAM); `3000000 8` (IWRAM) and a 2000-byte read are answered
    (the reply is capped at 1354 bytes; DualDex reads at most 1024 per request)
  - `WRITE_CORE_MEMORY` gets no reply and changes nothing
  - CRC32 of the 32 MB ROM in 41 ms; average read 139 µs
- The reply formats were checked against DualDex's `RetroArchStatusParser`, `NetworkCommandClient` and
  `CoreMemoryReplyParser` at `751196a`.

### 4B: DualDex as an in-process library (needs DualScreenDex changes, coordinated with the DualDex thread)

Finding (2026-11-22): the companion is not additive-hostable. Its Android graph (`AndroidLoopbackServer`,
`ProductionCompanionRuntime`, `RetroArchSetupCoordinator`, the save, storage and knowledge packages) lives in `app`,
an `android.application` module, wired through `DualDexApplication` casts, with private ROM-index and save-directory
lookups. The user chose to restructure DualScreenDex after syncing with the DualDex thread.

- In DualScreenDex (branch `dualplay-host`, PR Darkaxt/DualScreenDex#37): the companion moved out of `app` into an
  Android library `companion-android` (`git mv`, same packages; `app` keeps `DualDexApplication`, `MainActivity` and
  the overlay). `DualDexCompanionHost(context, config)` builds the graph `startLoopback()` used to build; its config
  carries the transport factory, the shared-storage gateway and an optional `hostSession` (the loaded ROM as a
  `RomIndexEntry`, used ahead of the ROM index). `DualDexApplication` is a thin wrapper around it. Local build: 637
  companion-android and 56 app unit tests pass, all 672 original tests kept; parser cache revision and SQLite schema
  unchanged.
- In DualPlay: DualScreenDex is a submodule (`external/DualScreenDex`); its eight companion modules are included as
  projects with their own paths (two AGP versions cannot share one composite build, so no `includeBuild`). A small
  `gradle/libs.versions.toml` supplies the catalog entries `companion-android` references.
  `dualdex/DualDexHosting` owns one host per process with an in-process transport that calls
  `NetworkCommandServer.answer` directly (no socket), empty shared-storage roots (no ROM indexing, no RetroArch config
  found or patched) and a host session from the extracted ROM (CRC32 and SHA-256 computed in one pass).
  `dualdex/DualDexPresentation` shows DualDex's own `DualDexWebView` on the bottom display in a non-focusable window.
  The UDP listener stays for the standalone app. Cleartext is allowed only to `127.0.0.1`.
#### 4B results (2026-11-22, Thor b26–b29, evidence `evidence/stage4b-thor-*`)

- Pokemon Unbound (mGBA): the hosted DualDex shows on display 4 within 0.3 s of launch; the game keeps focus
  (`mCurrentFocus` DualPlay `GameActivity`, `mTopFocusedDisplayId=0`) and runs at 60 fps.
- DualDex's own session view (debug step `dualdex`): `connection=PLAYING`, matched by CRC32 `4B3D4957`, SHA-256
  verified, saves `/storage/emulated/0/DualPlay/saves/mgba`. First launch: DualDex builds its catalog in DualPlay's
  sandbox ("Preparing your game guide for the first time"), `ACTIVE` after about 2.5 minutes. Second launch: cache
  `HIT`, `ACTIVE` about 6 s after launch. On the title screen DualDex shows "Waiting for in-game access", its normal
  state before a save is loaded; the in-game view needs gameplay, so it is the user's hands-on check.
- On a cold start the first heartbeat runs before the ROM is attached and reports "Game discovery could not finish"
  for one 3 s interval; the next one resolves. Cosmetic.
- While DualPlay ran, its UDP listener answered about one command per second from another local client; the
  standalone DualDex app was running on the device, so this is most likely its monitor (inference, not traced).
- D-MEM-3 closed with a root cause (see the spec ledger).

- Acceptance 4B = spec Stage 4: the DualDex bottom screen is live inside DualPlay, and the standalone UDP path still
  passes its tests (`:retroarch-session:test` and the app's RetroArch-free QA tests).
- D-MEM-3 closes here: p99 read time over a 5-minute melonDS run, or a root cause.

## Open points

- DualDex supports GB, GBC and GBA ROMs; DS (melonDS) sessions report a system it does not resolve, and it shows
  nothing for them. That is expected, not a defect.
- DualDex can also write save documents (`DirectSaveDocumentResolver.replace`). Hosted, it reads DualPlay's saves
  folder with DualPlay's all-files access, so that write path is not blocked. Not exercised in 4B; revisit if a
  DualDex feature that writes saves is used.
