/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.configStore

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.wireguard.android.R
import com.wireguard.config.BadConfigException
import com.wireguard.config.Config
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * Configuration store that uses a `wg-quick`-style file for each configured tunnel.
 */
class FileConfigStore(private val context: Context) : ConfigStore {
    @Throws(IOException::class)
    override fun create(name: String, config: Config): Config {
        Log.d(TAG, "Creating configuration for tunnel $name")
        val file = fileFor(name)
        if (!file.createNewFile())
            throw IOException(context.getString(R.string.config_file_exists_error, file.name))
        writeAtomically(file, config.toWgQuickString())
        return config
    }

    @Throws(IOException::class)
    override fun delete(name: String) {
        Log.d(TAG, "Deleting configuration for tunnel $name")
        val file = fileFor(name)
        if (!file.delete())
            throw IOException(context.getString(R.string.config_delete_error, file.name))
    }

    override fun enumerate(): Set<String> {
        return context.fileList()
            .filter { it.endsWith(".conf") }
            .map { it.substring(0, it.length - ".conf".length) }
            .toSet()
    }

    private fun fileFor(name: String): File {
        return File(context.filesDir, "$name.conf")
    }

    /**
     * Writes [contents] to [file] atomically via a temporary file followed by a rename, so that a
     * crash or error mid-write can never leave the existing configuration truncated or empty.
     */
    @Throws(IOException::class)
    private fun writeAtomically(file: File, contents: String) {
        val atomicFile = AtomicFile(file)
        var stream: FileOutputStream? = null
        try {
            stream = atomicFile.startWrite()
            stream.write(contents.toByteArray(StandardCharsets.UTF_8))
            atomicFile.finishWrite(stream)
        } catch (e: IOException) {
            if (stream != null)
                atomicFile.failWrite(stream)
            throw e
        }
    }

    @Throws(BadConfigException::class, IOException::class)
    override fun load(name: String): Config {
        FileInputStream(fileFor(name)).use { stream -> return Config.parse(stream) }
    }

    @Throws(IOException::class)
    override fun rename(name: String, replacement: String) {
        Log.d(TAG, "Renaming configuration for tunnel $name to $replacement")
        val file = fileFor(name)
        val replacementFile = fileFor(replacement)
        if (!replacementFile.createNewFile()) throw IOException(context.getString(R.string.config_exists_error, replacement))
        if (!file.renameTo(replacementFile)) {
            if (!replacementFile.delete()) Log.w(TAG, "Couldn't delete marker file for new name $replacement")
            throw IOException(context.getString(R.string.config_rename_error, file.name))
        }
    }

    @Throws(IOException::class)
    override fun save(name: String, config: Config): Config {
        Log.d(TAG, "Saving configuration for tunnel $name")
        val file = fileFor(name)
        if (!file.isFile)
            throw FileNotFoundException(context.getString(R.string.config_not_found_error, file.name))
        writeAtomically(file, config.toWgQuickString())
        return config
    }

    companion object {
        private const val TAG = "WireGuard/FileConfigStore"
    }
}
