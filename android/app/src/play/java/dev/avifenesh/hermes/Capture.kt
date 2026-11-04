package dev.avifenesh.hermes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/** Play declares no CAMERA permission. Every photo comes from the camera app through ActivityResultContracts
 * .TakePicture (MediaStore.ACTION_IMAGE_CAPTURE), which needs no grant while CAMERA is undeclared; the pairing QR
 * scanner runs in Google Play services without one. There is no in-app camera. */
internal val CAMERA_PERMISSIONS: List<String> = emptyList()

/** Opens the camera app directly: nothing to ask for, so [denied] is never called. */
@Composable
internal fun rememberCameraStart(open: () -> Unit, @Suppress("UNUSED_PARAMETER") denied: () -> Unit): () -> Unit {
    val current by rememberUpdatedState(open)
    return { current() }
}
