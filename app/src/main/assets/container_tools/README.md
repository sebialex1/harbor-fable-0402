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
| Direct3D 11 Test  | `d3d11-test.exe`     | Placeholder: drop the binary here         |
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
