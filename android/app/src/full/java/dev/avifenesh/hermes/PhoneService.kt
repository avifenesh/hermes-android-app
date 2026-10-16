package dev.avifenesh.hermes

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/** SSE/FCM are wake hints only. Fetch commands with this device's signed API credentials. */
class PhoneService : AccessibilityService() {
    companion object {
        val controlState = kotlinx.coroutines.flow.MutableStateFlow(false)
        @Volatile var inst: PhoneService? = null
        private val done = ConcurrentHashMap<String, Long>()
        private val lock = Mutex()
        private val activation = java.util.concurrent.atomic.AtomicLong()
        @Volatile private var activeJob: kotlinx.coroutines.Job? = null
        fun onArmChanged() { activation.incrementAndGet() }
        fun activationGeneration(): Long = activation.get()

        fun enabled(c: Context): Boolean {
            val want = ComponentName(c, PhoneService::class.java)
            val on = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return on.split(':').any { ComponentName.unflattenFromString(it) == want }
        }
        fun openSettings(c: Context) {
            c.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        private fun stopLocal(c: Context) {
            Prefs.phoneArmed = false // Local kill switch acts even when the server is unreachable.
            activeJob?.cancel()
            inst?.cancelActiveGesture()
            PhoneNotice.hide(c)
            LiveService.sync(c)
        }
        fun stop(c: Context) {
            stopLocal(c)
            Hub.scope.launch { runCatching { Api.post("/v1/phone/arm", JSONObject().put("on", false)) } }
        }
        suspend fun disarm(c: Context) {
            stopLocal(c)
            if (Prefs.paired) runCatching { Api.post("/v1/phone/arm", JSONObject().put("on", false)) }
        }
        fun pull() = Hub.scope.launch {
            if (!Prefs.paired || !Prefs.phoneArmed) return@launch
            val a = runCatching { Api.get("/v1/phone/pending").optJSONArray("cmds") }.getOrNull() ?: return@launch
            for (i in 0 until a.length()) onCmd(a.getJSONObject(i))
        }
        private fun onCmd(cmd: JSONObject) {
            val id = cmd.str("id")
            if (id.isBlank() || cmd.str("device") != Prefs.deviceId || !Prefs.phoneArmed) return
            if (done.putIfAbsent(id, System.currentTimeMillis()) != null) return
            done.entries.removeIf { System.currentTimeMillis() - it.value > 600_000 }
            val generation = activation.get()
            Hub.scope.launch {
                lock.withLock {
                    // Re-read pending after waiting for the device lock. Disarm, cancellation and timeout remove it.
                    val current = runCatching { Api.get("/v1/phone/pending").optJSONArray("cmds").objs().firstOrNull { it.str("id") == id } }.getOrNull()
                    if (current == null || !Prefs.phoneArmed || generation != activation.get() || current.str("device") != Prefs.deviceId) return@withLock
                    val expiry = current.optDouble("expires", 0.0)
                    if (expiry <= System.currentTimeMillis() / 1000.0) return@withLock
                    val s = inst
                    activeJob = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
                    try {
                    val out = if (s == null) JSONObject().put("ok", false).put("error", "Enable Hermes phone control in Android Accessibility settings")
                        else withContext(Dispatchers.Main) {
                            if (generation != activation.get() || !Prefs.phoneArmed) JSONObject().put("ok", false).put("error", "Control changed; command cancelled")
                            else runCatching { s.exec(current) }.getOrElse { JSONObject().put("ok", false).put("error", it.message ?: it.toString()) }
                        }
                    if (generation == activation.get() && Prefs.phoneArmed && current.str("device") == Prefs.deviceId && expiry > System.currentTimeMillis() / 1000.0) {
                        runCatching { Api.post("/v1/phone/result", out.put("id", id)) }
                    }
                    } finally { activeJob = null }
                }
            }
        }
    }

    private data class Entry(val node: AccessibilityNodeInfo, val bounds: Rect, val label: String)
    private var nodes = emptyList<Entry>()
    private var windowGeneration = 0
    private var stateGeneration = -1
    private var captureError: String? = null
    private var deadline = 0.0
    private var commandGeneration = -1L
    @Volatile private var gestureActive = false
    private var gestureSequence = 0L
    private fun cancelActiveGesture() {
        if (!gestureActive) return
        val sequence = gestureSequence
        mainExecutor.execute {
            if (gestureActive && sequence == gestureSequence) {
                // A replacement cancels Android's prior gesture. Use positive coordinates beyond the display
                // so the replacement cannot hit an app or system control (negative paths are rejected by Android).
                val bounds = getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
                val cancel = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(
                    Path().apply { moveTo((bounds.right + 1).toFloat(), (bounds.bottom + 1).toFloat()) }, 0, 1)).build()
                dispatchGesture(cancel, null, null)
                gestureActive = false
            }
        }
    }
    override fun onServiceConnected() {
        inst = this
        if (Prefs.paired && Prefs.phoneArmed) {
            if (PhoneNotice.available(this)) { PhoneNotice.show(this); pull() } else stop(this)
        }
    }
    override fun onDestroy() { stop(this); inst = null; clearNodes(); super.onDestroy() }
    override fun onUnbind(intent: Intent?): Boolean { stop(this); inst = null; clearNodes(); return super.onUnbind(intent) }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) windowGeneration++
    }
    override fun onInterrupt() {}
    private fun ok(r: Any, img: String? = null) = JSONObject().put("ok", true).put("result", r).apply { if (img != null) put("image", img) }
    private fun fail(m: String) = JSONObject().put("ok", false).put("error", m)
    private fun clearNodes() { nodes.forEach { it.node.recycle() }; nodes = emptyList(); stateGeneration = -1 }
    private fun label(n: AccessibilityNodeInfo) = if (n.isPassword) "[password]" else
        listOfNotNull(n.text?.toString(), n.contentDescription?.toString()).filter { it.isNotBlank() }.distinct().joinToString(" ")
    private fun screenSize(): JSONObject {
        val b = getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
        return JSONObject().put("width", b.width()).put("height", b.height())
    }
    private fun allowed(): String? = when {
        !Prefs.phoneArmed -> "Phone control is off"
        !PhoneNotice.available(this) -> { stop(this); "Phone control stopped: enable the Live runs & connection notification channel" }
        getSystemService(KeyguardManager::class.java).isKeyguardLocked -> "Unlock the phone first; control cannot bypass the lock screen"
        !getSystemService(PowerManager::class.java).isInteractive -> "Turn on the phone screen first"
        else -> null
    }
    private fun commandAllowed(): String? = allowed() ?: when {
        commandGeneration != activation.get() -> "Phone control changed; command cancelled"
        deadline <= System.currentTimeMillis() / 1000.0 -> "Command expired"
        else -> null
    }
    suspend fun exec(c: JSONObject): JSONObject {
        commandGeneration = activation.get()
        deadline = c.optDouble("expires", 0.0)
        commandAllowed()?.let { return fail(it) }
        if (deadline <= System.currentTimeMillis() / 1000.0) return fail("Command expired")
        return when (c.str("action")) {
            "state" -> state(c.optBoolean("screenshot", true), c.optInt("max_nodes", 250), c.str("scope", "focused"), c.optBoolean("include_keyboard", false))
            "screenshot" -> shot()?.let { ok(JSONObject().put("screen", screenSize()), it) } ?: fail(captureError ?: "Screenshot unavailable")
            "tap", "long_press" -> tap(c)
            "swipe" -> if (listOf("x", "y", "x2", "y2").all { c.has(it) }) gesture(line(c.getInt("x"), c.getInt("y"), c.getInt("x2"), c.getInt("y2")), c.optLong("duration_ms", 300), "swiped") else fail("swipe needs x,y,x2,y2")
            "scroll" -> scroll(c)
            "type" -> type(c)
            "key" -> key(c.str("key"))
            "open_app" -> openApp(c.str("app"))
            "list_apps" -> listApps(c.str("query"))
            "list_windows" -> listWindows()
            "activate_window" -> activateWindow(c)
            "wait" -> { delay((c.optDouble("seconds", 1.0).coerceIn(0.0, 10.0) * 1000).toLong()); allowed()?.let { fail(it) } ?: state(false, 250) }
            else -> fail("unknown action")
        }
    }
    private suspend fun state(image: Boolean, cap: Int, scope: String = "focused", includeKeyboard: Boolean = false): JSONObject {
        if (scope !in listOf("focused", "all")) return fail("scope must be focused or all")
        clearNodes()
        val limit = cap.coerceIn(1, 1000)
        val entries = mutableListOf<Entry>()
        val a = JSONArray(); val metadata = JSONArray()
        // Walk borrows nodes. Retain separate dedupe copies until traversal ends: recycled
        // handles must never remain hash keys, nor be recycled twice when roots overlap.
        val seen = HashSet<AccessibilityNodeInfo>()
        val ownedRoots = mutableListOf<AccessibilityNodeInfo>()
        data class Root(val node: AccessibilityNodeInfo?, val meta: JSONObject, val ime: Boolean, val bar: Boolean, val rank: Int)
        val roots = mutableListOf<Root>()
        var visited = 0; var truncated = false
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (entries.size >= limit || visited >= 5000 || depth > 80) { truncated = true; return }
            if (n in seen) return
            seen.add(AccessibilityNodeInfo.obtain(n)); visited++
            // Toggle/text-change events can leave Android's cached node flags stale. Fetch the
            // authoritative control value before exposing it for post-action verification.
            if ((n.isCheckable || n.isEditable) && !n.refresh()) return
            val b = Rect(); n.getBoundsInScreen(b)
            val text = label(n)
            if (n.isVisibleToUser && !b.isEmpty && (text.isNotBlank() || n.isClickable || n.isEditable || n.isScrollable || n.isLongClickable || n.isCheckable)) {
                val index = entries.size
                entries.add(Entry(AccessibilityNodeInfo.obtain(n), Rect(b), text))
                a.put(JSONObject().put("index", index).put("role", n.className?.toString()).put("text", text)
                    .put("id", n.viewIdResourceName).put("package", n.packageName?.toString()).put("window", n.windowId)
                    .put("bounds", JSONArray(listOf(b.left, b.top, b.right, b.bottom)))
                    .put("clickable", n.isClickable).put("editable", n.isEditable).put("scrollable", n.isScrollable)
                    .put("checkable", n.isCheckable).put("checked", if (n.isCheckable) n.isChecked else JSONObject.NULL)
                    .put("selected", n.isSelected).put("focused", n.isFocused).put("long_clickable", n.isLongClickable)
                    .put("enabled", n.isEnabled).put("password", n.isPassword))
            }
            for (i in 0 until n.childCount) {
                if (entries.size >= limit || visited >= 5000) { truncated = true; break }
                n.getChild(i)?.let { child -> try { walk(child, depth + 1) } finally { child.recycle() } }
            }
        }
        val ws = windows
        var primary: Root? = null
        try {
            for (w in ws) {
                val root = w.root
                if (root != null) ownedRoots.add(root)
                val b = Rect(); w.getBoundsInScreen(b)
                val ime = w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
                val title = w.title?.toString().orEmpty()
                // Exclude only known bar windows, not active SystemUI panels (notification shade).
                val bar = w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM &&
                    (title.lowercase() in listOf("status bar", "navigation bar") ||
                        (!w.isActive && !w.isFocused && root?.viewIdResourceName?.substringAfterLast('/') in listOf("status_bar", "super_status_bar", "status_bar_root", "navigation_bar", "navigation_bar_frame")))
                val meta = JSONObject().put("id", w.id).put("title", title).put("package", root?.packageName?.toString() ?: JSONObject.NULL)
                    .put("active", w.isActive).put("focused", w.isFocused).put("type", w.type)
                    .put("bounds", JSONArray(listOf(b.left, b.top, b.right, b.bottom)))
                roots.add(Root(root, meta, ime, bar, when {
                    w.isActive -> 0
                    w.isFocused -> 1
                    w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION -> 2
                    else -> 3
                }))
            }
            primary = roots.filter { it.node != null && !it.ime && !it.bar && it.rank < 3 }.minByOrNull { it.rank }
            if (primary == null) rootInActiveWindow?.let { root ->
                ownedRoots.add(root)
                // Never bring an excluded IME/bar back via the active-root fallback.
                val known = roots.firstOrNull { it.meta.optInt("id") == root.windowId }
                if (known != null && !known.ime && !known.bar && known.rank < 3) {
                    val fallback = known.copy(node = root)
                    roots.remove(known); roots.add(fallback); primary = fallback
                } else if (known == null) {
                    val b = Rect(); root.getBoundsInScreen(b)
                    val fallback = Root(root, JSONObject().put("id", root.windowId).put("title", JSONObject.NULL)
                        .put("package", root.packageName?.toString() ?: JSONObject.NULL).put("active", true).put("focused", true)
                        .put("type", JSONObject.NULL).put("bounds", JSONArray(listOf(b.left, b.top, b.right, b.bottom))), false, false, 0)
                    roots.add(fallback); primary = fallback
                }
            }
            val ordered = roots.sortedWith(compareBy<Root> { if (it === primary) 0 else if (!it.ime && !it.bar) 1 else 2 }.thenBy { it.rank })
            for (root in ordered) {
                val included = root.node != null && (scope == "all" || !root.bar) && (!root.ime || includeKeyboard) &&
                    (scope == "all" || root === primary || root.ime)
                root.meta.put("included", included).put("foreground", root === primary)
                metadata.put(root.meta)
                if (included) walk(root.node!!, 0)
            }
            nodes = entries; stateGeneration = windowGeneration
        } catch (e: Exception) {
            entries.forEach { it.node.recycle() }
            throw e
        } finally {
            seen.forEach { it.recycle() }
            ownedRoots.forEach { it.recycle() }
            ws.forEach { it.recycle() }
        }
        val r = JSONObject().put("screen", screenSize()).put("elements", a).put("truncated", truncated)
            .put("windows", metadata).put("filters", JSONObject().put("scope", scope).put("include_keyboard", includeKeyboard)
                .put("exclude_system_bars", scope != "all").put("max_nodes", limit).put("foreground_window", primary?.meta?.optInt("id") ?: JSONObject.NULL))
        val img = if (image) shot() else null
        if (image && img == null) r.put("screenshot_error", captureError ?: "Unavailable; protected screens cannot be captured")
        return ok(r, img)
    }
    private fun indexed(c: JSONObject): AccessibilityNodeInfo {
        check(c.has("index")) { "Need an element index from state" }
        check(stateGeneration == windowGeneration) { "Screen changed; read state again" }
        val e = nodes.getOrNull(c.getInt("index")) ?: error("Unknown index; read state again")
        val n = e.node
        check(n.refresh() && n.isVisibleToUser && n.isEnabled) { "Element is stale or disabled; read state again" }
        val b = Rect(); n.getBoundsInScreen(b)
        check(b == e.bounds && label(n) == e.label) { "Element changed; read state again" }
        return n
    }
    private suspend fun tap(c: JSONObject): JSONObject {
        val long = c.str("action") == "long_press"
        val method = c.str("method", "auto")
        if (method !in listOf("auto", "gesture", "accessibility")) return fail("method must be auto, gesture or accessibility")
        val n = if (c.has("index")) indexed(c) else null
        if (n?.isPassword == true) return fail("Password fields must be filled by the user")
        if (method == "accessibility" && n == null) return fail("accessibility method needs an element index")
        val checkedBefore = if (n?.isCheckable == true) n.isChecked else null
        fun dispatched(out: JSONObject, used: String): JSONObject {
            out.optJSONObject("result")?.put("method", used)?.put("effect_verified", false)
                ?.put("checked_before", checkedBefore ?: JSONObject.NULL)?.put("next", "Read state again and verify the intended effect")
            return out
        }
        commandAllowed()?.let { return fail(it) }
        // OEM switches can accept ACTION_CLICK without toggling. Auto uses exactly one
        // physical tap for a short checkable target, never an AX click followed by a tap.
        val useAccessibility = n != null && method != "gesture" &&
            (method == "accessibility" || long || !n.isCheckable)
        if (useAccessibility) {
            if (n!!.performAction(if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK))
                return dispatched(after(if (long) "long-clicked" else "clicked"), "accessibility")
            if (method == "accessibility") return fail("App rejected accessibility click")
        }
        val b = Rect(); n?.getBoundsInScreen(b)
        val x = if (n != null) b.centerX() else c.optInt("x", -1)
        val y = if (n != null) b.centerY() else c.optInt("y", -1)
        return dispatched(gesture(line(x, y, x, y), c.optLong("duration_ms", if (long) 700 else 60), if (long) "long-pressed" else "tapped"), "gesture")
    }
    private fun line(x: Int, y: Int, x2: Int, y2: Int): Path {
        val size = screenSize()
        check(x in 0 until size.getInt("width") && x2 in 0 until size.getInt("width") && y in 0 until size.getInt("height") && y2 in 0 until size.getInt("height")) { "Coordinates outside screen" }
        return Path().apply { moveTo(x.toFloat(), y.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }
    }
    private suspend fun gesture(path: Path, ms: Long, note: String): JSONObject {
        commandAllowed()?.let { return fail(it) }
        if (ms !in 1..GestureDescription.getMaxGestureDuration() || ms + 2000 > (deadline * 1000 - System.currentTimeMillis())) return fail("Gesture duration exceeds the command deadline")
        val result = CompletableDeferred<Boolean>()
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, ms)).build()
        gestureActive = true
        val sequence = ++gestureSequence
        android.util.Log.d("HermesPhone", "Gesture started duration=$ms")
        val accepted = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { if (sequence == gestureSequence) gestureActive = false; result.complete(true) }
            override fun onCancelled(g: GestureDescription?) {
                if (sequence == gestureSequence) gestureActive = false
                android.util.Log.d("HermesPhone", "Gesture cancelled")
                result.complete(false)
            }
        }, null)
        if (!accepted) gestureActive = false
        return if (accepted && withTimeoutOrNull(ms + 2000) { result.await() } == true) after(note) else fail("Gesture cancelled or rejected")
    }
    private suspend fun after(note: String): JSONObject { clearNodes(); delay(250); commandAllowed()?.let { return fail(it) }; return ok(JSONObject().put("action", note).put("next", "Read state again")) }
    private suspend fun scroll(c: JSONObject): JSONObject {
        val direction = c.str("direction", "down")
        val n = if (c.has("index")) indexed(c) else nodes.firstOrNull { it.node.isScrollable && it.node.refresh() }?.node
        val act = when (direction) {
            "down" -> AccessibilityAction.ACTION_SCROLL_DOWN
            "up" -> AccessibilityAction.ACTION_SCROLL_UP
            "left" -> AccessibilityAction.ACTION_SCROLL_LEFT
            "right" -> AccessibilityAction.ACTION_SCROLL_RIGHT
            else -> return fail("Unknown scroll direction")
        }
        if (n != null && n.performAction(act.id)) return after("scrolled $direction")
        val b = Rect()
        if (n != null) n.getBoundsInScreen(b) else { val s = screenSize(); b.set(0, 0, s.getInt("width"), s.getInt("height")) }
        val x = b.centerX(); val y = b.centerY(); val dx = b.width() / 4; val dy = b.height() / 4
        return when (direction) {
            "down" -> gesture(line(x, y + dy, x, y - dy), 300, "scrolled down")
            "up" -> gesture(line(x, y - dy, x, y + dy), 300, "scrolled up")
            "left" -> gesture(line(x - dx, y, x + dx, y), 300, "scrolled left")
            else -> gesture(line(x + dx, y, x - dx, y), 300, "scrolled right")
        }
    }
    private suspend fun type(c: JSONObject): JSONObject {
        val focused = if (c.has("index")) null else rootInActiveWindow?.let { root -> try { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } finally { root.recycle() } }
        val n = if (c.has("index")) indexed(c) else focused ?: return fail("Focus an editable field or supply index")
        try {
            if (!n.isEditable || n.isPassword) return fail("Not an editable non-password field")
            if (!c.has("text")) return fail("type needs text")
            val text = (if (c.optBoolean("append")) n.text?.toString().orEmpty() else "") + c.getString("text")
            val b = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
            n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            if (!n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)) return fail("App rejected setting text")
            if (c.optBoolean("submit") && !n.performAction(AccessibilityAction.ACTION_IME_ENTER.id)) return fail("Text set, but app does not support IME enter")
            return after("text set")
        } finally { focused?.recycle() }
    }
    private suspend fun key(k: String): JSONObject {
        if (k == "enter") {
            val root = rootInActiveWindow ?: return fail("No active window")
            val n = try { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } finally { root.recycle() }
            if (n == null) return fail("No focused input")
            val pressed = try { !n.isPassword && n.performAction(AccessibilityAction.ACTION_IME_ENTER.id) } finally { n.recycle() }
            return if (pressed) after("enter") else fail("IME enter unavailable")
        }
        val action = when (k) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "power_dialog" -> GLOBAL_ACTION_POWER_DIALOG
            "lock" -> GLOBAL_ACTION_LOCK_SCREEN
            else -> return fail("Unknown key")
        }
        return if (performGlobalAction(action)) after(k) else fail("System action rejected")
    }
    private suspend fun activateWindow(c: JSONObject): JSONObject {
        if (c.has("app")) return openApp(c.str("app"))
        if (!c.has("window_id")) return fail("Supply window_id from list_windows or app")
        val ws = windows
        var pkg: String? = null
        ws.forEach { w ->
            if (w.id == c.getInt("window_id")) {
                val root = w.root
                try { pkg = root?.packageName?.toString() } finally { root?.recycle() }
            }
            w.recycle()
        }
        return pkg?.let { openApp(it) } ?: fail("Window no longer available; read list_windows again")
    }
    private fun listWindows(): JSONObject {
        val a = JSONArray()
        val ws = windows
        ws.forEach { w ->
            val b = Rect(); w.getBoundsInScreen(b)
            val root = w.root
            try {
                a.put(JSONObject().put("id", w.id).put("title", w.title?.toString()).put("package", root?.packageName?.toString())
                    .put("active", w.isActive).put("focused", w.isFocused).put("type", w.type)
                    .put("bounds", JSONArray(listOf(b.left, b.top, b.right, b.bottom))))
            } finally { root?.recycle(); w.recycle() }
        }
        return ok(a)
    }
    private fun apps() = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .distinctBy { it.activityInfo.packageName }.map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }.sortedBy { it.second.lowercase() }
    private fun listApps(q: String): JSONObject = ok(JSONArray(apps().filter { (pkg, name) -> pkg.contains(q, true) || name.contains(q, true) }.map { (pkg, name) -> JSONObject().put("package", pkg).put("label", name) }))
    private suspend fun openApp(app: String): JSONObject {
        if (app.isBlank()) return fail("Supply app package or exact label")
        val exact = apps().filter { it.first == app || it.second.equals(app, true) }
        if (exact.size != 1) return fail("App not found or ambiguous; use list_apps and the package name")
        val intent = packageManager.getLaunchIntentForPackage(exact.single().first) ?: return fail("App cannot be launched")
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return after("opened ${exact.single().first}")
    }
    private suspend fun shot(): String? {
        val result = CompletableDeferred<String?>(); captureError = null
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(s: ScreenshotResult) {
                val buffer = s.hardwareBuffer
                try {
                    val hardware = Bitmap.wrapHardwareBuffer(buffer, s.colorSpace) ?: error("Unable to read screenshot buffer")
                    val bitmap = hardware.copy(Bitmap.Config.ARGB_8888, false); hardware.recycle()
                    try {
                        val out = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
                        result.complete(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
                    } finally { bitmap.recycle() }
                } catch (e: Exception) { captureError = e.message; result.complete(null) } finally { buffer.close() }
            }
            override fun onFailure(code: Int) { captureError = "Android screenshot error $code (protected screens cannot be captured)"; result.complete(null) }
        })
        return withTimeoutOrNull(5000) { result.await() }
    }
}
