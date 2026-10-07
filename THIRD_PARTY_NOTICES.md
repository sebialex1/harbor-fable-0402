# Third-party notices

## Inter

The app UI uses the Inter typeface (https://rsms.me/inter/), bundled as
`app/src/main/res/font/inter_*.ttf` and used under the SIL Open Font License 1.1.

```
Copyright (c) 2016 The Inter Project Authors (https://github.com/rsms/inter)

This Font Software is licensed under the SIL Open Font License, Version 1.1.
This license is copied below, and is also available with a FAQ at:
http://scripts.sil.org/OFL

-----------------------------------------------------------
SIL OPEN FONT LICENSE Version 1.1 - 26 February 2007
-----------------------------------------------------------

PREAMBLE
The goals of the Open Font License (OFL) are to stimulate worldwide
development of collaborative font projects, to support the font creation
efforts of academic and linguistic communities, and to provide a free and
open framework in which fonts may be shared and improved in partnership
with others.

The OFL allows the licensed fonts to be used, studied, modified and
redistributed freely as long as they are not sold by themselves. The
fonts, including any derivative works, can be bundled, embedded,
redistributed and/or sold with any software provided that any reserved
names are not used by derivative works. The fonts and derivatives,
however, cannot be released under any other type of license. The
requirement for fonts to remain under this license does not apply
to any document created using the fonts or their derivatives.

DEFINITIONS
"Font Software" refers to the set of files released by the Copyright
Holder(s) under this license and clearly marked as such. This may
include source files, build scripts and documentation.

"Reserved Font Name" refers to any names specified as such after the
copyright statement(s).

"Original Version" refers to the collection of Font Software components as
distributed by the Copyright Holder(s).

"Modified Version" refers to any derivative made by adding to, deleting,
or substituting -- in part or in whole -- any of the components of the
Original Version, by changing formats or by porting the Font Software to a
new environment.

"Author" refers to any designer, engineer, programmer, technical
writer or other person who contributed to the Font Software.

PERMISSION AND CONDITIONS
Permission is hereby granted, free of charge, to any person obtaining
a copy of the Font Software, to use, study, copy, merge, embed, modify,
redistribute, and sell modified and unmodified copies of the Font
Software, subject to the following conditions:

1) Neither the Font Software nor any of its individual components,
in Original or Modified Versions, may be sold by itself.

2) Original or Modified Versions of the Font Software may be bundled,
redistributed and/or sold with any software, provided that each copy
contains the above copyright notice and this license. These can be
included either as stand-alone text files, human-readable headers or
in the appropriate machine-readable metadata fields within text or
binary files as long as those fields can be easily viewed by the user.

3) No Modified Version of the Font Software may use the Reserved Font
Name(s) unless explicit written permission is granted by the corresponding
Copyright Holder. This restriction only applies to the primary font name as
presented to the users.

4) The name(s) of the Copyright Holder(s) or the Author(s) of the Font
Software shall not be used to promote, endorse or advertise any
Modified Version, except to acknowledge the contribution(s) of the
Copyright Holder(s) and the Author(s) or with their explicit written
permission.

5) The Font Software, modified or unmodified, in part or in whole,
must be distributed entirely under this license, and must not be
distributed under any other license. The requirement for fonts to
remain under this license does not apply to any document created
using the Font Software.

TERMINATION
This license becomes null and void if any of the above conditions are
not met.

DISCLAIMER
THE FONT SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO ANY WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT
OF COPYRIGHT, PATENT, TRADEMARK, OR OTHER RIGHT. IN NO EVENT SHALL THE
COPYRIGHT HOLDER BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
INCLUDING ANY GENERAL, SPECIAL, INDIRECT, INCIDENTAL, OR CONSEQUENTIAL
DAMAGES, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
FROM, OUT OF THE USE OR INABILITY TO USE THE FONT SOFTWARE OR FROM
OTHER DEALINGS IN THE FONT SOFTWARE.

```

## Phosphor Icons

The app's icons (`app/src/main/java/io/harbor/fable/ui/icons/FableIcons.kt`) are path data
copied from Phosphor Icons (https://phosphoricons.com, https://github.com/phosphor-icons/core),
Regular, Fill and Bold weights, used under the MIT License.

```
MIT License

Copyright (c) 2023 Phosphor Icons

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Winlator X server (display)

The display server is Winlator's in-process Java X11 server, vendored with light changes:

- `app/src/main/java/com/winlator/{xserver,xconnector,renderer,sysvshm,widget/XServerView.java,core/{ArrayUtils,Callback,CursorLocker,StringUtils}.java,math,winhandler/MouseEventFlags.java}`
- `app/src/main/cpp/winlator/{drawable.c,xconnector_epoll.c,sysvshared_memory.c}` (built as `libwinlator.so`)
- `app/src/main/res/drawable-nodpi/winlator_cursor.png`

Source: Winlator 7.1 by BrunoSX (brunodev85), as preserved in the history of the bionic fork
https://github.com/Pipetto-crypto/winlator at commit
`11974b31982145358d14e25e6bec4174c99eb039` (2024-06-27, "Update app", author brunodev85).
At that commit, and on the fork's `winlator_bionic` branch today, the LICENSE is MIT
(Copyright (c) 2023 BrunoSX). The same text ships next to the code in
`app/src/main/java/com/winlator/LICENSE` and `app/src/main/cpp/winlator/LICENSE`.

License note: the official repository https://github.com/brunodev85/winlator (and
`brunodev85/winlator-app`) is **LGPL-2.1** since 2026-04-14 (it was MIT before that); it was
never GPL-3.0. Nothing here was taken from the post-relicense official tree, so these files are
used under MIT and Fable stays MIT. Do not merge later official Winlator changes into these
files unless you accept LGPL-2.1 for them. Termux-X11 / Lorie (GPL-3.0) code is **not** used.

Changes made for Fable (marked `Fable:` in the code): removed WinHandler, XR, GPUImage/DRI3,
Present and the input-controls dependency; MIT-SHM/DRI3/Present are not advertised; exceptions
from the epoll JNI callbacks drop the client instead of killing the app; `GLRenderer.release()`,
`Texture.invalidate()` and `DrawableManager.invalidateTextures()` so the server outlives the
display screen; `UnixSocketConfig` no longer depends on Winlator's `FileUtils`; CRLF -> LF.

```
MIT License

Copyright (c) 2023 BrunoSX

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## X11 client libraries (Termux builds)

Wine's `winex11.so` runs under Box64, which maps `libX11.so` / `libXext.so` (and the X
extension libraries) onto native aarch64 libraries. Fable bundles unmodified Termux (Android NDK,
bionic) builds under `app/src/main/assets/x11/arm64-v8a/`, downloaded from
https://packages.termux.dev/apt/termux-main/ (and https://packages.termux.dev/apt/termux-x11/
where noted):

