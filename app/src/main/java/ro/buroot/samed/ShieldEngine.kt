package ro.buroot.samed

import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/**
 * The defensive core of Samed.
 *
 * How it protects you:
 * Tools like NetCut cut your connection with an "ARP spoofing" trick — they
 * flood the network telling your phone that the router lives at a fake MAC
 * address, so your traffic gets sent nowhere. The strongest defence is to
 * make the kernel stop trusting those forged announcements for the router.
 *
 * We do that by learning the router's REAL MAC once, then writing it into the
 * neighbour (ARP) table as a PERMANENT entry. A permanent entry is never
 * overwritten by incoming ARP replies, so the attacker's forged packets are
 * simply ignored and your connection stays up.
 *
 * The engine then watches the entry on a short interval. If the gateway or its
 * MAC ever changes (you moved to a new Wi-Fi, or the entry was knocked out),
 * it re-learns and re-pins. This engine only reads and pins ARP state for the
 * router you are already connected to; it never sends forged packets and never
 * touches other devices on the network.
 */
class ShieldEngine(
    private val onStatus: (Status) -> Unit
) {

    data class Status(
        val running: Boolean,
        val protected: Boolean,
        val iface: String = "",
        val gatewayIp: String = "",
        val gatewayMac: String = "",
        val neighState: String = "",
        val attacksBlocked: Int = 0,
        val lastMessage: String = ""
    )

    private val attacksBlocked = AtomicInteger(0)

    // Current pinned target.
    @Volatile private var pinnedIface: String? = null
    @Volatile private var pinnedIp: String? = null
    @Volatile private var pinnedMac: String? = null

    /**
     * Main protection loop. Runs until the surrounding coroutine is cancelled.
     * @param intervalMs how often to re-verify the pin (default 2s).
     */
    suspend fun run(intervalMs: Long = 2000L) {
        if (!Root.isAvailable()) {
            onStatus(Status(running = false, protected = false,
                lastMessage = "Root access was not granted."))
            return
        }

        while (coroutineContext.isActive) {
            try {
                tick()
            } catch (e: Exception) {
                onStatus(currentStatus("Error: ${e.message}"))
            }
            delay(intervalMs)
        }

        // On stop, release the permanent entry so normal ARP resumes.
        releasePin()
    }

    private fun tick() {
        val gw = NetInfo.readGateway()
        if (gw == null) {
            // No usable gateway (e.g. Wi-Fi off). Clear any stale pin.
            if (pinnedIp != null) releasePin()
            onStatus(Status(running = true, protected = false,
                attacksBlocked = attacksBlocked.get(),
                lastMessage = "No active Wi-Fi gateway found."))
            return
        }

        val needPin = gw.iface != pinnedIface ||
                gw.gatewayIp != pinnedIp ||
                gw.gatewayMac != pinnedMac

        if (needPin) {
            // New network or first run: trust the MAC the router is currently
            // answering with, and lock it in permanently.
            pin(gw.iface, gw.gatewayIp, gw.gatewayMac)
            onStatus(currentStatus("Protection locked on ${gw.gatewayIp}"))
            return
        }

        // Already pinned. Verify the live entry still matches what we locked.
        val liveMac = NetInfo.readMacFor(gw.gatewayIp, gw.iface)
        val state = NetInfo.readState(gw.gatewayIp, gw.iface)

        if (liveMac == null || !state.contains("PERMANENT", ignoreCase = true)) {
            // The permanent entry was dropped or downgraded — a classic sign
            // the table was pushed on. Re-assert it immediately.
            pin(pinnedIface!!, pinnedIp!!, pinnedMac!!)
            attacksBlocked.incrementAndGet()
            onStatus(currentStatus("Re-asserted protection (attempt blocked)"))
            return
        }

        if (liveMac != pinnedMac) {
            // Something managed to change the stored MAC. Force it back to the
            // real one we locked earlier.
            pin(pinnedIface!!, pinnedIp!!, pinnedMac!!)
            attacksBlocked.incrementAndGet()
            onStatus(currentStatus("Blocked MAC tampering on gateway"))
            return
        }

        onStatus(currentStatus("Protected"))
    }

    /** Writes the gateway MAC as a permanent neighbour entry. */
    private fun pin(iface: String, ip: String, mac: String) {
        Root.exec("ip neigh replace $ip lladdr $mac dev $iface nud permanent")
        pinnedIface = iface
        pinnedIp = ip
        pinnedMac = mac
    }

    /** Restores normal dynamic ARP resolution for the pinned gateway. */
    private fun releasePin() {
        val ip = pinnedIp
        val iface = pinnedIface
        if (ip != null && iface != null) {
            // Downgrade from permanent back to a normal reachable entry.
            Root.exec("ip neigh replace $ip dev $iface nud reachable")
        }
        pinnedIface = null
        pinnedIp = null
        pinnedMac = null
    }

    private fun currentStatus(message: String): Status {
        val iface = pinnedIface ?: ""
        val ip = pinnedIp ?: ""
        val mac = pinnedMac ?: ""
        val state = if (ip.isNotEmpty() && iface.isNotEmpty())
            NetInfo.readState(ip, iface) else ""
        return Status(
            running = true,
            protected = mac.isNotEmpty(),
            iface = iface,
            gatewayIp = ip,
            gatewayMac = mac,
            neighState = state,
            attacksBlocked = attacksBlocked.get(),
            lastMessage = message
        )
    }
}
