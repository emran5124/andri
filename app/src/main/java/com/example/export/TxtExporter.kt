package com.example.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.example.db.AppSettings
import java.io.File
import java.io.FileOutputStream

class TxtExporter {

    fun compileAndFormatSummaries(
        summaries: List<String>,
        settings: AppSettings
    ): String {
        val template = settings.compiledSeparatorTemplate
        return summaries.mapIndexed { idx, summary ->
            template
                .replace("{index}", (idx + 1).toString())
                .replace("{total}", summaries.size.toString())
                .replace("{summary}", summary)
        }.joinToString("\n\n")
    }

    fun saveFileToDownloads(
        context: Context,
        fileName: String,
        content: String,
        mimeType: String
    ): Uri? {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                try {
                    resolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.write(content.toByteArray())
                        outputStream.flush()
                    }
                    return uri
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } else {
            try {
                @Suppress("DEPRECATION")
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) {
                    downloadsDir.mkdirs()
                }
                val file = File(downloadsDir, fileName)
                FileOutputStream(file).use { out ->
                    out.write(content.toByteArray())
                    out.flush()
                }
                return Uri.fromFile(file)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return null
    }
}
