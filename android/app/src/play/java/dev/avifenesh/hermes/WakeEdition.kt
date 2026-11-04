package dev.avifenesh.hermes

import android.annotation.SuppressLint
import android.content.pm.ServiceInfo
import androidx.annotation.StringRes

/**
 * Foreground service type of the alarm sound. Android documents systemExempted for apps holding SCHEDULE_EXACT_ALARM
 * or USE_EXACT_ALARM that use the foreground service to continue alarms in the background, and Play's foreground
 * service policy exempts the type. API 34+ enforces it in startForeground. On API 31-33 the type does not exist:
 * ServiceCompat masks it to "no type" and those platforms start the service without type rules.
 */
@SuppressLint("InlinedApi")
internal const val WAKE_FGS_TYPE = ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED

/**
 * The alarm service may start only while exact-alarm access is granted, on every API level, so systemExempted is
 * used exactly when its documented condition holds. API 34+ would refuse the type in startForeground anyway, and a
 * refused startForeground after startForegroundService crashes the app. Revoking the access stops the app and
 * cancels its exact alarms; the grant broadcast re-arms them (WakeBootReceiver).
 */
internal fun wakeServiceAllowed(canExact: Boolean): Boolean = canExact

/** Subtitle of the "Exact alarms are off" row: here alarms from the gateway cannot ring without the access either. */
@StringRes internal val WAKE_EXACT_OFF = R.string.alarm_exact_off_cant_ring
