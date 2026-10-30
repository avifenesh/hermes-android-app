package dev.avifenesh.hermes

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Several paired machines. The active one lives in the flat Prefs fields every request already reads; the others are
 * parked here. Switching parks the current one, loads the target, and resyncs from zero (the client keeps no disk cache).
 * The device keys are shared: one hardware key pair per phone, registered with each machine at pairing.
 */
object Machines {
    private val switchMx = Mutex()

    private fun cur(): JSONObject? = Prefs.base?.let { b ->
        JSONObject().put("base", b).put("token", Prefs.token).put("device", Prefs.deviceId).put("cfi", Prefs.cfId)
            .put("cfs", Prefs.cfSecret).put("server", Prefs.server).put("profile", Prefs.profile)
            .put("fcmcfg", Prefs.fcmCfg).put("fcmtok", Prefs.fcmToken).put("fleetseen", Prefs.fleetSeen).put("agent", Prefs.agentName)
            .put("moread", Prefs.momentumRead)
    }

    fun all(): List<JSONObject> {
        // Do not treat unreadable storage as empty: park/forget would destroy existing pairings.
        val saved = JSONArray(Prefs.machines ?: "[]").objs().associateBy { it.str("base") }.toMutableMap()
        cur()?.takeIf { Prefs.token != null }?.let { saved[it.str("base")] = it }
        return saved.values.sortedBy { it.str("server").ifBlank { it.str("base") } }
    }

    fun park() {
        val c = cur()?.takeIf { Prefs.token != null } ?: return
        val l = all().filter { it.str("base") != c.str("base") } + c
        Prefs.machines = JSONArray(l).toString()
    }

    fun forget(base: String) { Prefs.machines = JSONArray(all().filter { it.str("base") != base }).toString() }

    fun isActive(m: JSONObject) = m.str("base") == Prefs.base

    suspend fun switchTo(c: android.content.Context, m: JSONObject) = switchMx.withLock {
        if (isActive(m)) return@withLock
        if (m.str("base").isBlank() || m.str("token").isBlank()) throw ApiError(0, "That machine isn't paired")
        val ticket = Api.epoch.capture()
        PhoneService.disarm(c)
        currentCoroutineContext().ensureActive()
        val targetTicket = Api.epoch.commit(ticket) {
            park()
            restore(m)
            Ui.stack.clear()
            Api.epoch.capture()
        }
        Hub.afterPair(c, targetTicket)
    }

    fun restore(m: JSONObject?) = synchronized(Api.epoch) {
        if (m == null) return@synchronized
        Hub.reset()
        Prefs.base = m.str("base"); Prefs.token = m.str("token").ifBlank { null }; Prefs.deviceId = m.str("device").ifBlank { null }
        Prefs.cfId = m.str("cfi").ifBlank { null }; Prefs.cfSecret = m.str("cfs").ifBlank { null }
        Prefs.server = m.str("server").ifBlank { null }; Prefs.profile = m.str("profile").ifBlank { null }
        Prefs.fcmCfg = m.str("fcmcfg").ifBlank { null }; Prefs.fcmToken = m.str("fcmtok").ifBlank { null }
        Prefs.fleetSeen = m.optDouble("fleetseen", 0.0)
        Prefs.momentumRead = m.str("moread").ifBlank { null }; Momentum.reloadRead()
        Prefs.agentName = m.str("agent").ifBlank { "Hermes" }; Hub.agent.value = Prefs.agentName
    }
}
