# Pinned native libraries

Every `.so` in `app/src/main/jniLibs/arm64-v8a/` is built by the `native-libs` GitHub Actions workflow
(`.github/workflows/native-libs.yml`). Each comes from a pinned source commit using the libretro buildbot recipe
for that core (ci-templates `android-cmake` / `android-jni`). The workflow uses NDK 27.2.12479018 and strips its
output. It publishes the files with `SHA256SUMS` and `BUILDINFO` in the `native-libs-arm64-v8a` artifact.

Current files: run [45021711773](https://github.com/Darkaxt/DualPlay/actions/runs/45021711773), 2026-11-22.

| File | Source | Commit | SHA-256 |
| --- | --- | --- | --- |
| `libmgba_libretro_android.so` | mgba-emu/mgba (`-DLIBMGBA_ONLY=ON -DBUILD_LIBRETRO=ON`, API 21) | `7a12d6d4b9acb14c0ae62c9166b6a2f3d08007f6` | `9cf23fdde01f75bd4f5931f9554184df6e0e05c0915a42b533888b712df81179` |
| `libgambatte_libretro_android.so` | libretro/gambatte-libretro (ndk-build `libgambatte/libretro/jni`) | `138bcbbf311300a271896bf93a76370c72cab339` | `5ae370034cf6e258c6be38dd79e262ed29fdf617f85511262c79dbbe0021b3ff` |
| `libmelondsds_libretro_android.so` | JesseTG/melonds-ds tag `v1.4.0` (API 24) | `f394adbacb5722ee97c1b37c8064da9a25818310` | `5e7a09a39cb1c8333917239f7d139437178c6720daed3395c2a8ecb1496a19c5` |
| `librashader.so` | SnowflakePowered/librashader 0.12.1, `librashader-capi --no-default-features --features runtime-opengl`, Rust 1.94.0, `--locked` | `9d0116f86aadb866b0275b19a9fe48ee29b165e7` | `6cfd4977f0ccc55297a8f826cdba7f8e799dba7a8a5133141933d16bf8884690` |
| `libc++_shared.so` | NDK 27.2.12479018 sysroot (needed by `librashader.so`) | - | `9ee5e2dd1e2b1530257367d49bfbaecfa6d3f79e2f3b67ac956347f58ab000a6` |

To change a pin:
1. Edit the commit in the workflow `env`.
2. Run the workflow.
3. Copy the artifact's files here.
4. Update this table.

Licences and sources:
- mGBA: MPL-2.0.
- Gambatte: GPL-2.0.
- melonDS DS: GPL-3.0.
- librashader: MPL-2.0 for the implementation, MIT for the C headers. The runtime loads it with `dlopen`, so
  DualPlay works without it.
- libc++: Apache-2.0 with LLVM exceptions.

GB/GBC core choice (R2.1, 2026-11-22): Gambatte. It publishes a libretro memory map and is the common
RetroArch GB/GBC core. The SameBoy nightly was only 307 KB at download time and was not adopted.

Until 2026-11-22 the cores were buildbot nightlies, pinned by hash. The CI builds replace them (D-CORE-1).
