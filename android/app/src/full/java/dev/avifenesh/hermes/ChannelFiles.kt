package dev.avifenesh.hermes

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

fun openFile(c: Context, f: File, mime: String?) {
    val u = FileProvider.getUriForFile(c, c.packageName + ".files", f)
    val apk = mime == APK_MIME || f.name.endsWith(".apk", true)
    if (apk && !c.packageManager.canRequestPackageInstalls()) {
        // Without this per-app grant the installer closes silently, which looks like "tapping does nothing".
        Ui.say("Allow \"Install unknown apps\" for Hermes, then tap the file again")
        c.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + c.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return
    }
    val i = Intent(Intent.ACTION_VIEW).setDataAndType(u, if (apk) APK_MIME else mime ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { c.startActivity(if (apk) i else Intent.createChooser(i, f.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Ui.say("No app can open ${f.name}. Use the save button to put it in Downloads.") }
}
