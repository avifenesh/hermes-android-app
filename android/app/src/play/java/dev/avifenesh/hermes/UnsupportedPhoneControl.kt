package dev.avifenesh.hermes

import android.content.Context

/** Source compatibility only: no Android service, command transport, executor or activation API.
 * SSE/FCM wake hints resolve to this inert implementation, never to the full source set.
 * The alias generates no PhoneService class in Play DEX.
 */
internal typealias PhoneService = UnsupportedPhoneControl

internal class UnsupportedPhoneControl private constructor() {
    companion object {
        val controlState = DisabledControlState
        fun onArmChanged() {
            // Clear stale local state without recursing once false; no server requests.
            if (Prefs.phoneArmed) Prefs.phoneArmed = false
        }
        fun pull() { onArmChanged() }
        @Suppress("UNUSED_PARAMETER")
        suspend fun disarm(c: Context) { onArmChanged() }
    }
}

/** Shared startup/preferences may assign this property; Play can never report itself armed. */
internal object DisabledControlState {
    var value: Boolean
        get() = false
        set(@Suppress("UNUSED_PARAMETER") requested) { UnsupportedPhoneControl.onArmChanged() }
}
