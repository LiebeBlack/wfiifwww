# Keep Room generated code (entities/DAOs are referenced reflectively by adapters).
-keep class com.example.wifiscanner.data.** { *; }

# Keep the public device model so downstream consumers can read scan results.
-keep class com.example.wifiscanner.model.** { *; }

# Keep the scanning engine's public entry points.
-keep class com.example.wifiscanner.net.NetworkScanner { public *; }
-keep class com.example.wifiscanner.net.PingSweep { public *; }
-keep class com.example.wifiscanner.net.LocalNetworkInfo { public *; }
-keep class com.example.wifiscanner.net.SsdpDiscovery { public *; }
-keep class com.example.wifiscanner.net.ArpTableReader { public *; }
-keep class com.example.wifiscanner.net.MdnsDiscovery { public *; }
-keep class com.example.wifiscanner.net.NetBiosDiscovery { public *; }
-keep class com.example.wifiscanner.net.WsDiscovery { public *; }
-keep class com.example.wifiscanner.net.NetworkStatistics { public *; }

-dontwarn org.jetbrains.annotations.**
