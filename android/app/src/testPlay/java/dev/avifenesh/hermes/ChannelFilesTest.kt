package dev.avifenesh.hermes

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChannelFilesTest {
    @get:Rule val temp = TemporaryFolder(File(System.getenv("TMPDIR") ?: System.getProperty("java.io.tmpdir")))

    @Test fun blocksApkByNameOrMime() {
        val apk = temp.newFile("download.APK")
        assertTrue(isInstallablePackage(apk, "application/octet-stream"))
        val unknown = temp.newFile("unknown.bin")
        assertTrue(isInstallablePackage(unknown, "APPLICATION/VND.ANDROID.PACKAGE-ARCHIVE; charset=binary"))
    }

    @Test fun blocksRenamedAndroidPackage() {
        val file = temp.newFile("innocent.zip")
        ZipOutputStream(file.outputStream()).use {
            it.putNextEntry(ZipEntry("AndroidManifest.xml"))
            it.write(byteArrayOf(1, 2, 3))
            it.closeEntry()
        }
        assertTrue(isInstallablePackage(file, "application/zip"))
    }

    @Test fun preservesOrdinaryFilesAndArchives() {
        val text = temp.newFile("note.txt").apply { writeText("hello") }
        assertFalse(isInstallablePackage(text, "text/plain"))
        val archive = temp.newFile("notes.zip")
        ZipOutputStream(archive.outputStream()).use {
            it.putNextEntry(ZipEntry("note.txt"))
            it.write("hello".toByteArray())
            it.closeEntry()
        }
        assertFalse(isInstallablePackage(archive, "application/zip"))
    }
}
