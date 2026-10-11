/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.updater

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.wireguard.android.Application
import com.wireguard.android.BuildConfig
import com.wireguard.android.activity.MainActivity
import com.wireguard.android.util.UserKnobs
import com.wireguard.android.util.applicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.InvalidParameterException
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.max
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

object Updater {
    private const val TAG = "Novl/Updater"
    private const val DEFAULT_UPDATE_URL = "https://codeberg.org/ooopev/novl-android/raw/branch/main/ota/update.json"
    private const val RELEASES_API_URL = "https://codeberg.org/api/v1/repos/ooopev/novl-android/releases/latest"
    private val CURRENT_VERSION by lazy { Version(BuildConfig.VERSION_NAME) }

    private val updaterScope = CoroutineScope(Job() + Dispatchers.IO)

    private fun installer(context: Context): String = try {
        val packageName = context.packageName
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(packageName).installingPackageName ?: ""
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(packageName) ?: ""
        }
    } catch (_: Throwable) {
        ""
    }

    fun installerIsGooglePlay(context: Context): Boolean = installer(context) == "com.android.vending"

    sealed class Progress {
        object Complete : Progress()
        class Available(val version: String, val versionId: String) : Progress() {
            fun update() {
                applicationScope.launch {
                    UserKnobs.setUpdaterNewerVersionConsented(versionId)
                }
            }
        }

        object Rechecking : Progress()
        class Downloading(val bytesDownloaded: ULong, val bytesTotal: ULong) : Progress()
        object Installing : Progress()
        class NeedsUserIntervention(val intent: Intent, private val id: Int) : Progress() {

            private suspend fun installerActive(): Boolean {
                if (mutableState.firstOrNull() != this@NeedsUserIntervention)
                    return true
                try {
                    if (Application.get().packageManager.packageInstaller.getSessionInfo(id)?.isActive == true)
                        return true
                } catch (_: SecurityException) {
                    return true
                }
                return false
            }

            fun markAsDone() {
                applicationScope.launch {
                    if (installerActive())
                        return@launch
                    delay(7.seconds)
                    if (installerActive())
                        return@launch
                    emitProgress(Failure(Exception("Ignored by user")))
                }
            }
        }

        class Failure(val error: Throwable) : Progress() {
            fun retry() {
                updaterScope.launch {
                    downloadAndUpdateWrapErrors()
                }
            }
        }

        class Corrupt(private val betterFile: String?) : Progress() {
            val downloadUrl: String
                get() = DEFAULT_UPDATE_URL
        }
    }

    sealed class CheckResult {
        class Available(val version: String, val changelog: String?) : CheckResult()
        object UpToDate : CheckResult()
        class Error(val message: String) : CheckResult()
    }

    private val mutableState = MutableStateFlow<Progress>(Progress.Complete)
    val state = mutableState.asStateFlow()

    private suspend fun emitProgress(progress: Progress, force: Boolean = false) {
        if (force || mutableState.firstOrNull()?.javaClass != progress.javaClass)
            mutableState.emit(progress)
    }

    private class Sha256Digest(hex: String) {
        val bytes: ByteArray

        init {
            val clean = hex.trim().lowercase()
            if (clean.length != 64)
                throw InvalidParameterException("SHA256 hashes must be 64 hex characters (32 bytes)")
            bytes = clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    class Version(version: String) : Comparable<Version> {
        val parts: ULongArray

        init {
            val strParts = version.split(".")
            if (strParts.isEmpty())
                throw InvalidParameterException("Version has no parts")
            parts = ULongArray(strParts.size)
            for (i in parts.indices) {
                val clean = strParts[i].split("-")[0]
                parts[i] = clean.toULongOrNull() ?: 0UL
            }
        }

        override fun toString(): String {
            return parts.joinToString(".")
        }

        override fun compareTo(other: Version): Int {
            for (i in 0 until max(parts.size, other.parts.size)) {
                val lhsPart = if (i < parts.size) parts[i] else 0UL
                val rhsPart = if (i < other.parts.size) other.parts[i] else 0UL
                if (lhsPart > rhsPart)
                    return 1
                else if (lhsPart < rhsPart)
                    return -1
            }
            return 0
        }
    }

    private class Update(
        val apkUrl: String,
        val version: Version,
        val versionCode: Int,
        val hash: Sha256Digest?,
        val changelog: String?
    )

    private fun isNewer(update: Update): Boolean {
        if (update.versionCode > 0 && BuildConfig.VERSION_CODE > 0) {
            if (update.versionCode > BuildConfig.VERSION_CODE) return true
            if (update.versionCode < BuildConfig.VERSION_CODE) return false
        }
        return update.version > CURRENT_VERSION
    }

    /**
     * Encodes the full update identifier (version name plus version code) so that a release with a
     * higher version code but an unchanged version name is still detected as newer (F14).
     */
    private fun encodeVersion(name: String, code: Int): String =
        if (code > 0) "$name#$code" else name

    private fun versionDisplayName(id: String): String = id.substringBefore('#')

    /** Whether the stored update identifier is newer than the currently installed build. */
    private fun isStoredNewer(id: String): Boolean {
        val name = id.substringBefore('#')
        val code = id.substringAfter('#', "").toIntOrNull() ?: 0
        if (code > 0 && BuildConfig.VERSION_CODE > 0) {
            if (code > BuildConfig.VERSION_CODE) return true
            if (code < BuildConfig.VERSION_CODE) return false
        }
        return Version(name) > CURRENT_VERSION
    }

    private suspend fun getUpdateUrls(): List<String> {
        val custom = UserKnobs.customUpdateUrl.firstOrNull()?.trim()
        if (!custom.isNullOrBlank()) {
            return listOf(custom)
        }
        return listOf(DEFAULT_UPDATE_URL, RELEASES_API_URL)
    }

    private suspend fun checkForUpdates(): Update? {
        val urls = getUpdateUrls()
        var lastError: Throwable? = null

        for (urlString in urls) {
            try {
                Log.d(TAG, "Checking update manifest from $urlString")
                val connection = URL(urlString).openConnection() as HttpURLConnection
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                connection.setRequestProperty("User-Agent", Application.USER_AGENT)
                connection.setRequestProperty("Accept", "application/json")
                connection.connect()

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw IOException("HTTP ${connection.responseCode} ${connection.responseMessage}")
                }

                val jsonStr = connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                val json = JSONObject(jsonStr)

                if (json.has("tag_name") && json.has("assets")) {
                    val tag = json.getString("tag_name")
                    val versionName = tag.removePrefix("v").trim()
                    val assets = json.optJSONArray("assets")
                    var apkUrl: String? = null
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            val name = asset.optString("name", "")
                            if (name.endsWith(".apk")) {
                                apkUrl = asset.optString("browser_download_url")
                                break
                            }
                        }
                    }
                    if (apkUrl.isNullOrEmpty()) {
                        throw IOException("No APK found in release assets")
                    }
                    val changelog = if (json.has("body")) json.optString("body") else null

                    return Update(
                        apkUrl = apkUrl,
                        version = Version(versionName),
                        versionCode = 0,
                        hash = null,
                        changelog = changelog
                    )
                }

                val versionName = json.getString("version_name")
                val versionCode = json.optInt("version_code", -1)
                var apkUrl = json.getString("apk_url")
                if (!apkUrl.startsWith("http://") && !apkUrl.startsWith("https://")) {
                    apkUrl = URL(URL(urlString), apkUrl).toString()
                }
                val sha256 = json.optString("sha256", "").trim()
                val hash = if (sha256.length == 64) Sha256Digest(sha256) else null
                val changelog = if (json.has("changelog")) json.optString("changelog") else null

                return Update(
                    apkUrl = apkUrl,
                    version = Version(versionName),
                    versionCode = versionCode,
                    hash = hash,
                    changelog = changelog
                )
            } catch (e: Throwable) {
                lastError = e
                Log.w(TAG, "Update check failed for $urlString: ${e.message}")
            }
        }

        if (lastError != null)
            throw lastError
        return null
    }

    private suspend fun downloadAndUpdate() = withContext(Dispatchers.IO) {
        val context = Application.get().applicationContext
        val receiver = InstallReceiver()
        var handedOff = false

        val pendingIntent = withContext(Dispatchers.Main) {
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(receiver.sessionId),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            PendingIntent.getBroadcast(
                context,
                0,
                Intent(receiver.sessionId).setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
        }

        try {
            emitProgress(Progress.Rechecking)
            val update = checkForUpdates()
            if (update == null || !isNewer(update)) {
                emitProgress(Progress.Complete)
                return@withContext
            }

            emitProgress(Progress.Downloading(0UL, 0UL), true)
            val connection = URL(update.apkUrl).openConnection() as HttpURLConnection
            connection.setRequestProperty("User-Agent", Application.USER_AGENT)
            connection.connectTimeout = 15000
            connection.readTimeout = 60000
            connection.connect()
            if (connection.responseCode != HttpURLConnection.HTTP_OK)
                throw IOException("Update download failed: HTTP ${connection.responseCode} ${connection.responseMessage}")

            var downloadedByteLen: ULong = 0UL
            val totalByteLen = connection.contentLengthLong.let { if (it > 0) it.toULong() else 0UL }
            val fileBytes = ByteArray(1024 * 32 /* 32 KiB */)
            val digest = MessageDigest.getInstance("SHA-256")
            emitProgress(Progress.Downloading(downloadedByteLen, totalByteLen), true)

            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            params.setAppPackageName(context.packageName) /* Enforces updates; disallows new apps. */

            val sessionId = installer.createSession(params)
            val session = installer.openSession(sessionId)
            try {
                val installDest = session.openWrite(receiver.sessionId, 0, -1)

                installDest.use { dest ->
                    connection.inputStream.use { src ->
                        while (true) {
                            val readLen = src.read(fileBytes)
                            if (readLen <= 0)
                                break

                            digest.update(fileBytes, 0, readLen)
                            dest.write(fileBytes, 0, readLen)

                            downloadedByteLen += readLen.toUInt()
                            emitProgress(Progress.Downloading(downloadedByteLen, totalByteLen), true)

                            if (downloadedByteLen >= 1024UL * 1024UL * 100UL /* 100 MiB */)
                                throw IOException("Update APK too large (> 100 MiB)")
                        }
                    }
                }

                emitProgress(Progress.Installing)
                val calculatedHash = digest.digest()
                if (update.hash != null && !calculatedHash.contentEquals(update.hash.bytes)) {
                    val actualHex = calculatedHash.joinToString("") { "%02x".format(it) }
                    throw SecurityException("Hash mismatch! Calculated: $actualHex")
                }
                session.commit(pendingIntent.intentSender)
                handedOff = true
            } finally {
                // On any failure before a successful commit, abandon the install session so it
                // cannot linger (F17).
                if (!handedOff) {
                    try { session.abandon() } catch (_: Throwable) {}
                }
                try { session.close() } catch (_: Throwable) {}
            }
        } finally {
            // The receiver is only kept registered after a successful commit, when the installer
            // will deliver the result. All other exit paths must release it (F17).
            if (!handedOff) {
                try { context.unregisterReceiver(receiver) } catch (_: Throwable) {}
            }
        }
    }

    private var updating = false
    private var failureCount = 0

    private suspend fun downloadAndUpdateWrapErrors() {
        if (updating)
            return
        updating = true
        try {
            downloadAndUpdate()
            failureCount = 0
        } catch (e: Throwable) {
            Log.e(TAG, "Update failure", e)
            failureCount++
            if (failureCount >= 5) {
                UserKnobs.setUpdaterNewerVersionConsented(null)
                failureCount = 0
            }
            emitProgress(Progress.Failure(e))
        } finally {
            updating = false
        }
    }

    private class InstallReceiver : BroadcastReceiver() {
        val sessionId = UUID.randomUUID().toString()

        override fun onReceive(context: Context, intent: Intent) {
            if (sessionId != intent.action)
                return

            when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE_INVALID)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, 0)
                    val userIntervention = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    if (userIntervention != null) {
                        applicationScope.launch {
                            emitProgress(Progress.NeedsUserIntervention(userIntervention, id))
                        }
                    } else {
                        Log.e(TAG, "STATUS_PENDING_USER_ACTION received without EXTRA_INTENT")
                    }
                }

                PackageInstaller.STATUS_SUCCESS -> {
                    applicationScope.launch {
                        emitProgress(Progress.Complete)
                    }
                    try { context.applicationContext.unregisterReceiver(this) } catch (_: Throwable) {}
                }

                else -> {
                    val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, 0)
                    try {
                        context.applicationContext.packageManager.packageInstaller.abandonSession(id)
                    } catch (_: Throwable) {
                    }
                    val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Installation error code: $status"
                    applicationScope.launch {
                        val e = Exception(message)
                        Log.e(TAG, "Update failure: $message", e)
                        emitProgress(Progress.Failure(e))
                    }
                    try { context.applicationContext.unregisterReceiver(this) } catch (_: Throwable) {}
                }
            }
        }
    }

    fun monitorForUpdates() {
        if (BuildConfig.DEBUG)
            return

        val context = Application.get()

        if (installerIsGooglePlay(context))
            return

        updaterScope.launch {
            delay(5.seconds)
            var waitMinutes = 15
            while (true) {
                try {
                    val update = checkForUpdates()
                    if (update != null && isNewer(update)) {
                        Log.i(TAG, "Update available: ${update.version} (versionCode=${update.versionCode})")
                        UserKnobs.setUpdaterNewerVersionSeen(encodeVersion(update.version.toString(), update.versionCode))
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "Background update check failed: ${e.message}")
                }
                delay(waitMinutes.minutes)
                waitMinutes = 60
            }
        }

        UserKnobs.updaterNewerVersionSeen.onEach { ver ->
            if (
                ver != null &&
                isStoredNewer(ver) &&
                UserKnobs.updaterNewerVersionConsented.firstOrNull()?.let { isStoredNewer(it) } != true
            ) {
                emitProgress(Progress.Available(versionDisplayName(ver), ver))
            }
        }.launchIn(applicationScope)

        UserKnobs.updaterNewerVersionConsented.onEach { ver ->
            if (ver != null && isStoredNewer(ver)) {
                updaterScope.launch {
                    downloadAndUpdateWrapErrors()
                }
            }
        }.launchIn(applicationScope)
    }

    suspend fun checkNow(): CheckResult = withContext(Dispatchers.IO) {
        try {
            val update = checkForUpdates()
            if (update != null && isNewer(update)) {
                val versionId = encodeVersion(update.version.toString(), update.versionCode)
                UserKnobs.setUpdaterNewerVersionSeen(versionId)
                UserKnobs.setUpdaterNewerVersionConsented(null)
                emitProgress(Progress.Available(update.version.toString(), versionId))
                CheckResult.Available(update.version.toString(), update.changelog)
            } else {
                emitProgress(Progress.Complete)
                CheckResult.UpToDate
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Check updates error", e)
            CheckResult.Error(e.message ?: "Network error")
        }
    }

    class AppUpdatedReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED)
                return

            if (installer(context) != context.packageName)
                return

            try {
                val start = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(start)
            } catch (e: Throwable) {
                Log.w(TAG, "Could not relaunch MainActivity after update: ${e.message}")
            }
        }
    }
}
