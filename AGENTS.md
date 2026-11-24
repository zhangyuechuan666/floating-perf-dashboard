# Resource Overlay — PICO Spatial floating resource monitor

## What it does
Two windows:
- **Compact window (default, launcher)** — `DefaultWindowContainer`, id `ResourceOverlayCompact`.
  A 72 dp pill-shaped glass strip with one fixed-width cell per metric (dot + caption on top,
  value below): CPU · GPU · NET (↓/↑, compact K/s M/s) · TEMP (battery °C) · BATTERY (battery life only: `3h 12m left`, charging `0h 45m to full`,
  `Estimating…` until 10 s of readings exist; watts are only in Details → Power) · REFRESH. Each cell is as wide as
  its widest possible value (an invisible template string, `MetricCell(widest = …)`) and cells are
  spread with `Arrangement.SpaceBetween`, so gaps are equal and changing values never move other
  metrics; BATTERY's width changes only on plug-in or unplug; unavailable
  values show a dimmed `—`; `+` before the power value = charging. The whole
  strip is one button (`Open details`) that opens the details window. PICO enforces a
  320x180 dp minimum planar window, so the window is 780x180 dp with **no window glass**
  (manifest `materialbackground=0`, `resizetype=2`, auto-hide caption bar) and the strip draws
  its own clipped `backgroundMaterial`, vertically centred; the rest is transparent.
- **Details window** — `WindowContainer` id `ResourceOverlayDetails`, opened with
  `LocalSpatialNavigator.current.openWindowContainer(id, tag = "details")` (tag ⇒ reused, not
  stacked), placed with `Placement.top(compact, offset = 12.dp)`. Windows Task Manager
  "Performance"-style page, 880x500 dp default, min 760x460 via `windowConstraints`:
  - Left: SpatialUI `SideNavigation` (CPU / GPU / Network / Temperature / Power / Display) with
    live mini graphs + summaries.
  - Right: 60 s graph with scrolling 10x10 grid, filled area, axis labels, and stats
    (Utilization, Speed, Max speed, Cores, Up time; Send/Receive/totals) in `StatsBlock`, which
    lays items out in 5 equal fixed columns (wrapping to a new row), `—` when unavailable. The graph is lifted
    toward the user with `Modifier.offset(z = 24.dp)`.

Both windows share one process-wide sampler (`MonitorHub` in `MonitorState.kt`) that runs only
while at least one window is composed, so numbers and history match across windows.

Data sources (refreshed every second, see `metrics/SystemStatsSampler.kt`):
- Network: `android.net.TrafficStats` deltas (device-wide).
- CPU: `/proc/stat`; if SELinux denies it (true on the emulator), average per-core
  `scaling_cur_freq / cpuinfo_max_freq` labelled "Frequency estimate". Clock speeds below
  100 MHz (emulator placeholders) show as "—".
- GPU: Adreno kgsl sysfs (`gpu_busy_percentage`, `gpubusy`), then generic/Mali nodes; "N/A" when
  none is readable. The emulator has no kgsl/Mali/devfreq nodes at all (`ro.hardware.egl=emulation`,
  it renders on the host GPU), so GPU is always `—` there. On the headset (Adreno) only `gpubusy`
  is readable by the app (others SELinux-denied); it reads `0 0` while the headset is asleep,
  which counts as 0 %, and the reader re-probes until a source is readable.
- Temperature (`metrics/PowerThermalReader.kt`): battery temperature from the sticky
  `ACTION_BATTERY_CHANGED` broadcast; `PowerManager.currentThermalStatus`; and
  `getThermalHeadroom(10)` (1.0 = severe throttling; NaN keeps the last value). CPU/GPU/skin
  temperatures are NOT readable: thermal zones are absent/denied and the thermal HAL values need
  a system permission.
- Power: `BatteryManager.CURRENT_NOW` × `EXTRA_VOLTAGE` = whole-device battery power. Unplugged
  ⇒ always draw (vendor sign ignored); plugged ⇒ positive current = charging (shown as negative
  watts / "+" in the strip). Values < 10 000 from CURRENT_NOW are treated as mA. No per-component
  power API exists; `/sys/class/power_supply` is denied.
