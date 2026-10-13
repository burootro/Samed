package ro.buroot.samed

/**
 * Reads the current network layout (interface, gateway IP, gateway MAC) using
 * standard tools that already exist on the device. No packets are sent here —
 * this only observes the current state.
 */
object NetInfo {

    data class Gateway(
        val iface: String,
        val gatewayIp: String,
        val gatewayMac: String
    )

    /**
     * Finds the active default route interface and gateway IP via `ip route`,
     * then resolves the gateway MAC via the ARP/neighbour table.
     *
     * Example `ip route` line we parse:
     *   default via 192.168.1.1 dev wlan0 proto dhcp metric 600
     */
    fun readGateway(): Gateway? {
        val routeOut = Root.exec("ip route show default")
        var iface: String? = null
        var gwIp: String? = null

        for (line in routeOut.lines()) {
            val tokens = line.trim().split(Regex("\\s+"))
            val viaIdx = tokens.indexOf("via")
            val devIdx = tokens.indexOf("dev")
            if (viaIdx != -1 && viaIdx + 1 < tokens.size) gwIp = tokens[viaIdx + 1]
            if (devIdx != -1 && devIdx + 1 < tokens.size) iface = tokens[devIdx + 1]
            if (gwIp != null && iface != null) break
        }

        if (gwIp == null || iface == null) return null

        val mac = readMacFor(gwIp, iface) ?: return null
        return Gateway(iface, gwIp, mac)
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
            val tokens = line.trim().split(Regex("\\s+"))
            val idx = tokens.indexOf("lladdr")
            if (idx != -1 && idx + 1 < tokens.size) {
                val mac = tokens[idx + 1].lowercase()
                if (mac.matches(Regex("([0-9a-f]{2}:){5}[0-9a-f]{2}"))) return mac
            }
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
}
