# Wi-Fi LAN Scanner

A lightweight, fully offline Android app that scans the current Wi-Fi subnet,
discovers active devices (IP, MAC, hostname, manufacturer) and lets you tag
each device with a physical location label ("Living Room", "Office", ...).

## Features

- **Multi-strategy discovery** — eight independent, individually guarded
  methods run in one pass. No single technique is required, and a method the
  platform blocks is reported as an advisory note instead of an error:
  1. **ARP / neighbour table** (`/proc/net/arp`, `/proc/self/net/arp`, or
     `ip neigh` output) — instant, richest data (MAC + vendor). Hidden on
     Android 10+.
  2. **SSDP / UPnP M-SEARCH** (multicast) — routers, TVs, consoles, printers,
     NAS and IoT announce a device class. Needs neither a subnet nor a MAC.
  3. **WS-Discovery** (SOAP Probe over multicast) — the discovery protocol
     Windows itself uses for "Network" in the Explorer: modern PCs, printers,
     scanners, ONVIF cameras, NAS and IP phones answer with their types,
     scopes and URLs.
  4. **Raw mDNS / DNS-SD** (multicast, own DNS parser) — service types,
     hostnames and model strings, independent of the platform `NsdManager`.
  5. **NetBIOS NBSTAT** (UDP broadcast) — machine names *and* MAC addresses
     from Windows/legacy devices: the only unprivileged way to recover a MAC
     when Android hides the ARP table.
  6. **ICMP + TCP sweep** of the subnet — parallel probe of every host, with
     neighbour priming (UDP) and a TCP connect fingerprint.
  7. **mDNS via `NsdManager`** — friendly names, when the platform allows it.
  8. **Reverse DNS** for the hosts that still have no name.
- **Device classification** — services, open ports and names are fused into a
  device type (router, printer, camera, speaker, TV, cast, NAS, phone,
  computer, smart device) used for icons and sorting.
- **Offline vendor lookup** — a bundled, sorted OUI prefix table
  (`app/src/main/assets/oui.csv`, ~1500 common vendors) binary-searched in
  memory. No network calls, no big database.
- **Location labels** — per-MAC labels persisted in Room and merged into the
  scan results reactively (saving a label updates the list instantly).
- **Network statistics** — a collapsible card with everything the public
  APIs expose: SSID and access point, band/channel from the frequency,
  negotiated link speeds (total and RX/TX), signal in dBm with a 0-4 level,
  IPv4 address/prefix, network and broadcast addresses, gateway, DNS, DHCP
  server and lease time, scannable host count, transport, metered flag, VPN
  presence and the platform-reported link bandwidth — plus the last scan's
  duration and device count.
- **Results tooling** — live search across every field, four sort orders
  (IP, name, type, brand), a per-method summary line, a device detail sheet
  with per-field copy, and CSV export through the system share sheet.
- **Modern UI** — Jetpack Compose + Material 3, dynamic color (Android 12+),
  dark/light themes, live-filling `LazyColumn`.

## Architecture (MVVM)

```
ui/          Compose screens, theme, components, ScanViewModel, ScanUiState,
             ScanExport (CSV), DeviceFormatting (sort/search helpers)
net/         NetworkScanner (orchestrator), PingSweep (ICMP/TCP/priming),
             ArpTableReader, SsdpDiscovery, WsDiscovery (Windows discovery),
             MdnsDiscovery, NetBiosDiscovery, NetworkStatistics,
             HostnameResolver (NsdManager + rDNS), LocalNetworkInfo, OuiDatabase
data/        Room: AppDatabase, DeviceLabelDao, DeviceLabelEntity,
             DeviceLabelRepository
model/       NetworkDevice, DeviceKind (classifier), DiscoverySource
```

`NetworkScanner.scan()` returns a `Flow<ScanUpdate>` that emits partial
results progressively, one phase at a time: ARP snapshot, sweep +
fingerprint, SSDP, mDNS (both paths), NetBIOS, reverse DNS. Each
`ScanUpdate` carries the phase label, the devices discovered so far and an
optional advisory `note` describing a degraded method. The ViewModel maps
those into `ScanUiState` (Idle / Loading / Success / Error) and merges the
reactive Room label map on top so edits appear without rescanning.

Only two conditions are reported as errors — Wi-Fi off, or no network at
all with nothing discovered. Everything else degrades to a note.

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

- **Android 10+ hides `/proc/net/arp`**, so MAC addresses and vendor names
  are often unavailable. The app then relies on ICMP/TCP, SSDP, mDNS and
  NetBIOS instead; NetBIOS is the only one that can still recover a MAC
  (for devices that keep it enabled). The scan says so in a note rather than
  failing.
- Android requires the Location permission (with location services enabled)
  to populate Wi-Fi scan results; discovery through the other strategies
  works with just the granted permission even if the location toggle is off.
- `/proc/net/arp` (when readable) only lists hosts the device has actually
  talked to — that is why each sweep primes the neighbour cache with a few
  tiny UDP datagrams first.
- Devices that block ICMP *and* run no TCP services are found through
  multicast only; a device that answers nothing and announces nothing is
  invisible to an unprivileged app, and the app will not pretend otherwise.
- Location labels are keyed by MAC, so on a device where every method
  failed to recover one they cannot be saved.
- The bundled OUI table is a curated subset; regenerate the full IEEE table
  with the `curl`/`awk` one-liner documented at the top of
  `app/src/main/assets/oui.csv`.
