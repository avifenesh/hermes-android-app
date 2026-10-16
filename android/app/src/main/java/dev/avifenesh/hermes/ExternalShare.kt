package dev.avifenesh.hermes

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process

/** External SEND streams must come from another app with an explicit readable URI grant. */
object ExternalShare {
    fun validate(c: Context, uri: Uri, flags: Int) {
        require(uri.scheme == "content") { "Share a file from another app, or use the file picker." }
        val authority = uri.authority ?: throw IllegalArgumentException("The shared file has no provider.")
        require(authority.substringAfter('@') != c.packageName + ".files") { "Hermes-private files cannot be shared into the app." }
        val provider = c.packageManager.resolveContentProvider(authority, 0)
            ?: throw IllegalArgumentException("The shared file provider is unavailable.")
        require(provider.applicationInfo.uid != Process.myUid()) { "Hermes-private files cannot be shared into the app." }
        require(flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0 &&
            c.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            "The sending app did not grant access. Share the file again, or use the file picker."
        }
    }
}
