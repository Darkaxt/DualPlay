# Stage 4 plan: DualDex on the bottom display

Status: draft, 2026-11-22. Spec: `spec.md` R5 and Stage 4 ("DualDex bottom screen live in DualPlay; standalone UDP
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
- Bottom display: when the DualDex app is installed and the user enables "DualDex on the bottom screen", DualPlay
  launches it on the secondary display instead of showing its own placeholder Presentation. Controller focus stays
  with the game (DualDex's controller-focus contract).
- Acceptance 4A (Thor): the unmodified DualDex release identifies a running GBA game from DualPlay, shows live
  party/battle data during a run and reads the DualPlay save. Read cost per heartbeat logged.

### 4B: DualDex as an in-process library (needs DualScreenDex changes, coordinated with the DualDex thread)

- In DualScreenDex: a small additive `dualdex-host` library module that starts the existing companion runtime and
  web UI with an injected `NetworkCommandTransport`, save source and ROM source, without moving existing files (the
  DualDex thread keeps working in `app`). Landed through that repository's normal review and sync flow.
- In DualPlay: an in-process transport that calls the 4A command handler directly (no socket), the save bytes and
  ROM path passed directly, and the DualDex UI rendered in DualPlay's bottom-screen Presentation.
- Acceptance 4B = spec Stage 4: the DualDex bottom screen is live inside DualPlay, and the standalone UDP path still
  passes its tests (`:retroarch-session:test` and the app's RetroArch-free QA tests).
- D-MEM-3 closes here: p99 read time over a 5-minute melonDS run, or a root cause.

## Open points

- DualDex supports GB, GBC and GBA ROMs; DS (melonDS) sessions report a system it does not resolve, and it shows
  nothing for them. That is expected, not a defect.
- Whether DualDex needs its RetroArch setup completed before it polls the network port is checked in 4A on the
  Thor; if it does, the gap and its fix go to the DualDex thread.
