package dev.avifenesh.hermes

import android.content.SharedPreferences

/** Versioned engine settings. Legacy OWW scores cannot be mapped to native window cutoffs. */
internal object WakePreferences {
    // UI adjusts whole hundredths. Exclude zero (warm-up cannot progress) and one
    // (strict sum > 255 * window can never trigger); these are arithmetic limits, not calibration.
    const val CUTOFF_KEY = "mww_probability_cutoff"
    private const val ENGINE_KEY = "wake_engine_model"

    fun migrate(sp: SharedPreferences) {
        if (sp.getString(ENGINE_KEY, null) != WakeWord.MODEL_ID) {
            // Preserve wakeword opt-in exactly. Do not touch pairing, Talk, or other preferences.
            sp.edit().putString("wakephrase", WakeWord.MODEL_ID)
                .putString(ENGINE_KEY, WakeWord.MODEL_ID)
                .putFloat(CUTOFF_KEY, WakeWord.DEFAULT_CUTOFF)
                .remove("wakethr").apply()
        }
    }

    fun cutoff(sp: SharedPreferences): Float {
        migrate(sp)
        val saved = runCatching { sp.getFloat(CUTOFF_KEY, WakeWord.DEFAULT_CUTOFF) }.getOrDefault(Float.NaN)
        return saved.takeIf { it.isFinite() && it in 0.01f..0.99f } ?: WakeWord.DEFAULT_CUTOFF
    }
}
