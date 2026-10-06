# DownloadService & Asset Repository Notes

## What was added

### `DownloadService.kt`
Foreground service that keeps a `dataSync` notification alive while
`DownloadManager` has queued or active transfers.

- Registered in `AndroidManifest.xml` as `.data.DownloadService` with
  `foregroundServiceType="dataSync"`.
- `ACTION_PROCESS = "io.harbor.fable.action.PROCESS_DOWNLOADS"` — the intent
  action that `DownloadManager.startServiceOrKick()` sends.
- `ensureChannel(context)` — creates the `fable_downloads` notification channel
  at `IMPORTANCE_LOW`. Idempotent via `AtomicBoolean`.
- `onStartCommand` enters the foreground immediately, calls
  `DownloadManager.kick()`, then observes `DownloadManager.snapshot`. When
  `DownloadSnapshot.hasActiveWork` becomes false, it calls
  `stopForeground(STOP_FOREGROUND_REMOVE)` and `stopSelf()`.
- Notification body shows the current download name and percentage, or a
  queued count via `R.plurals.download_notification_queued`.
- Returns `START_NOT_STICKY` — a killed service is not restarted; the next
  `enqueue()` call will start it again.

### `AssetRepository.kt`
In-memory asset catalog with search, type filter, local import, and download
status tracking.

- **Catalog** — seeded from `defaultCatalog` (see below). Sources can be added
  or removed at runtime via `addCatalogSource` / `removeCatalogSource`.
  Persisted to `filesDir/assets/catalog.json`.
- **Refresh** — `refresh(forceRefresh)` fetches `releases/latest` for every
  catalog source via `GitHubReleaseFetcher.fetchLatestOrCached()`, filters
  assets with `filterAssets()`, and builds `AssetEntry` / `DriverPackage`
  records. Per-source failures are recorded in `refreshErrors` StateFlow
  rather than aborting the whole refresh.
- **Search** — `search(query)` does case-insensitive substring matching across
  name, version, and source repo.
- **Type filter** — `filterByType(AssetType)` returns entries of one type.
- **Local import** — `importLocal(name, type, uri)` creates an
  `AssetSource.LOCAL_IMPORT` entry with the content `Uri` as `localPath`.
  The file is not copied.
- **Download** — `download(assetId)` enqueues a transfer via `DownloadManager`.
  Destination: `filesDir/assets/downloads/{repo}/{name}`.
- **Reconciliation** — `reconcileDownloadStatus` checks the destination file and
  `DownloadManager.taskForAsset()` to set `isDownloaded` and `localPath` on
  every access.

### `RadvReleaseProvider.kt` + `DriverRepository.kt`
The RADV Xclipse Vulkan driver is **not** part of the asset catalog. It is a
Mesa RADV build for Samsung Xclipse (RDNA2) GPUs that is loaded through the
adrenotools namespace loader, but it is not a Turnip/Adreno driver and the app
offers no Turnip packages.

- `RadvReleaseProvider.fetch()` reads *every* release of `JimVulkan/radv-xclipse`
  (`GET /repos/{owner}/{repo}/releases`, shared transport and cache in
  `GitHubReleaseFetcher.fetchReleases`), picks the driver zip of each release,
  parses the Mesa version and commit, sorts newest first and categorizes:
  `LATEST` (newest stable), `VERSIONED` (older stable), `PRERELEASE`.
- `DriverRepository` owns `releases`, the downloaded zips and the single
  `installed` driver (`InstalledDriver`, persisted as `drivers/active.json`).
  Layout under `filesDir/drivers/`: `packages/<asset>.zip` (one per version),
  `active/<tag>/` (the extracted driver), `active.json`.
- Download tasks use `RecordKind.DRIVER` and `assetId = "radv-xclipse/<tag>"`.

### `FableApp.kt`
Wired up as the application entry point.

