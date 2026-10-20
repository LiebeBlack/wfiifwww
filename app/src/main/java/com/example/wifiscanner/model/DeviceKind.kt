package com.example.wifiscanner.model

/**
 * Coarse device type inferred from the evidence a scan actually collected.
 *
 * There is no reliable "device type" field on a LAN, so the classification is
 * deliberately a documented heuristic over independent signals:
 *
 *  - [isGateway]  — whether the address is the subnet's default route (the
 *                   router). Measured, so it outranks every guess below.
 *  - [services]   — SSDP device classes ("InternetGatewayDevice", "MediaRenderer")
 *                   and DNS-SD/mDNS service types ("_googlecast._tcp").
 *  - [openPorts]  — the TCP ports that accepted a connection (fingerprint),
 *                   including the web panel of a router's admin page.
 *  - [hostname]/[vendor] — name and OUI brand, as weak hints (a computer is
 *                   usually recognizable from "DESKTOP-…" / "WIN-…" alone).
 *
 * Keeping it pure (no Android, no I/O) means the rules are unit-testable and
 * the UI can recompute the kind for a device it already has.
 */
enum class DeviceKind(val label: String) {
    Self("This device"),
    Router("Router / gateway"),
    Printer("Printer"),
    Camera("Camera"),
    Speaker("Speaker"),
    Tv("TV"),
    MediaPlayer("Media player"),
    Nas("NAS / storage"),
    Phone("Phone / tablet"),
    Computer("Computer"),
    Iot("Smart device"),
    Unknown("Unidentified device");

    companion object {

        /**
         * Classifies a device from its signals. Order matters: the most
         * specific, highest-confidence rules run first, and anything that
         * cannot be identified stays [Unknown] instead of guessing.
         *
         * Two signals were added on top of the original heuristic because they
         * decide the two most common mistakes:
         *
         *  - [isGateway] — the host owning the default route is the router.
         *    It is measured (LinkProperties / DHCP), never inferred, so a
         *    router that hides its UPnP announcements is still recognized.
         *  - a **web panel plus router evidence** — many routers serve their
         *    admin page on port 80/8080 like half the LAN does, so the port is
         *    only used together with the router's own name, brand or firmware.
         *    Requiring the web port keeps a router found without ports from
         *    being mislabeled, and keeps a router branded as an IoT device
         *    (smart plugs share the parent brand's OUI) from stealing the slot.
         */
        fun classify(
            isSelf: Boolean,
            services: List<String> = emptyList(),
            openPorts: Set<Int> = emptySet(),
            hostname: String? = null,
            vendor: String? = null,
            isGateway: Boolean = false,
        ): DeviceKind {
            if (isSelf) return Self

            val text = buildString {
                services.forEach { append(it.lowercase()).append(' ') }
                hostname?.let { append(it.lowercase()).append(' ') }
                vendor?.let { append(it.lowercase()) }
            }
            fun mentions(vararg needles: String) = needles.any { text.contains(it) }
            fun open(vararg ports: Int) = ports.any { it in openPorts }

            // The three ports every router, NAS and camera web UI shares.
            val hasWebPanel = open(80, 8080, 8443)

            return when {
                // The device that routes this subnet. Checked before every
                // other rule: the gateway is the only device whose role is
                // known with certainty.
                isGateway -> Router

                // Routers/gateways announce a UPnP device class; the port set
                // is only a fallback hint.
                mentions("internetgatewaydevice", "wandevice", "wanconnectiondevice", "ipbridge") ->
                    Router

                // Web panel + router evidence (name, brand or router
                // firmware): this is the "device with an admin page that is
                // actually a router" case.
                hasWebPanel && mentions(*ROUTER_HINTS) -> Router

                // Printers are the easiest to spot: RAW/IPP ports, or a name/service hint
                open(9100, 9101, 515) ||
                    mentions("_ipp", "_printer", "_pdl-datastream", "printerserver", "printer", "scanner") ->
                    Printer

                // Cameras: RTSP / ONVIF.
                open(554) || mentions("_rtsp", "_onvif", "camera", "ipcam") -> Camera

                // Speakers first: a speaker also answers _airplay, and its own
                // name is a much stronger signal than a generic service list.
                mentions("_raop", "_spotify-connect", "sonos", "homepod", "speaker", "_airplay") ->
                    Speaker

                // TVs / streaming sticks.
                mentions("appletv", "apple tv", "bravia", "roku", "webos", "tizen", "smarttv", " tv") ->
                    Tv

                // Cast targets and DLNA renderers.
                mentions("_googlecast", "mediarenderer", "_airplay", "firetv", "chromecast") ->
                    MediaPlayer

                // NAS boxes: storage-specific ports/services.
                open(5000, 5001, 2049, 9000) || mentions("synology", "qnap", "readynas", "freenas", "nas") ->
                    Nas

                // iPhones/iPads expose the Apple lockdown port.
                open(62078) || mentions("iphone", "ipad", "ipod") -> Phone

                // Desktops/laptops: file sharing or remote access ports.
                open(445, 139, 3389, 5900, 22) ||
                    mentions(
                        "_smb",
                        "_workstation",
                        "_ssh",
                        "windows",
                        // Windows machine names carry the OS in the name:
                        // "DESKTOP-ABC123", "WIN-XYZ", "JOHN-PC".
                        "desktop",
                        "win-",
                        "-pc",
                        "pc-",
                        "laptop",
                        "thinkpad",
                        "latitude",
                        "inspiron",
                        "surface",
                        // Unix/BSD desktops and workstations.
                        "ubuntu",
                        "debian",
                        "fedora",
                        "macos",
                        "mac os",
                        "macbook",
                        "imac",
                        "arch linux",
                        "proxmox",
                        "vmware",
                        "virtualbox",
                        // WS-Discovery announces Windows PCs as pub:Computer.
                        "computer",
                    ) -> Computer

                // Smart-home / IoT stacks.
                mentions("_homekit", "_hap", "_matter", "esphome", "tasmota", "shelly", "sonoff", "tuya", "hue") ->
                    Iot

                else -> Unknown
            }
        }

        /**
         * Evidence that a device *is* a router: its own name, its brand or a
         * router firmware. Kept deliberately narrow — bare brand names that
         * also ship phones or plugs ("huawei", "xiaomi", "asus") are absent,
         * and every hit still needs the web panel in front of it.
         */
        private val ROUTER_HINTS = arrayOf(
            "router",
            "gateway",
            "openwrt",
            "dd-wrt",
            "lede",
            "pfsense",
            "opnsense",
            "mikrotik",
            "routerboard",
            "ubiquiti",
            "unifi",
            "tp-link",
            "tplink",
            "d-link",
            "dlink",
            "netgear",
            "linksys",
            "zyxel",
            "fritz",
            "asuswrt",
            "edgeos",
            "peplink",
            "home gateway",
        )
    }
}
