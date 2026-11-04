package dev.avifenesh.hermes

import android.Manifest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayCameraTest {
    @Test fun cameraCapturesNeverAskForTheCameraPermission() {
        // ACTION_IMAGE_CAPTURE needs no grant when CAMERA is undeclared, and throws when it is declared but denied.
        assertTrue("chat and Wake photo capture open the camera app directly", CAMERA_PERMISSIONS.isEmpty())
        assertFalse("the Wake optional-proof list never asks for CAMERA", Manifest.permission.CAMERA in CAMERA_PERMISSIONS)
    }
}
