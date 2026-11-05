package dev.avifenesh.hermes

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AiNoticeTest {
    @Before fun englishStrings() = TestStrings.install()

    @Test fun aiNoticeCoversSourceRiskPrivacyAndReporting() {
        val text = AiNotice.points().joinToString("\n")
        assertTrue(str(R.string.ai_notice_title).isNotBlank())
        assertTrue("replies come from the user's gateway models", text.contains("models") && text.contains("gateway"))
        assertTrue("replies can be wrong or inappropriate", text.contains("wrong") && text.contains("inappropriate"))
        assertTrue("the publisher does not see chats", text.contains("\u2068$EDITION_PUBLISHER\u2069 does not see your chats"))
        assertTrue("how to report", text.contains("Report a problem") && text.contains("long-press"))
    }

    @Test fun aiNoticeShowsOnceAfterPairing() {
        assertTrue(AiNotice.shouldShow(paired = true, seen = false))
        assertFalse(AiNotice.shouldShow(paired = true, seen = true))
        assertFalse(AiNotice.shouldShow(paired = false, seen = false))
    }

    /** A kill right after "Got it" must not bring the notice back: the acknowledgement is written before it returns. */
    @Test fun acknowledgementIsWrittenSynchronously() {
        val prefs = RecordingPrefs()
        assertFalse(AiNotice.seen(prefs))
        AiNotice.acknowledge(prefs)
        assertEquals(listOf("commit"), prefs.writes)
        assertTrue(AiNotice.seen(prefs))
    }
}

/** Preferences that record how each edit was written. */
private class RecordingPrefs : SharedPreferences {
    val values = mutableMapOf<String, Any?>()
    val writes = mutableListOf<String>()
    override fun getBoolean(key: String, defValue: Boolean) = values[key] as? Boolean ?: defValue
    override fun contains(key: String) = key in values
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        val pending = mutableMapOf<String, Any?>()
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun commit(): Boolean { values.putAll(pending); writes += "commit"; return true }
        override fun apply() { values.putAll(pending); writes += "apply" }
        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun remove(key: String) = apply { pending[key] = null }
        override fun clear() = apply { values.clear() }
    }
    override fun getAll(): MutableMap<String, *> = values
    override fun getString(key: String, defValue: String?) = values[key] as? String ?: defValue
    override fun getStringSet(key: String, defValues: MutableSet<String>?) = defValues
    override fun getInt(key: String, defValue: Int) = defValue
    override fun getLong(key: String, defValue: Long) = defValue
    override fun getFloat(key: String, defValue: Float) = defValue
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
}
