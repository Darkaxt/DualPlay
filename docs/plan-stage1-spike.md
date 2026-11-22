# Stage 1 plan — spike

Spec: `docs/spec.md` (Stage 1 row). Deferrals D-MEM-1, D-CORE-1 apply.

1. **Fork memory API** (`Darkaxt/LibretroDroid`, branch `dualplay`)
   - `memorymap.{h,cpp}`: copy descriptors from `RETRO_ENVIRONMENT_SET_MEMORY_MAPS`; linear translation
     of a bus address to a pointer, reads spanning descriptors; fallback to SYSTEM_RAM at address 0.
   - `LibretroDroid::readMemory` under `coreLock` (so never during `retro_run`); frame counter
     incremented after each `retro_run`.
   - JNI: `readMemory(long address, byte[] dest, int offset, int length): int`, `getMemoryRegions(): long[]`
     (start,len,flags triples), `getFrameCount(): long`. Clear map on destroy.
2. **DualPlay spike app** (`app`, `dev.darkaxt.dualplay`)
   - Includes the fork's `libretrodroid` module from the `runtime/LibretroDroid` submodule.
   - `SpikeActivity`: `GLRetroView` fullscreen on the top display; mGBA from `jniLibs`; ROM path from an
     intent extra (`rom`), copied into app storage for the spike.
   - `DualDexPlaceholder` `Presentation` on the first non-default display: memory map list, frame number,
     hex dump of a configurable region (default EWRAM `0x02000000`, 256 bytes), changed-byte count,
     read latency (avg/max µs).
   - Controller key events forwarded to the core; the presentation is not focusable.
3. **Build** via gradle-build-gate (`:app:assembleDebug`).
4. **Thor run** via android-device route (after ownership check and user authorization): install,
   push a test ROM, launch, capture both displays, bounded run, collect logcat, stop the app.
5. **Audit** against the Stage 1 acceptance row; record results in `docs/stage1-results.md`.
