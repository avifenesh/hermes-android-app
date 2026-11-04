package dev.avifenesh.hermes

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FullWakeServiceTest {
    @Test fun alarmKeepsMediaPlayback() {
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK, WAKE_FGS_TYPE)
        assertTrue(wakeServiceAllowed(canExact = false))
    }
}
