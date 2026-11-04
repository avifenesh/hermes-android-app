package dev.avifenesh.hermes

import android.content.pm.ServiceInfo

/** Foreground service type of the alarm sound. Full keeps mediaPlayback. */
internal const val WAKE_FGS_TYPE = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK

/** Whether the alarm service may start. mediaPlayback has no exact-alarm prerequisite. */
@Suppress("UNUSED_PARAMETER")
internal fun wakeServiceAllowed(canExact: Boolean): Boolean = true

internal const val WAKE_EXACT_OFF = "Local alarms are not scheduled. Allow exact alarms before relying on Wake."