| Library | Termux package | License |
|---|---|---|
| libX11.so | libx11 1.8.13-1 | X11/MIT-style (see `assets/x11/licenses/libx11.txt`) |
| libxcb.so | libxcb 1.17.0-1 | MIT (`libxcb.txt`) |
| libXau.so | libxau 1.0.12-2 | MIT (`libxau.txt`) |
| libXdmcp.so | libxdmcp 1.1.5-2 | MIT (`libxdmcp.txt`) |
| libXext.so | libxext 1.3.7 | MIT/X11-style (`libxext.txt`) |
| libandroid-support.so | libandroid-support 29-1 | Apache-2.0 / Termux terms (`libandroid-support.txt`) |
| libXrender.so | libxrender 0.9.12-1 | MIT/X11-style (`libxrender.txt`) |
| libXcursor.so | libxcursor 1.2.3-1 | MIT/X11-style (`libxcursor.txt`) |
| libXfixes.so | libxfixes 6.0.2 | MIT/X11-style (`libxfixes.txt`) |
| libXi.so | libxi 1.8.3 | MIT/X11-style (`libxi.txt`) |
| libXrandr.so | libxrandr 1.5.5 | MIT/X11-style (`libxrandr.txt`) |
| libXinerama.so | libxinerama 1.1.6 (termux-x11 repo) | MIT/X11-style (`libxinerama.txt`) |
| libXcomposite.so | libxcomposite 0.4.7 (termux-x11 repo) | MIT/X11-style (`libxcomposite.txt`) |

### FreeType and its dependencies (Termux builds)

Wine's font code (`win32u` / `gdi32`, through Box64's wrapped `libfreetype.so.6`) needs a full
FreeType. Android's `/system/lib64/libft2.so` is too stripped down (Wine: "upgrade FreeType to at
least version 2.1.4", then `kernel32.dll` status c0000135), so Fable bundles unmodified Termux
aarch64 builds next to the X11 libraries. Where Box64 / the DT_NEEDED entries use a versioned name
the same file ships under both names.

