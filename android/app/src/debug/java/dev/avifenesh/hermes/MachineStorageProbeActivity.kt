package dev.avifenesh.hermes

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore

/** Debug/emulator only. Requires empty hm preferences; never pairs or sends network requests. */
class MachineStorageProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val result = runCatching { probe() }
        val message = result.fold(
            { "MACHINE STORAGE PASS\nStartup migration; atomic removal; field round trip; park/update/forget; corrupt-storage protection; disk plaintext scan; cleanup" },
            { "MACHINE STORAGE FAIL: ${it.javaClass.simpleName}: ${it.message}" }
        )
        Log.i("MachineStorageProbe", message)
        setContentView(TextView(this).apply {
            text = message
            textSize = 20f
            setPadding(32, 80, 32, 32)
        })
    }

    private fun probe() {
        check(Build.PRODUCT.contains("sdk") && (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            "Use a debug APK on an Android emulator"
        }
        val sp = getSharedPreferences("hm", Context.MODE_PRIVATE)
        check(sp.all.isEmpty()) { "Requires an empty test installation: clear app data first" }
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val hadKey = ks.containsAlias("hm_prefs")
        // All credentials in this fixture are synthetic, deliberately recognizable, and unusable.
        val a = fixture("a")
        val b = fixture("b")
        val c = fixture("c")
        val secrets = listOf(a, b, c).flatMap { listOf(it.getString("token"), it.getString("cfs")) }
        try {
            val legacy = JSONArray().put(a).put(b).toString()
            check(sp.edit().putString("machines", legacy).commit())
            check(File(applicationInfo.dataDir, "shared_prefs/hm.xml").readText().contains(a.getString("token"))) {
                "Legacy fixture did not reproduce plaintext disk storage"
            }
            var observed = false
            var atomic = true
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
                if (key == "machines" || key == "e_machines") {
                    observed = true
                    atomic = atomic && !prefs.contains("machines") && prefs.contains("e_machines")
                }
            }
            sp.registerOnSharedPreferenceChangeListener(listener)
            try {
                // Exercise the exact Application startup entry point BEFORE opening/reading Machines.
                Prefs.init(this)
            } finally {
                sp.unregisterOnSharedPreferenceChangeListener(listener)
            }
            check(observed && atomic) { "Migration was not one observable preference transaction" }
            check(Prefs.machines == legacy) { "Migration changed registry contents" }
            val encrypted = sp.getString("e_machines", null)
            check(!encrypted.isNullOrBlank() && encrypted != legacy)
            assertNoPlaintext(sp, secrets)
            Prefs.init(this)
            check(sp.getString("e_machines", null) == encrypted) { "Migration was not idempotent" }
            assertFields(a, Machines.all().single { it.str("base") == a.str("base") })
            assertFields(b, Machines.all().single { it.str("base") == b.str("base") })

            // Mixed-version state: authenticated encrypted data wins; stale plaintext is purged at init.
            check(sp.edit().putString("machines", "[]").commit())
            Prefs.init(this)
            check(Prefs.machines == legacy && !sp.contains("machines"))

            // restore() is the local half of switching; switchTo()/afterPair() deliberately are NOT called.
            Machines.restore(c)
            assertFields(c, Machines.all().single { Machines.isActive(it) })
            Machines.park()
            check(JSONArray(Prefs.machines).length() == 3)
            assertNoPlaintext(sp, secrets)
            Prefs.server = "Updated synthetic machine"
            Machines.park()
            val parked = JSONArray(Prefs.machines)
            check(parked.length() == 3) { "Parking the same base duplicated it" }
            check(parked.objs().single { it.str("base") == c.str("base") }.str("server") == Prefs.server)
            Machines.restore(a)
            assertFields(a, Machines.all().single { Machines.isActive(it) })
            Machines.forget(b.str("base"))
            check(Machines.all().map { it.str("base") }.toSet() == setOf(a.str("base"), c.str("base")))
            check(JSONArray(Prefs.machines).objs().none { it.str("base") == b.str("base") })
            assertNoPlaintext(sp, secrets)

            // Never silently replace an unreadable registry with just the active machine.
            val valid = sp.getString("e_machines", null)
            check(sp.edit().putString("e_machines", "invalid-test-ciphertext").commit())
            check(runCatching { Machines.park() }.isFailure)
            check(runCatching { Machines.forget(c.str("base")) }.isFailure)
            check(sp.getString("e_machines", null) == "invalid-test-ciphertext")
            check(sp.edit().putString("e_machines", valid).commit())
            Prefs.machines = "not-json"
            check(runCatching { Machines.park() }.isFailure)
            check(Prefs.machines == "not-json")
            check(sp.edit().putString("e_machines", valid).commit())

            // Corrupt mixed storage must block registry writes, not crash Application startup.
            check(sp.edit().putString("machines", "[]").putString("e_machines", "invalid-test-ciphertext").commit())
            check(runCatching { Prefs.init(this) }.isSuccess) { "Corrupt registry crashes Application startup" }
            check(runCatching { Machines.park() }.isFailure)
            check(sp.getString("e_machines", null) == "invalid-test-ciphertext")
            check(sp.edit().remove("machines").putString("e_machines", valid).commit())

            // Null deletes both representations; an empty legacy registry also migrates.
            Prefs.machines = null
            check(!sp.contains("machines") && !sp.contains("e_machines"))
            check(sp.edit().putString("machines", "[]").commit())
            Prefs.init(this)
            check(Prefs.machines == "[]" && !sp.contains("machines"))
            assertNoPlaintext(sp, secrets)
        } finally {
            // onCreate finishes before App's onActivityStarted/Hub.connect: no synthetic pairing escapes.
            check(sp.edit().clear().commit()) { "Failed to clear probe preferences" }
            Prefs.init(this)
            Hub.reset()
            check(sp.edit().clear().commit()) { "Failed to clear reset defaults" }
            if (!hadKey && ks.containsAlias("hm_prefs")) ks.deleteEntry("hm_prefs")
            check(sp.all.isEmpty()) { "Probe left preference state behind" }
        }
    }

    private fun fixture(id: String) = JSONObject()
        .put("base", "https://machine-$id.invalid")
        .put("token", "SYNTHETIC_MACHINE_${id}_BEARER_NEVER_VALID")
        .put("device", "probe-device-$id")
        .put("cfi", "probe-access-$id")
        .put("cfs", "SYNTHETIC_MACHINE_${id}_CF_SECRET_NEVER_VALID")
        .put("server", "Synthetic $id")
        .put("profile", "probe-profile-$id")
        .put("fcmcfg", JSONObject().put("project_id", "synthetic-$id").toString())
        .put("fcmtok", "synthetic-fcm-$id")
        .put("fleetseen", 123.5)
        .put("agent", "Synthetic agent $id")

    private fun assertFields(expected: JSONObject, actual: JSONObject) {
        check(expected.keys().asSequence().toSet() == actual.keys().asSequence().toSet()) { "Machine field set changed" }
        expected.keys().forEach { key ->
            check(expected.get(key) == actual.get(key)) { "Machine field did not round trip: $key" }
        }
    }

    private fun assertNoPlaintext(sp: SharedPreferences, secrets: List<String>) {
        check(!sp.contains("machines") && !sp.contains("token") && !sp.contains("cfs")) { "Plaintext preference key survived" }
        // A synchronous commit is a disk barrier for earlier apply() calls on active credentials.
        check(sp.edit().putBoolean("probe_disk_barrier", true).commit())
        check(sp.edit().remove("probe_disk_barrier").commit())
        val memory = sp.all.values.joinToString("\n")
        val disk = listOf("hm.xml", "hm.xml.bak").map { File(applicationInfo.dataDir, "shared_prefs/$it") }
            .filter { it.exists() }.joinToString("\n") { it.readText() }
        check(secrets.none { memory.contains(it) || disk.contains(it) }) { "Synthetic credential found in plaintext storage" }
    }
}
