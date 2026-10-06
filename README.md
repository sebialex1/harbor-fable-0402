# harbor-fable

A minimal Android Wine container manager with refraction glass UI.

## Features

- **Container management** — create and manage Wine environments
- **Adrenotools driver support** — load custom Vulkan driver zips (RADV Xclipse, Turnip, etc.)
- **Asset downloads** — fetch Wine builds, DXVK, VKD3D, Proton from GitHub releases
- **Refraction glass design** — frosted translucent surfaces, animated transitions, custom dock
- **Minimal** — no bloat, focused on the essentials

## Architecture

```
app/src/main/
├── java/io/harbor/fable/
│   ├── app/           — Application class
│   ├── data/          — Repositories, models, download manager
│   ├── nativebridge/  — JNI bridge to adrenotools
│   └── ui/            — Compose UI (glass theme, components, screens)
├── cpp/               — Native C++ (adrenotools bridge, driver loader)
└── res/               — Resources
```

## Building

Requires Android SDK 34, NDK r26+, and CMake 3.22.1.

```bash
./gradlew assembleDebug
```

## License

MIT
