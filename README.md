# Wi-Fi LAN Scanner

A lightweight, fully offline Android app that scans the current Wi-Fi subnet,
discovers active devices (IP, MAC, hostname, manufacturer) and lets you tag
each device with a physical location label ("Living Room", "Office", ...).

## Features

- **Fast subnet sweep** — parallel ICMP sweep via the system `ping` binary
  (64 concurrent probes, bounded timeouts), followed by an instant
  `/proc/net/arp` read to recover MAC addresses.
- **Hostnames** — mDNS/DNS-SD discovery through `NsdManager` (Chromecast,
  AirPlay, SMB, printers, HomeKit, ...) plus reverse-DNS PTR lookups with
  strict timeouts.
- **Offline vendor lookup** — a bundled, sorted OUI prefix table
  (`app/src/main/assets/oui.csv`, ~1500 common vendors) binary-searched in
  memory. No network calls, no big database.
- **Location labels** — per-MAC labels persisted in Room and merged into the
  scan results reactively (saving a label updates the list instantly).
- **Modern UI** — Jetpack Compose + Material 3, dynamic color (Android 12+),
  dark/light themes, live-filling `LazyColumn`, edit dialog per device.

## Architecture (MVVM)

```
ui/          Compose screens, theme, components, ScanViewModel, ScanUiState
net/         NetworkScanner (orchestrator), PingSweep, ArpTableReader,
             HostnameResolver (mDNS + rDNS), LocalNetworkInfo, OuiDatabase
data/        Room: AppDatabase, DeviceLabelDao, DeviceLabelEntity,
             DeviceLabelRepository
model/       NetworkDevice (immutable domain model)
```

`NetworkScanner.scan()` returns a `Flow<List<NetworkDevice>>` that emits
partial results progressively: ARP snapshot first, then sweep results +
mDNS names, then reverse-DNS hostnames. The ViewModel maps these emissions
into `ScanUiState` (Idle / Loading / Success / Error) and merges the
reactive Room label map on top so edits appear without rescanning.

## Permissions

- `INTERNET`, `ACCESS_WIFI_STATE`, `ACCESS_NETWORK_STATE`,
  `CHANGE_WIFI_STATE` — scanning and DHCP/subnet info.
- `ACCESS_FINE_LOCATION` — Android gates Wi-Fi scan results behind location;
  requested at runtime through `PermissionGate` with rationale +
  settings fallback on API 29+.
- `NEARBY_WIFI_DEVICES` (`neverForLocation`) — Android 13+ non-location
  Wi-Fi APIs; `POST_NOTIFICATIONS` declared for future background-scan use.

## Building

Open the project in Android Studio (Koala or newer); it will provision Gradle
8.9 from `gradle/wrapper/gradle-wrapper.properties` automatically. Requires
JDK 17 and Android SDK 35.

CLI (with a local Gradle 8.9+ or Android Studio's bundled one):

```bash
./gradlew :app:assembleDebug   # build the APK
./gradlew :app:testDebugUnitTest  # run JVM unit tests (ARP parsing, OUI, subnet math)
```

Install on a device connected to Wi-Fi:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Notes & limitations

- Android requires the Location permission (with location services enabled)
  to populate Wi-Fi scan results; the ARP-based discovery still works with
  just a granted permission even if the system location toggle is off.
- `/proc/net/arp` returns MACs only for hosts the device has actually talked
  to — that's why the ICMP sweep runs first to prime the neighbor cache.
- Some devices (printers behind IGMP snooping, IoT gadgets with power
  saving) may answer the sweep but omit ARP entries; they appear with IP
  only.
- The bundled OUI table is a curated subset; regenerate the full IEEE table
  with the `curl`/`awk` one-liner documented at the top of
  `app/src/main/assets/oui.csv`.