- Battery life (`metrics/BatteryLifeEstimator.kt`, state in `MonitorState.batteryEstimate`): on battery,
  `CHARGE_COUNTER` (µAh) ÷ average |current| over a rolling 120-sample (2 min) window; needs 10
  samples ("Estimating…"), resets on plug-in or a >5 s sampling gap. Charge counters implying a
  capacity outside 500–50 000 mAh are rejected (`—`). Plugged in: `computeChargeTimeRemaining()`.
  The public SDK has no discharge-prediction API (`getBatteryDischargePrediction` is a system API).
- Display (`metrics/DisplayReader.kt`): `DisplayManager` default display refresh rate and
  supported modes, plus `FrameRateMeter` (Choreographer vsync callbacks/s, main thread, runs only
  while sampling). The headset panel rate is owned by the PICO compositor; the Spatial SDK has
  no API for it, so the Android display rate may differ from the panel.
- Emulator: battery is simulated (constant 25.0 °C, 5.000 V, 900 mA ⇒ 4.50 W); thermal
  headroom reported 0.9; display 60 Hz. Verify real values on a headset.

Android/PICO expose no public app API for system-wide CPU/GPU load (PICO's metrics live in the
profiler / `pico-cli perf`), so the app probes readable kernel nodes and shows the source used.

## Theme (`AppTheme` in `content/GaugeTheme.kt`)
- Details header (top-left): a "Theme" label over a native `SegmentControl` with Classic / Gauge.
  The choice is app-wide and persisted (SharedPreferences `details_ui/theme`), default Classic.
- The theme only picks the floating window; the details page always shows the Classic graphs.
  Classic: the glass strip (default container). Gauge: the 3D dial cluster replaces it.
- Swap mechanism (`Main.kt`): each floating window watches `AppTheme.theme`. The strip window,
  on Gauge, opens the cluster and calls `closeWindowContainer()` on itself; the cluster window,
  on Classic, opens `COMPACT_WINDOW_ID` and closes itself. Launching with Gauge saved therefore
  shows the cluster. Details is placed above whichever floating window is open; the cluster is
  placed below details when details is open.
- Clicking any cluster dial opens details (Compose `clickable` + semantics `onClick` on each
  attachment face, with `spatialHoverEffect`).
- The skeuomorphic 2D gauge pane (`GaugeDial`, `ShiftLights`, `MiniDial`) is no longer shown;
  `DetailsPage` pins `theme = DetailsTheme.Classic`. Pre-gauge sources:
  `.scratch/backup-before-gauge-2026-11-23/`.

## 3D instrument cluster (`content/ClusterWindow.kt`, `content/ClusterGeometry.kt`) — GT row
- Volumetric `WindowContainer` `CLUSTER_WINDOW_ID` (1.00 x 0.34 x 0.30 m, no window glass): the
  Gauge theme's floating window (see Theme). Each dial is a button that opens details.
- User's picks: GT curved row; dial styles aircraft black + aviation chronograph + vintage GT cream.
  Left → right: Temperature (small, aircraft black), Network (medium, cream), CPU and GPU (large,
  chronograph), Display (medium, cream), Power (small, aircraft black). Row curve `ROW_CURVE`,
  each gauge yawed to face the centre; no backing panel.
- Faces per metric (`dialScale`): CPU/GPU tachometer 0–100 % (×10), red from 85; Network twin
  needle (black = receive, red = send) on an auto-ranged scale; Temperature short 90° arc
  20–60 °C with green/yellow/red bands (aircraft convention); Power centre-zero ammeter style,
  discharge left / charge right; Display speedometer 0–120 fps with a red refresh-rate marker.
- Only the blank dial plates are generated images (`res/drawable-nodpi/plate_black|opaline|cream.jpg`).
  Bezels (inner chamfer, top, outer chamfer, knurled band), bodies, caps and needles are meshes
  built in code (`ClusterGeometry.lathe` / `needle`) with materials in `Finishes`; generated
  housing models looked blotchy. Materials use `MaterialCullingMode.NONE` (open shells).
- Scales, numerals, arcs, captions, readouts and needle shadows are printed live in `PrintedDial`.
  3D needles follow the same `NeedleState` springs. A point light orbits every 12 s.
- No 3D glass: any entity in front of an attachment panel hid the panel at oblique angles.
- A11Y: six read-only face owners `cluster.temperature|network|cpu|gpu|display|power`, declared
  with literal IDs directly on `content`; rerun `inventory` + `contract init` after changes.
- Earlier versions: chrome + panel in `.scratch/backup-cluster-v1-2026-11-24/`, hypercar 3-gauge
  in `.scratch/backup-cluster-v2-2026-11-24/`. Option sheet: `.scratch/gauge-picker/index.html`.

