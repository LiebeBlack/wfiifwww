package com.example.wifiscanner.model

/**
 * Coarse device type inferred from the evidence a scan actually collected.
 *
 * There is no reliable "device type" field on a LAN, so the classification is
 * deliberately a documented heuristic over three independent signals:
 *
 *  - [services]  — SSDP device classes ("InternetGatewayDevice", "MediaRenderer")
 *                  and DNS-SD/mDNS service types ("_googlecast._tcp").
 *  - [openPorts] — the TCP ports that accepted a connection (fingerprint).
 *  - [hostname]/[vendor] — name and OUI brand, as weak hints.
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
         */
        fun classify(
            isSelf: Boolean,
            services: List<String> = emptyList(),
            openPorts: Set<Int> = emptySet(),
            hostname: String? = null,
            vendor: String? = null,
        ): DeviceKind {
            if (isSelf) return Self

            val text = buildString {
                services.forEach { append(it.lowercase()).append(' ') }
                hostname?.let { append(it.lowercase()).append(' ') }
                vendor?.let { append(it.lowercase()) }
            }
            fun mentions(vararg needles: String) = needles.any { text.contains(it) }
            fun open(vararg ports: Int) = ports.any { it in openPorts }

            return when {
                // Routers/gateways announce a UPnP device class; the port set
                // is only a fallback hint.
                mentions("internetgatewaydevice", "wandevice", "wanconnectiondevice", "ipbridge") ->
                    Router

                // Printers are the easiest to spot: dedicated RAW/IPP ports.
                open(9100, 9101, 515) || mentions("_ipp", "_printer", "_pdl-datastream", "printerserver") ->
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
                        "macbook",
                        "imac",
                        "desktop",
                        "laptop",
                        // WS-Discovery announces Windows PCs as pub:Computer.
                        "computer",
                    ) ->
                    Computer

                // Smart-home / IoT stacks.
                mentions("_homekit", "_hap", "_matter", "esphome", "tasmota", "shelly", "sonoff", "tuya", "hue") ->
                    Iot

                else -> Unknown
            }
        }
    }
}
