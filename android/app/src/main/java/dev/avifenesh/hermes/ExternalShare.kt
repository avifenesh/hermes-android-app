package dev.avifenesh.hermes

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process

/** External SEND streams must come from another app with an explicit readable URI grant. */
object ExternalShare {
    fun validate(c: Context, uri: Uri, flags: Int) {
        require(uri.scheme == "content") { str(R.string.share_not_content) }
        val authority = uri.authority ?: throw IllegalArgumentException(str(R.string.share_no_provider))
        require(authority.substringAfter('@') != c.packageName + ".files") { str(R.string.share_private_file, APP_NAME) }
        val provider = c.packageManager.resolveContentProvider(authority, 0)
            ?: throw IllegalArgumentException(str(R.string.share_provider_unavailable))
        require(provider.applicationInfo.uid != Process.myUid()) { str(R.string.share_private_file, APP_NAME) }
        require(flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0 &&
            c.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            str(R.string.share_no_grant)
        }
    }
}
