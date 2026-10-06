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

Wine's `winex11.so` runs under Box64, which maps `libX11.so` / `libXext.so` onto native
aarch64 libraries. Fable bundles unmodified Termux (Android NDK, bionic) builds under
`app/src/main/assets/x11/arm64-v8a/`, downloaded from https://packages.termux.dev/apt/termux-main/:

| Library | Termux package | License |
|---|---|---|
| libX11.so | libx11 1.8.13-1 | X11/MIT-style (see `assets/x11/licenses/libx11.txt`) |
| libxcb.so | libxcb 1.17.0-1 | MIT (`libxcb.txt`) |
| libXau.so | libxau 1.0.12-2 | MIT (`libxau.txt`) |
| libXdmcp.so | libxdmcp 1.1.5-2 | MIT (`libxdmcp.txt`) |
| libXext.so | libxext 1.3.7 | MIT/X11-style (`libxext.txt`) |
| libandroid-support.so | libandroid-support 29-1 | Apache-2.0 / Termux terms (`libandroid-support.txt`) |

The full license texts ship in the APK under `assets/x11/licenses/`. At install time Fable
patches one string in its copy of libxcb (the compiled-in socket directory
`/data/data/com.termux/files/usr/tmp/.X11-unix/X` -> `<filesDir>/.X11-unix/X`); no other change.
