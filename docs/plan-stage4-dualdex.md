# Stage 4 plan: DualDex on the bottom display

Status: 4A done 2026-11-22; 4B next. Spec: `spec.md` R5 and Stage 4 ("DualDex bottom screen live in DualPlay; standalone UDP
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
  would steal controller input from the game, and it opened on its unconnected start screen. DualDex only polls
  after its own "Connect RetroArch" mode is chosen in its UI, so the standalone pairing is set up by the user in
  DualDex. In-process hosting (4B) replaces it.
- Acceptance 4A: on the Thor, the server answers DualDex's commands over real UDP from 127.0.0.1, in formats its
  parsers accept (verified, see below). The end-to-end check with the unmodified DualDex app needs the user to pick
  "Connect RetroArch" in DualDex, so it is a hands-on check.

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
- DualDex polls only after its "Connect RetroArch" mode is chosen in its own UI (checked in 4A). That is DualDex
  behaviour, not a gap; 4B does not depend on it.
- DualDex can also write save documents (`DirectSaveDocumentResolver.replace`). 4B passes saves read-only unless a
  later stage decides otherwise.