## Keep-alive (`platform/KeepAlive*.kt`, `platform/BootReceiver.kt`)
- `KeepAliveService`: `specialUse` foreground service + ongoing low-importance notification
  (tap reopens the compact window), `START_STICKY`. It only keeps the process at foreground
  priority; sampling still runs only while a window is composed.
- `KeepAlive.onAppVisible` (from the compact window's `LaunchedEffect`): starts the service,
  then asks once each — one prompt per launch — for `POST_NOTIFICATIONS` and the battery-
  optimisation (Doze) exemption. The exemption also allows restarting the service from background.
- `BootReceiver`: `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` → start the service. Not delivered
  until the app has been opened once after install or a Force stop. Android 15 blocks opening
  the window from the background, so after a reboot the user opens it from the notification/launcher.
- Deliberately not used: dual-process mutual restart, 1-px activities, silent audio, sync-
  adapter/job abuse — blocked on modern Android and policy violations. Force stop cannot be bypassed.
- Dev shortcuts: `adb shell dumpsys deviceidle whitelist +<pkg>`,
  `adb shell pm grant <pkg> android.permission.POST_NOTIFICATIONS`,
  check with `adb shell dumpsys activity services <pkg>`.

## Key files
- `Main.kt` — declares both windows, window ids, details placement/gap.
- `metrics/SystemStatsSampler.kt` — CPU/GPU/network readers + aggregation; call `sample()` off the main thread.
- `metrics/PowerThermalReader.kt` — battery temperature, thermal status/headroom, battery power.
- `metrics/DisplayReader.kt` — display refresh rate/modes and `FrameRateMeter`.
- `content/MonitorState.kt` — shared `MonitorHub` sampler (ref-counted), histories, `Resource` enum.
- `content/CompactStrip.kt` — the compact window's clickable glass strip.
- `content/DetailsPage.kt` — SideNavigation + detail pane + stats (details window).
- `content/Charts.kt` — `UsageGraph` (grid, area, dashed secondary series) and throughput scaling.
- `content/MonitorPalette.kt` — app-owned accents: CPU `#117DBB`, GPU `#4DA60C`, Network `#A74F01`,
  Thermal `#C4314B`, Power `#8764B8`, Display `#038387`.
- `content/Format.kt` — number/speed/uptime formatting.

applicationId: `com.resourceoverlay.monitor` (store identity, permanent). Kotlin namespace / source
package stays `com.example.sysmonitor.pbb1c5130`.

## Rules
- All 2D UI uses SpatialUI (`com.pico.spatial.ui.design.*`) inside `PicoTheme`; no Material/Material3.
- Details window uses system window glass; never paint a root background there. The compact
  window's glass is off on purpose; only the clipped strip draws `backgroundMaterial`.
  Accent colours are only for graph strokes/fills/dots; text uses native `label*` roles.
- `windowConstraints` must stay on the direct root child of each container.
- The compact window id in `Main.kt` (`COMPACT_WINDOW_ID`) must match the manifest
  `pico.spatial.windowcontainer.id`; details placement looks the anchor up by that id.
- After UI changes run:
  `bash .claude/skills/spatial-ui-design-style/scripts/verify-design-style.sh app/src/main --design-color cpuAccent=#117DBB --design-color gpuAccent=#4DA60C --design-color networkAccent=#A74F01 --design-color thermalAccent=#C4314B --design-color powerAccent=#8764B8 --design-color displayAccent=#038387`
  and `pico-cli accessibility project-audit --project . --format json`.

## Build / run
No system JDK; use Homebrew's keg-only OpenJDK 17:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew assembleDebug
pico-cli app install app/build/outputs/apk/debug/app-debug.apk --replace --device emulator-5554
pico-cli app launch com.resourceoverlay.monitor --device emulator-5554
# open details without spatial input (node id comes from the query):
pico-cli interact query --package com.resourceoverlay.monitor --compact --format json
pico-cli interact perform --package com.resourceoverlay.monitor --node <node> --action-id 16 --format json
# switch tabs in the details window ("Temperature"/"Power" are ambiguous with the strip's
# description; query and use `interact perform --node` for those):
pico-cli interact act --package com.resourceoverlay.monitor --text "Network" --action click --refresh never --compact --format json
```

## Next ideas
- Memory entry (ActivityManager.MemoryInfo is reliably readable) and per-core CPU view.
- Close/collapse button in the details window; remember details placement.
