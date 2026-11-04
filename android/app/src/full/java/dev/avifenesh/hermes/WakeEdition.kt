package dev.avifenesh.hermes

import android.content.pm.ServiceInfo
import androidx.annotation.StringRes

/** Foreground service type of the alarm sound. Full keeps mediaPlayback. */
internal const val WAKE_FGS_TYPE = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK

/** Whether the alarm service may start. mediaPlayback has no exact-alarm prerequisite. */
@Suppress("UNUSED_PARAMETER")
internal fun wakeServiceAllowed(canExact: Boolean): Boolean = true

/** Subtitle of the "Exact alarms are off" row. */
@StringRes internal val WAKE_EXACT_OFF = R.string.alarm_exact_off_sub
