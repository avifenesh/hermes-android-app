package dev.avifenesh.hermes

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** Runtime grants the camera features ask for. Full declares CAMERA, and Android then requires the grant even for
 * the system camera intent (MediaStore.ACTION_IMAGE_CAPTURE throws SecurityException while it is denied). */
internal val CAMERA_PERMISSIONS: List<String> = listOf(Manifest.permission.CAMERA)

/** Returns the action that opens the camera app: asks for CAMERA first, then [open]; [denied] if refused. */
@Composable
internal fun rememberCameraStart(open: () -> Unit, denied: () -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) open() else denied() }
    return {
        if (ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) open()
        else ask.launch(Manifest.permission.CAMERA)
    }
}
