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
- **Download** — `download(assetId)` and `downloadDriver(driverId)` enqueue a
  transfer via `DownloadManager`. Destination directories:
  - Assets: `filesDir/assets/downloads/{repo}/{name}`
  - Drivers: `filesDir/drivers/{repo}/{name}`
- **Reconciliation** — `reconcileDownloadStatus` / `reconcileDriverStatus`
  check the destination file and `DownloadManager.taskForAsset()` to set
  `isDownloaded` and `localPath` on every access.

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
| doitsujin | dxvk | DXVK | `*.tar.gz`, `*.tar.zst` |
| JimVulkan | radv-xclipse | VULKAN_DRIVER | `*.apk`, `*.zip` |
| GGlessT | modern-treex | OTHER | `*.zip`, `*.tar.gz` |

`GGlessT/modern-treex` is included as requested. The GitHub API may return a
client error (404) for repos with no releases; this is recorded in
`refreshErrors` rather than crashing the refresh.

### String resources added
- `download_notification_title` — "Fable Downloads"
- `download_notification_preparing` — "Preparing download…"
- `download_channel_description` — (channel description text)
- `plurals:download_notification_queued` — "%d download queued" / "%d downloads queued"

## Not compiled here
No Android SDK in this environment. The code is statically verified against
the existing data layer API surface (method signatures, field names, import
paths) but was not Gradle-compiled.

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
