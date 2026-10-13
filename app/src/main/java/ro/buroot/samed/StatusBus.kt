package ro.buroot.samed

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Tiny in-process bus so the service can push live status to the UI without a
 * binder. The Activity just collects this flow while it is visible.
 */
object StatusBus {
    private val _status = MutableStateFlow(
        ShieldEngine.Status(running = false, protected = false)
    )
    val status: StateFlow<ShieldEngine.Status> = _status

    fun publish(s: ShieldEngine.Status) {
        _status.value = s
    }
}
