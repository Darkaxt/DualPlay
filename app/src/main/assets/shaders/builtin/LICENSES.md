# Built-in shader presets

Shader sources are copied unchanged from libretro/slang-shaders at commit `02bf134`
(https://github.com/libretro/slang-shaders). Each file keeps its original header. The `.slangp`
files are DualPlay's own single-pass presets. Every licence below is compatible with GPL-3.0.

| Preset | Files | Licence | Author (from the file header) | Upstream path |
| --- | --- | --- | --- | --- |
| lcd-sameboy | `sameboy-lcd.slang` | MIT | Lior Halphon (LIJI32) | `handheld/shaders/sameboy-lcd.slang` |
| lcd-3x | `lcd3x.slang` | Public domain | Gigaherz | `handheld/shaders/lcd3x.slang` |
| lcd-dot | `dot.slang` | Public domain | Themaister | `handheld/shaders/dot.slang` |
| lcd-zfast | `zfast_lcd.slang` | GPL-2.0-or-later | Greg Hogan (SoltanGris42) | `handheld/shaders/zfast_lcd.slang` |
| color-gba | `gba-color.slang` | Public domain | hunterk, Pokefan531 | `handheld/shaders/color/gba-color.slang` |
| color-gbc | `gbc-color.slang` | Public domain | hunterk, Pokefan531 | `handheld/shaders/color/gbc-color.slang` |
| color-nds | `nds-color.slang` | Public domain | hunterk, Pokefan531 | `handheld/shaders/color/nds-color.slang` |
| crt-lottes-fast | `crt-lottes-fast.slang` | Public domain | Timothy Lottes; adapted by hunterk | `crt/shaders/crt-lottes-fast.slang` |
| crt-geom | `crt-geom.slang` | GPL-2.0-or-later | cgwg, Themaister, DOLLS | `crt/shaders/crt-geom.slang` |
| crt-pi | `crt-pi.slang` | GPL-2.0-or-later | davej | `crt/shaders/crt-pi.slang` |
| crt-zfast | `zfast_crt_finemask.slang`, `zfast_crt_impl.inc` | GPL-2.0-or-later | Greg Hogan (SoltanGris42) | `crt/shaders/zfast_crt/zfast_crt_finemask.slang, crt/shaders/zfast_crt/zfast_crt_impl.inc` |
