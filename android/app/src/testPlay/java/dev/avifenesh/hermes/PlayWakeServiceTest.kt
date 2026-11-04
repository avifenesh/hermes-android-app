package dev.avifenesh.hermes

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PlayWakeServiceTest {
    @Before fun englishStrings() = TestStrings.install()

    @Test fun alarmRunsAsSystemExemptedOnlyWithExactAlarmAccess() {
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED, WAKE_FGS_TYPE)
        assertTrue(wakeServiceAllowed(canExact = true))
        assertFalse("no exact-alarm access: systemExempted conditions do not hold, never start the service", wakeServiceAllowed(canExact = false))
        assertTrue(str(WAKE_EXACT_OFF).contains("can't ring"))
    }
}