- `containerRepository` — `ContainerRepository.get(this)`
- `assetRepository` — `AssetRepository.get(this)`
- `gitHubReleaseFetcher` — `GitHubReleaseFetcher.get(this)`
- `downloadManager` — `DownloadManager.get(this)`
- `FableApp.from(context)` — casts `context.applicationContext` to `FableApp`.
  Screens use this to reach the repositories.

### Default catalog
| Owner | Repo | Type | Globs |
|---|---|---|---|
| Kron4ek | Wine-Builds | WINE | `*amd64*.tar.xz`, `*amd64*.tar.gz` |
| ptitSeb | box64 | BOX64 | `*aarch64*.tar.gz`, `*android*.tar.gz` |
| doitsujin | dxvk | DXVK | `regex:^dxvk-[0-9][0-9.]*\.tar\.(gz\|zst)$` (not the `dxvk-native-*` Linux builds) |
| GGlessT | modern-treex | OTHER | `*.zip`, `*.tar.gz` |

The RADV Xclipse driver has its own feed (`RadvReleaseProvider`); a persisted
catalog from an older build that still lists a `VULKAN_DRIVER` source is
migrated on load.

`GGlessT/modern-treex` is included as requested. The GitHub API may return a
client error (404) for repos with no releases; this is recorded in
`refreshErrors` rather than crashing the refresh.

### String resources added
- `download_notification_title` — "Fable Downloads"
- `download_notification_preparing` — "Preparing download…"
- `download_channel_description` — (channel description text)
- `plurals:download_notification_queued` — "%d download queued" / "%d downloads queued"

## Build verification
The data layer, launch flow, native code and UI build with
`./gradlew assembleDebug --no-daemon` (SDK 34, NDK 27.0.12077973, CMake 3.22.1), the same
command the CI workflow runs. Nothing has been run on a device yet, so the launch flow, the
catalog refresh and the animations are verified by compilation, host-side tests of the
archive extractor and native launcher, and rendered previews only.

## Launch flow (Box64 + Wine)

Windows programs are x86_64, so on an ARM64 device Wine runs through Box64:
`box64 wine <program>`. `ContainerRepository.launch()` / `launchDesktop()`:

1. Look for a downloaded Box64 package (`assets/downloads/box64/`) and a downloaded Wine
   build (`assets/downloads/Wine-Builds/`). Missing pieces give a
   `LaunchResult.Failed` such as "Install Box64 and Wine from the Assets tab first".
2. Unpack Box64 once into `filesDir/runtime/box64/` (an archive containing a `box64`
   executable, or the bare executable).
3. Unpack the Wine build into the container directory (`ContainerRepository.installWine`).
   The first launch does this; `.fable-wine.json` marks a finished extraction. Headers, man
   pages and static libraries are skipped.
4. Call `NativeLoader.launchWineContainer`, which forks and execs
   `box64 <container>/bin/wine <program> [args]`. The desktop launch runs
   `explorer /desktop=Fable,<resolution>`. Output goes to `<container>/fable-launch.log`.

`ptitSeb/box64` does not publish a ready-made ARM64 binary in its GitHub releases (only
x86 library bundles), so the Box64 entry stays empty until a release that matches its globs
appears or the globs are pointed at another source.

## First-run setup (`SetupManager`)

`SetupManager.installRecommended()` refreshes the catalog and queues the latest Wine (amd64),
Box64, RADV Xclipse and DXVK packages on `DownloadManager`. Progress shows in the Assets list
and the download notification.

- Wine pick: stable `wine-X-amd64-wow64` first (runs 32-bit programs without Box86), then
  stable `wine-X-amd64`, then the rest. All amd64 variants stay selectable in the Assets list.
- `SetupManager.state` reports each kind as INSTALLED / DOWNLOADING / AVAILABLE / UNAVAILABLE.
  "Installed" means a file is on disk (`AssetRepository.downloadedFiles`), so it works offline.
- The Assets screen shows a "Get Started" / "Finish Setup" banner with "Download All" while
  anything recommended is still missing, and a "Download recommended" top-bar action.
- The driver pick is the `LATEST` RADV Xclipse release from `DriverRepository`; its zip goes to
  `filesDir/drivers/packages/`.
