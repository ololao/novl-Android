/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.util

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.wireguard.android.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

class DownloadsFileSaver(private val context: ComponentActivity) {
    private lateinit var activityResult: ActivityResultLauncher<String>
    private lateinit var futureGrant: CompletableDeferred<Boolean>

    init {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            futureGrant = CompletableDeferred()
            activityResult = context.registerForActivityResult(ActivityResultContracts.RequestPermission()) { ret -> futureGrant.complete(ret) }
        }
    }

    /**
     * Creates a new [DownloadsFile] for writing. The data is first written to a temporary
     * location and only replaces any previously-existing file once [DownloadsFile.commit] is
     * called, so a failed write never destroys the previous version (F11).
     */
    suspend fun save(name: String, mimeType: String?, overwriteExisting: Boolean): DownloadsFile? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        withContext(Dispatchers.IO) {
            val contentResolver = context.contentResolver

            // Remember any existing file so it can be replaced only after a successful write.
            var oldUri: Uri? = null
            if (overwriteExisting) {
                contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaColumns._ID),
                    String.format("%s = ?", MediaColumns.DISPLAY_NAME),
                    arrayOf(name), null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        oldUri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0))
                    }
                }
            }

            val contentValues = ContentValues()
            contentValues.put(MediaColumns.DISPLAY_NAME, "$name.part")
            contentValues.put(MediaColumns.MIME_TYPE, mimeType)
            val contentUri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                ?: throw IOException(context.getString(R.string.create_downloads_file_error))
            val contentStream = contentResolver.openOutputStream(contentUri)
                ?: throw IOException(context.getString(R.string.create_downloads_file_error))

            DownloadsFile(contentResolver, contentStream, name, contentUri, oldUri, null, null)
        }
    } else {
        withContext(Dispatchers.Main.immediate) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                activityResult.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                val granted = futureGrant.await()
                if (!granted) {
                    futureGrant = CompletableDeferred()
                    return@withContext null
                }
            }
            @Suppress("DEPRECATION") val path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            withContext(Dispatchers.IO) {
                if (!path.isDirectory && !path.mkdirs())
                    throw IOException(context.getString(R.string.create_output_dir_error))
                val finalFile = File(path, name)
                val tmpFile = File(path, "$name.part")
                DownloadsFile(context.contentResolver, FileOutputStream(tmpFile), name, null, null, finalFile, tmpFile)
            }
        }
    }

    /**
     * A file in the Downloads directory that is being written. Callers must write to
     * [outputStream], then call [commit] on success or [delete] on failure.
     */
    class DownloadsFile(
        private val contentResolver: android.content.ContentResolver,
        val outputStream: OutputStream,
        val fileName: String,
        private val newUri: Uri?,
        private val oldUri: Uri?,
        private val oldFile: File?,
        private val tmpFile: File?,
    ) {
        /** Removes the partially-written temporary file after a failed write. */
        suspend fun delete() = withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                newUri?.let { contentResolver.delete(it, null, null) }
            else
                tmpFile?.delete()
        }

        /** Replaces any previous version of the file with the newly-written content. */
        suspend fun commit() = withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Remove the old version, then promote the temporary name to the final name.
                oldUri?.let { contentResolver.delete(it, null, null) }
                newUri?.let {
                    val values = ContentValues().apply { put(MediaColumns.DISPLAY_NAME, fileName) }
                    contentResolver.update(it, values, null, null)
                }
            } else {
                val target = oldFile ?: tmpFile?.parentFile?.let { File(it, fileName) }
                if (target != null && target.exists())
                    target.delete()
                tmpFile?.renameTo(target)
            }
        }
    }
}