| Library (file names) | Termux package | License |
|---|---|---|
| libfreetype.so.6, libfreetype.so | freetype 2.14.3 | FreeType License (FTL) or GPL-2.0, at the user's choice; Fable uses it under the FTL (`freetype.txt`). Portions of this software are copyright (C) The FreeType Project (www.freetype.org). All rights reserved. |
| libpng16.so, libpng.so | libpng 1.6.59 | libpng license (`libpng.txt`) |
| libz.so.1, libz.so | zlib 1.3.2 | zlib license (`zlib.txt`) |
| libbz2.so.1.0, libbz2.so | libbz2 1.0.8-8 | bzip2 license (BSD-style, `libbz2.txt`) |
| libbrotlidec.so, libbrotlicommon.so | brotli 1.2.0 | MIT (`brotli.txt`) |

### Fontconfig and its dependencies (Termux builds)

Wine's `win32u` loads `libfontconfig.so.1` through Box64's wrapped Fontconfig; without a native
aarch64 build Box64 reports "Error initializing native libfontconfig.so" and `kernel32.dll` fails
to load (status c0000135). Fontconfig needs FreeType (above) and expat.

| Library (file names) | Termux package | License |
|---|---|---|
| libfontconfig.so, libfontconfig.so.1 | fontconfig 2.18.3 | MIT/X11-style (`fontconfig.txt`) |
| libexpat.so.1, libexpat.so | libexpat 2.9.0 | MIT (`libexpat.txt`) |

### GnuTLS and its dependencies (Termux builds)

Wine's `secur32` / `bcrypt` load `libgnutls.so.30` through Box64; Android has no GnuTLS, so
Fable bundles it with its full DT_NEEDED closure. The libraries are shipped unmodified as separate
shared objects. The LGPL / GPL texts are in `assets/x11/licenses/` (`LGPL-2.1.txt`,
`LGPL-3.0.txt`, `GPL-2.0.txt`, `GPL-3.0.txt`); the dual-licensed GNU libraries are used under
the LGPL version 3 or later. The LGPL license files name the upstream source tarball of each
library; the Termux build recipes are at https://github.com/termux/termux-packages.

| Library (file names) | Termux package | License |
|---|---|---|
| libgnutls.so, libgnutls.so.30 | libgnutls 3.8.13-1 | LGPL-2.1-or-later (`gnutls.txt`) |
| libnettle.so.8, libnettle.so, libhogweed.so.6, libhogweed.so | libnettle 4.0+really3.10.2 (nettle 3.10.2) | LGPL-3.0-or-later or GPL-2.0-or-later (`nettle.txt`) |
| libgmp.so | libgmp 6.3.0-2 | LGPL-3.0-or-later or GPL-2.0-or-later (`libgmp.txt`) |
| libtasn1.so | libtasn1 4.21.0 | LGPL-2.1-or-later (`libtasn1.txt`) |
| libidn2.so | libidn2 2.3.8-1 | LGPL-3.0-or-later or GPL-2.0-or-later, Unicode data license (`libidn2.txt`) |
| libunistring.so | libunistring 1.4.2 | LGPL-3.0-or-later or GPL-2.0-or-later (`libunistring.txt`) |
| libiconv.so | libiconv 1.19 | LGPL-2.1-or-later (`libiconv.txt`) |
| libp11-kit.so | p11-kit 0.26.5 | BSD-3-Clause (`p11-kit.txt`) |
| libffi.so | libffi 3.8.0 | MIT (`libffi.txt`) |
| libzstd.so.1, libzstd.so | zstd 1.5.7-1 | BSD-3-Clause or GPL-2.0; used under BSD (`zstd.txt`) |

### SDL2 and its dependencies (Termux builds)

Wine's `winebus.sys` loads `libSDL2-2.0.so.0` through Box64 for joysticks / game controllers.
Fable bundles the SDL2 build from the termux-x11 repository (https://packages.termux.dev/apt/termux-x11/)
and the DT_NEEDED entries not already covered by the libraries above (termux-main unless noted).

| Library (file names) | Termux package | License |
|---|---|---|
| libSDL2-2.0.so.0, libSDL2.so | sdl2 2.32.10 (termux-x11 repo) | zlib (`sdl2.txt`) |
| libXss.so | libxss 1.2.5 | MIT/X11-style (`libxss.txt`) |
| libwayland-client.so, libwayland-cursor.so, libwayland-egl.so | libwayland 1.26.0 | MIT (`libwayland.txt`) |
| libxkbcommon.so | libxkbcommon 1.13.2 (termux-x11 repo) | MIT and MIT-style (`libxkbcommon.txt`) |
| libdecor-0.so | libdecor 0.2.5 (termux-x11 repo) | MIT (`libdecor.txt`) |

The full license texts ship in the APK under `assets/x11/licenses/`. At install time Fable
patches one string in its copy of libxcb (the compiled-in socket directory
`/data/data/com.termux/files/usr/tmp/.X11-unix/X` -> `<filesDir>/.X11-unix/X`); no other change.
