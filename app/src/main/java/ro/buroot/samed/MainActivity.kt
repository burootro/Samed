package ro.buroot.samed

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ro.buroot.samed.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestNotifPermissionIfNeeded()

        binding.toggleButton.setOnClickListener {
            val prefs = prefs()
            val enabled = prefs.getBoolean("enabled", false)
            if (enabled) {
                prefs.edit().putBoolean("enabled", false).apply()
                ShieldService.stop(this)
                renderStopped()
            } else {
                startProtection()
            }
        }

        collectStatus()
    }

    override fun onResume() {
        super.onResume()
        // Reflect persisted state on open.
        if (!prefs().getBoolean("enabled", false)) renderStopped()
    }

    private fun startProtection() {
        binding.statusText.text = getString(R.string.checking_root)
        lifecycleScope.launch {
            val hasRoot = withContext(Dispatchers.IO) { Root.isAvailable() }
            if (!hasRoot) {
                binding.statusText.text = getString(R.string.no_root)
                return@launch
            }
            prefs().edit().putBoolean("enabled", true).apply()
            ShieldService.start(this@MainActivity)
        }
    }

    private fun collectStatus() {
        lifecycleScope.launch {
            StatusBus.status.collect { s -> render(s) }
        }
    }

    private fun render(s: ShieldEngine.Status) {
        val on = prefs().getBoolean("enabled", false)
        binding.toggleButton.text =
            getString(if (on) R.string.stop_protection else R.string.start_protection)

        if (!on) {
            renderStopped()
            return
        }

        binding.shieldBadge.text = if (s.protected) "🛡" else "…"
        binding.statusText.text = when {
            s.protected -> getString(R.string.protected_active)
            else -> s.lastMessage.ifEmpty { getString(R.string.working) }
        }

        binding.detailGateway.text = getString(R.string.gateway_fmt, s.gatewayIp.ifEmpty { "—" })
        binding.detailMac.text = getString(R.string.mac_fmt, s.gatewayMac.ifEmpty { "—" })
        binding.detailIface.text = getString(R.string.iface_fmt, s.iface.ifEmpty { "—" })
        binding.detailBlocked.text = getString(R.string.blocked_fmt, s.attacksBlocked)
    }

    private fun renderStopped() {
        binding.shieldBadge.text = "○"
        binding.statusText.text = getString(R.string.protection_off)
        binding.toggleButton.text = getString(R.string.start_protection)
        binding.detailGateway.text = getString(R.string.gateway_fmt, "—")
        binding.detailMac.text = getString(R.string.mac_fmt, "—")
        binding.detailIface.text = getString(R.string.iface_fmt, "—")
        binding.detailBlocked.text = getString(R.string.blocked_fmt, 0)
    }

    private fun requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun prefs() = getSharedPreferences("samed", Context.MODE_PRIVATE)
}
