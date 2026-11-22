# Stage 2 plan: runtime

Spec: `docs/spec.md` (R1, R2, Stage 2 row). Ordered by risk and dependency. The user delegated the ordering on
2026-11-22. Each batch ends with a gated build, a device or emulator check where relevant, a results entry in
`docs/stage2-results.md`, and a commit and push of both repos.

## Batch A: runtime correctness + multi-core + DS
1. **D-MEM-2**: move frame pacing (`fpsSync->wait()`) out of the core lock in `step()`.
   - The probe reports a p50/p99/max histogram.
   - Accept: on the Thor, read p99 < 1 ms, and max not tied to the frame period.
2. **D-MEM-1**: port RetroArch's memory-descriptor preprocessing (`mmap_preprocess_descriptors`, reduce/inflate,
   select/disconnect) into `MemoryMap`, plus a host C++ unit test (run through pipeline-gate).
   - Accept: the tests cover linear ranges, select-only mirrors and disconnect bits. mGBA reads are unchanged on the device.
3. **Core registry**: per-system core selection.
   - `.gba` → mGBA; `.gb/.gbc` → Gambatte; `.nds` → melonDS DS.
   - Pinned `.so` with SHA-256 in `cores/README.md`.
   - The spike activity picks the core from the ROM extension.
4. **DS on two displays**:
   - The runtime can present a sub-rectangle of the core framebuffer on a second `Surface`: an extra EGL window
     surface on the same context, drawn on the GL thread each frame.
   - For DS, the top screen goes on the top display and the bottom screen on the bottom display. Touch on the bottom
     display maps to the core's touch input.
   - Accept: a DS ROM boots with both screens split across the Thor displays and touch works. Fps is recorded.
5. **melonDS HW-render probe**:
   - Run melonDS DS with renderer=OpenGL and record whether it falls back to software on GLES.
   - If it falls back, open sub-stage 2-GL: a melonDS DS fork (`Darkaxt/melonds-ds`, a public fork) that ports the
     GL renderer to GLES 3.1+, built from source.
   - Accept for 2-GL: the GL renderer runs on the Thor at ≥2x internal resolution at full speed.

## Batch B: rewind + run-ahead (fork)
6. Rewind:
   - Per-frame (configurable interval) state capture into a byte-budgeted ring.
   - States are stored as XOR delta to the previous state, compressed with zlib level 1.
   - API: `setRewindEnabled(budgetBytes, interval)`, `rewindStep()`.
   - Accept: holding rewind on the Thor walks back ≥10 s on GBA at full speed.
7. Run-ahead (single-instance RetroArch algorithm):
   - Each frame: run N frames with video/audio suppressed, serialize, show the last frame, unserialize.
   - API: `setRunAhead(frames)`.
   - Accept: 1–2 frames on mGBA/Gambatte at full speed on the Thor, with the state round-trip measured.

## Batch C: achievements
8. rcheevos `rc_client` in the fork:
   - Hashing, memory peek through `MemoryMap`, per-frame `rc_client_do_frame`, an HTTP callback to Kotlin.
   - Accept (no account needed): game identification by hash against the RetroAchievements API for a known ROM.
   - Login/unlock acceptance needs the user's RetroAchievements credentials. This is deferral D-RA-1 (Stage 3
     settings UI provides login), not a blocker for Batch C.

## Batch D: shaders
9. Verify librashader for Android:
   - Cross-build `aarch64-linux-android` via cargo-ndk through rust-build-gate.
   - Load a slang preset on GLES 3 on the Thor.
   - If it works: integrate as the shader path (presets in app storage).
   - If not: record the evidence and keep the existing GLSL path plus a curated GLSL preset set (decision recorded in the spec).

## Batch E: D-CORE-1
10. GitHub Actions workflow in DualPlay that builds the pinned cores (mGBA, Gambatte, melonDS DS fork) for arm64
    from pinned commits and publishes them as workflow artifacts / release assets with SHA-256.
    - Accept: the app uses CI-built cores, and the hashes are recorded.
