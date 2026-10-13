package ro.buroot.samed

/**
 * Reads the current network layout (interface, gateway IP, gateway MAC) using
 * standard tools that already exist on the device. It only observes state;
 * the single exception is one ping to our own router when its MAC is not yet
 * in the neighbour table, so the kernel resolves it normally.
 */
object NetInfo {

    data class Gateway(
        val iface: String,
        val gatewayIp: String,
        val gatewayMac: String
    )

    private val MAC_REGEX = Regex("([0-9a-f]{2}:){5}[0-9a-f]{2}")
    private val IPV4_REGEX = Regex("""\d{1,3}(\.\d{1,3}){3}""")
    private const val ZERO_MAC = "00:00:00:00:00:00"

    /**
     * Finds the Wi-Fi (or Ethernet) default gateway and its MAC address.
     * Returns null if there is no ARP-based network (e.g. mobile data only).
     */
    fun readGateway(): Gateway? {
        val (iface, gwIp) = findDefaultRoute() ?: return null

        var mac = readMacFor(gwIp, iface) ?: readMacFromProc(gwIp, iface)
        if (mac == null) {
            // The neighbour entry may not exist yet. Ping our router once so
            // the kernel resolves its MAC, then read again.
            Root.exec("ping -c 1 -W 1 $gwIp >/dev/null 2>&1")
            mac = readMacFor(gwIp, iface) ?: readMacFromProc(gwIp, iface)
        }
        if (mac == null) return null
        return Gateway(iface, gwIp, mac)
    }

    /**
     * Android does NOT keep the default route in the main table. Each network
     * has its own routing table (e.g. "table wlan0" or a numeric id), so we
     * scan all tables. Example line:
     *   default via 192.168.1.1 dev wlan0 table wlan0 proto static
     */
    private fun findDefaultRoute(): Pair<String, String>? {
        val candidates = mutableListOf<Pair<String, String>>()

        val all = Root.exec("ip -4 route show table all")
        for (line in all.lines()) {
            val t = line.trim().split(Regex("\\s+"))
            if (t.firstOrNull() != "default") continue
            val gw = t.valueAfter("via") ?: continue
            val dev = t.valueAfter("dev") ?: continue
            if (!IPV4_REGEX.matches(gw)) continue
            candidates.add(dev to gw)
        }

        // Prefer Wi-Fi, then Ethernet.
        candidates.firstOrNull { isWifi(it.first) }?.let { return it }
        candidates.firstOrNull { isEthernet(it.first) }?.let { return it }

        // Fallback: ask the kernel which route it would use right now.
        // Example: 1.1.1.1 via 192.168.1.1 dev wlan0 table 1021 src 192.168.1.5 uid 0
        val get = Root.exec("ip -4 route get 1.1.1.1")
        for (line in get.lines()) {
            val t = line.trim().split(Regex("\\s+"))
            val gw = t.valueAfter("via") ?: continue
            val dev = t.valueAfter("dev") ?: continue
            if (IPV4_REGEX.matches(gw) && !isCellular(dev)) return dev to gw
        }

        // Last resort: any non-cellular default route.
        return candidates.firstOrNull { !isCellular(it.first) }
    }

    /**
     * Reads the current neighbour (ARP) entry for a given IP. Returns the MAC
     * string (lowercase, colon-separated) or null if not present/incomplete.
     *
     * Example `ip neigh` line:
     *   192.168.1.1 dev wlan0 lladdr a4:2b:b0:11:22:33 REACHABLE
     */
    fun readMacFor(ip: String, iface: String): String? {
        val out = Root.exec("ip neigh show $ip dev $iface")
        for (line in out.lines()) {
            val t = line.trim().split(Regex("\\s+"))
            val mac = t.valueAfter("lladdr")?.lowercase() ?: continue
            if (MAC_REGEX.matches(mac) && mac != ZERO_MAC) return mac
        }
        return null
    }

    /**
     * Fallback MAC lookup through /proc/net/arp. Columns:
     *   IP address  HW type  Flags  HW address  Mask  Device
     */
    private fun readMacFromProc(ip: String, iface: String): String? {
        val out = Root.exec("cat /proc/net/arp")
        for (line in out.lines()) {
            val t = line.trim().split(Regex("\\s+"))
            if (t.size < 6) continue
            if (t[0] != ip || t[5] != iface) continue
            val mac = t[3].lowercase()
            if (MAC_REGEX.matches(mac) && mac != ZERO_MAC) return mac
        }
        return null
    }

    /**
     * Returns the currently stored neighbour state string (e.g. REACHABLE,
     * STALE, PERMANENT, FAILED) for the gateway, used only for status display.
     */
    fun readState(ip: String, iface: String): String {
        val out = Root.exec("ip neigh show $ip dev $iface")
        val line = out.lines().firstOrNull() ?: return "UNKNOWN"
        val tokens = line.trim().split(Regex("\\s+"))
        return tokens.lastOrNull { it.matches(Regex("[A-Z]+")) } ?: "UNKNOWN"
    }

    private fun isWifi(iface: String) = iface.startsWith("wlan")

    private fun isEthernet(iface: String) = iface.startsWith("eth")

    // Mobile data interfaces are point-to-point and have no ARP at all.
    private fun isCellular(iface: String) =
        iface.startsWith("rmnet") || iface.startsWith("ccmni") ||
                iface.startsWith("seth") || iface.startsWith("pdp") ||
                iface.startsWith("v4-") || iface.startsWith("dummy")

    private fun List<String>.valueAfter(key: String): String? {
        val i = indexOf(key)
        return if (i != -1 && i + 1 < size) this[i + 1] else null
    }
}
