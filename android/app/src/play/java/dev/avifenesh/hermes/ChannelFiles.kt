package dev.avifenesh.hermes

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.util.zip.ZipFile

/** Block installer dispatch even for an APK with a misleading attachment name/type.
 * Saving/transferring attachments remains supported; this edition never launches an installer.
 */
internal fun isInstallablePackage(f: File, mime: String?): Boolean =
    mime?.substringBefore(';')?.trim()?.equals(APK_MIME, ignoreCase = true) == true ||
        f.extension.equals("apk", ignoreCase = true) ||
        runCatching { ZipFile(f).use { it.getEntry("AndroidManifest.xml") != null } }.getOrDefault(false)

fun openFile(c: Context, f: File, mime: String?) {
    if (isInstallablePackage(f, mime)) {
        Ui.say("Installing APK attachments is unavailable in this Play edition. You can still save the file to Downloads.")
        return
    }
    val u = FileProvider.getUriForFile(c, c.packageName + ".files", f)
    val i = Intent(Intent.ACTION_VIEW).setDataAndType(u, mime ?: "*/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { c.startActivity(Intent.createChooser(i, f.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Ui.say("No app can open ${f.name}. Use the save button to put it in Downloads.") }
}
