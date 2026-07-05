package com.securitycam.app

import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import com.securitycam.app.alert.EmailAlerter
import com.securitycam.app.alert.GeminiDescriber
import com.securitycam.app.alert.NtfyAlerter
import com.securitycam.app.schedule.ScheduleManager
import com.securitycam.app.schedule.Weekday
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity(), PreferenceFragmentCompat.OnPreferenceStartScreenCallback {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportFragmentManager.beginTransaction()
            .replace(R.id.settings_container, SettingsFragment())
            .commit()
    }

    /** Nested `<PreferenceScreen>` entries (e.g. "Monitoring schedule") don't navigate
     *  anywhere on their own — AndroidX requires the host to push a new fragment scoped
     *  to that screen's key, with a back-stack entry so the system Back button returns. */
    override fun onPreferenceStartScreen(caller: PreferenceFragmentCompat, pref: PreferenceScreen): Boolean {
        val fragment = SettingsFragment().apply {
            arguments = Bundle().apply {
                putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, pref.key)
            }
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.settings_container, fragment)
            .addToBackStack(pref.key)
            .commit()
        return true
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

            for (day in Weekday.entries) {
                wireTimePreference("schedule_${day.keyPrefix}_start", defaultMinutes = 0)
                wireTimePreference("schedule_${day.keyPrefix}_end", defaultMinutes = 24 * 60 - 1)
            }
        }

        private val scheduleChangeListener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key != null && key.startsWith("schedule_")) {
                    ScheduleManager.reschedule(requireContext())
                }
            }

        override fun onResume() {
            super.onResume()
            preferenceManager.sharedPreferences
                ?.registerOnSharedPreferenceChangeListener(scheduleChangeListener)
        }

        override fun onPause() {
            super.onPause()
            preferenceManager.sharedPreferences
                ?.unregisterOnSharedPreferenceChangeListener(scheduleChangeListener)
        }

        private fun wireTimePreference(key: String, defaultMinutes: Int) {
            val pref = findPreference<Preference>(key) ?: return
            val sp = preferenceManager.sharedPreferences ?: return
            updateTimeSummary(pref, sp.getInt(key, defaultMinutes))
            pref.setOnPreferenceClickListener {
                showTimePicker(sp, key, pref, defaultMinutes)
                true
            }
        }

        private fun updateTimeSummary(pref: Preference, minutes: Int) {
            pref.summary = "%02d:%02d".format(minutes / 60, minutes % 60)
        }

        private fun showTimePicker(sp: SharedPreferences, key: String, pref: Preference, defaultMinutes: Int) {
            val current = sp.getInt(key, defaultMinutes)
            TimePickerDialog(
                requireContext(),
                { _, hour, minute ->
                    val total = hour * 60 + minute
                    sp.edit().putInt(key, total).apply()
                    updateTimeSummary(pref, total)
                },
                current / 60, current % 60, true,
            ).show()
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
