# harbor-fable

A minimal Android Wine container manager with a dark, premium glass UI.

## Features

- **Containers** — create Wine containers, add Windows apps, launch an app or the Wine desktop
- **Wine through Box64** — Kron4ek's x86_64 Wine builds run on ARM64 via Box64
- **Custom Vulkan driver** — RADV Xclipse (Mesa) for Samsung Xclipse GPUs, every release from JimVulkan/radv-xclipse, one active driver at a time
- **One-tap setup** — download the recommended Wine, Box64, driver and DXVK from the Assets tab
- **Asset downloads** — Wine builds, DXVK and drivers from GitHub releases, with resume and checksum checks
- **Glass design** — charcoal glass cards with hairline edges, Inter typography, animated transitions, floating dock

## Architecture

```
app/src/main/
├── java/io/harbor/fable/
│   ├── app/           — Application class
│   ├── data/          — Repositories, models, download manager
│   ├── nativebridge/  — JNI bridge (Wine launcher, driver loader)
│   └── ui/            — Compose UI (glass theme, components, screens)
├── cpp/               — Native C++ (Wine/Box64 launcher, driver loader)
└── res/               — Resources
```

## Building

Requires Android SDK 34, NDK r26+, and CMake 3.22.1.

```bash
./gradlew assembleDebug
```

## License

MIT
