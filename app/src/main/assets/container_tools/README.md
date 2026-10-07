# Container tools (bundled Windows test programs)

Every Fable container gets a **Tools** list (container screen) with these shortcuts. They run
from `C:\fable\tools` inside the prefix (`<container>/drive_c/fable/tools`). On every launch
`ContainerTools.install` copies the files listed below from this folder into the prefix
(refreshed after an app update). See `app/src/main/java/io/harbor/fable/data/ContainerTools.kt`.

| Shortcut          | File in this folder  | Status in this repo                       |
|-------------------|----------------------|-------------------------------------------|
| GPU Info          | (none)               | `gpu-info.bat` is written by Fable        |
| GPU Info (helper) | `VulkanGpuInfo.exe`  | Optional, not bundled                     |
| GPU Info (helper) | `vulkaninfo.exe`     | Optional, not bundled                     |
| Direct3D 9 Test   | `d3d9-test.exe`      | Placeholder: drop the binary here         |
| Direct3D 11 Test  | `d3d11-test.exe`     | Built from source by the release workflow |
| Direct3D 12 Test  | `d3d12-test.exe`     | Placeholder: drop the binary here         |

File names are matched case-insensitively. A shortcut whose binary isn't here still shows up
("Not included in this build") and launching it says so instead of starting Wine.

## What the binaries should be

All of them must be **x86_64 (or x86) Windows PE executables** with no installer and no
dependencies beyond what Wine and the prefix provide (statically linked CRT, or the UCRT that
Wine ships). They run inside the container's virtual desktop through Box64.

- `VulkanGpuInfo.exe` — a windowed GPU info viewer (Winlator ships one in the same role). When
  present, `gpu-info.bat` runs it instead of writing a text report. It talks to Wine's
  `vulkan-1.dll` (winevulkan), so it shows the device the active Vulkan driver exposes.
- `vulkaninfo.exe` — Khronos Vulkan-Tools `vulkaninfo` for Windows (Apache-2.0,
  <https://github.com/KhronosGroup/Vulkan-Tools>). `gpu-info.bat` adds `vulkaninfo --summary` to
  its report when it is present.
- `d3d9-test.exe`, `d3d11-test.exe`, `d3d12-test.exe` — small windowed programs that create a
  device and render something animated (a spinning cube or triangle) so a working DXVK
  (`d3d9.dll`, `d3d11.dll`, `dxgi.dll`) or VKD3D-Proton (`d3d12.dll`) is visible at a glance.
  Winlator's own D3D test programs fill this role; MIT-licensed alternatives that can be built
  for x64 are Microsoft's `DirectX-Graphics-Samples` (`D3D12HelloTriangle`) and
  `walbourn/directx-sdk-samples` (Direct3D 11 tutorials) / `directx-sdk-legacy-samples`
  (Direct3D 9).

## `d3d11-test.exe` is built from source

The Direct3D 11 test is Fable's own: `container_tools/d3d11-test/src/main.cpp` at the repo root,
plain Win32 + Direct3D 11. It opens a 640x480 window with a spinning, colour-cycling triangle
on a dark grey background; the title bar shows the adapter Direct3D reports (the Vulkan device
under DXVK), the feature level and the frame rate. It imports only `d3d11.dll`, `kernel32`,
`user32` and the C runtime (libgcc/libstdc++ are linked statically) and compiles its shaders
at start-up with Wine's `d3dcompiler_47.dll`; an error box names the step that failed.

The release workflow cross-compiles it with MinGW-w64 and copies it here before
`assembleDebug`, so it isn't checked in. For a local build:

```bash
sudo apt install g++-mingw-w64-x86-64
make -C container_tools/d3d11-test
cp container_tools/d3d11-test/build/d3d11-test.exe app/src/main/assets/container_tools/
```

Before bundling a binary, check that its license allows redistribution and add it to
`THIRD_PARTY_NOTICES.md`.

## Without any binaries

`gpu-info.bat` already works: it writes `C:\fable\tools\gpu-info.txt` with

1. `ver`,
2. `wmic path Win32_VideoController` (Wine's WMI asks DXGI, i.e. DXVK's `dxgi.dll` when DXVK is
   installed, so this is the Vulkan adapter name and memory),
3. `vulkaninfo --summary` when bundled,
4. Wine's `dxdiag /t` report (Direct3D 9 adapter, i.e. DXVK's `d3d9.dll`),

and opens it in Notepad.
