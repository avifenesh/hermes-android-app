package dev.avifenesh.hermes

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Test

class FullCameraTest {
    @Test fun cameraStillRequiresTheDeclaredGrant() {
        assertEquals(listOf(Manifest.permission.CAMERA), CAMERA_PERMISSIONS)
    }
}
