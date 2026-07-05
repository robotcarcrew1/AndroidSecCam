package com.securitycam.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.securitycam.app.alert.EmailAlerter
import com.securitycam.app.alert.GeminiDescriber
import com.securitycam.app.alert.NtfyAlerter
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportFragmentManager.beginTransaction()
            .replace(R.id.settings_container, SettingsFragment())
            .commit()
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            findPreference<Preference>("test_email")?.setOnPreferenceClickListener { pref ->
                testAction(pref) { ctx -> EmailAlerter(ctx).sendTest() }
                true
            }
            findPreference<Preference>("test_ntfy")?.setOnPreferenceClickListener { pref ->
                testAction(pref) { ctx -> NtfyAlerter(ctx).sendTest() }
                true
            }
            findPreference<Preference>("test_gemini")?.setOnPreferenceClickListener { pref ->
                testAction(pref) { ctx -> GeminiDescriber(ctx).testConnection() }
                true
            }
            findPreference<Preference>("battery_optimization")?.setOnPreferenceClickListener {
                requestIgnoreBatteryOptimizations()
                true
            }
        }

        /**
         * Shows the result as the preference's own summary text rather than a Toast — on
         * this device, Toasts from this app get silently dropped by the system (confirmed via
         * "Toast already killed" in logcat) even after de-duplicating them, so a transient
         * Toast can't be relied on. The summary persists until the next tap, so it can't be missed.
         */
        private fun testAction(pref: Preference, block: suspend (Context) -> Result<String>) {
            val ctx = requireContext().applicationContext
            pref.summary = "Testing…"
            viewLifecycleOwner.lifecycleScope.launch {
                val result = block(ctx)
                pref.summary = result.fold(
                    onSuccess = { "✓ $it" },
                    onFailure = { "✗ Failed: ${it.message}" },
                )
            }
        }

        private fun requestIgnoreBatteryOptimizations() {
            val ctx = requireContext()
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${ctx.packageName}")
                )
                startActivity(intent)
            } else {
                Toast.makeText(ctx, "Already ignoring battery optimizations", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
