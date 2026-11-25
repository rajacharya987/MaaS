package com.wiperspace.maas.engine

import android.R
import android.content.Context
import android.view.inputmethod.InsertGesture
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
data class StorageItem(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val isDirectory: Boolean,
    val lastModified: Long,
    val childCount: Int = 0
)
class MaaSStorageManager(private val context: Context) {
    val rootDir: File = File(context.filesDir,"maas_root").apply { if(!exists())
    mkdirs() }
    val sitesDir: File = File(rootDir, "sites").apply {if(!exists()) mkdirs()}
    val databasesDir: File = File (rootDir, "databases").apply{ if (!exists()) mkdirs()  }

    val logsDir: File = File(rootDir,"logs").apply { if (!exists()) mkdirs() }
    val backupsDir : File = File(rootDir, "backups").apply { if (!exists()) mkdirs() }
    init {
        createDefaultStarterSite()
    }
    private fun createDefaultStarterSite() {
        val defaultSite = File(sitesDir, "default")
        if (!defaultSite.exists()) {
            defaultSite.mkdirs()
            val indexHtml = File(defaultSite, "index.html")
            indexHtml.writeText(
            """
                <h1> This works! hey hackclub!!!</h1>
                """.trimIndent()
                )
        }
    }

    fun listDirectory(dir: File): List<StorageItem>
    {
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        return (dir.listFiles() ?: emptyArray()).map { file ->
            StorageItem(
                name = file.name,
                path = file.absolutePath,
                sizeBytes = if (file.isDirectory) getFolderSize(file) else file.length(),
                isDirectory = file.isDirectory,
                lastModified = file.lastModified(),
                childCount = if (file.isDirectory) (file.listFiles()?.size ?: 0) else 0
            )
        }.sortedWith(
            compareByDescending<StorageItem>
        { it.isDirectory }.thenBy { it.name.lowercase(Locale.ROOT) })
    }
    fun createBackupSnapshot(): File {
        val timestamp = SimpleDateFormat(
            "yyyyMMdd_HHmmss",
            Locale.getDefault()
        ).format(Date())
        val backupFile = File(backupsDir, "maas_backup_$timestamp.zip")
        ZipOutputStream(FileOutputStream(backupFile)).use { zos ->
            zipSubDir(sitesDir, "sites", zos)
            zipSubDir(databasesDir, "database", zos)
        }
        return backupFile
    }
    private fun zipSubDir(dir: File, baseName: String, zos: ZipOutputStream) {
        val files = dir.listFiles() ?: return
        val buffer = ByteArray(4096)
        for (f in files) {
            if (f.isDirectory) {
                zipSubDir(f,"$baseName/${f.name}", zos)
            } else {
                FileInputStream(f).use {fis ->
                    zos.putNextEntry(ZipEntry("$baseName/${f.name}"))
                    var length: Int
                    while ( fis.read(buffer).also {length = it} > 0) {
                        zos.write(buffer,0,length)
                    }
                    zos.closeEntry()
                }
            }
        }
    }

    private fun getFolderSize(file: File): Long {
    var size = 0L
    val children = file.listFiles() ?: return 0L
    for (child in children) {
        size += if (child.isDirectory) getFolderSize(child) else child.length()
        }
        return size
    }
    fun getStorageSummary(): Map<String, Long> {
        return mapOf (
            "sites" to getFolderSize(sitesDir),
            "databases" to getFolderSize(databasesDir),
            "backups" to getFolderSize(backupsDir),
            "logs" to getFolderSize(logsDir)
        )
    }


}












