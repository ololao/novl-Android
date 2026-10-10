/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.preference

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.AttributeSet
import android.widget.Toast
import androidx.preference.Preference
import com.wireguard.android.Application
import com.wireguard.android.BuildConfig
import com.wireguard.android.R
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.WgQuickBackend
import com.wireguard.android.util.ErrorMessages
import com.wireguard.android.util.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VersionPreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {
    private var versionSummary: String? = null

    override fun getSummary() = versionSummary

    override fun getTitle() = context.getString(R.string.version_title, BuildConfig.VERSION_NAME)

    override fun onClick() {
        lifecycleScope.launch {
            Toast.makeText(context, R.string.updater_checking_toast, Toast.LENGTH_SHORT).show()
            when (val result = com.wireguard.android.updater.Updater.checkNow()) {
                is com.wireguard.android.updater.Updater.CheckResult.Available -> {
                    Toast.makeText(context, context.getString(R.string.updater_found_toast, result.version), Toast.LENGTH_LONG).show()
                }
                is com.wireguard.android.updater.Updater.CheckResult.UpToDate -> {
                    Toast.makeText(context, R.string.updater_latest_toast, Toast.LENGTH_SHORT).show()
                }
                is com.wireguard.android.updater.Updater.CheckResult.Error -> {
                    Toast.makeText(context, context.getString(R.string.updater_error_toast, result.message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    companion object {
        private fun getBackendPrettyName(context: Context, backend: Backend) = when (backend) {
            is WgQuickBackend -> context.getString(R.string.type_name_kernel_module)
            is GoBackend -> context.getString(R.string.type_name_go_userspace)
            else -> ""
        }
    }

    init {
        lifecycleScope.launch {
            val backend = Application.getBackend()
            versionSummary = getContext().getString(R.string.version_summary_checking, getBackendPrettyName(context, backend).lowercase())
            notifyChanged()
            versionSummary = try {
                getContext().getString(R.string.version_summary, getBackendPrettyName(context, backend), withContext(Dispatchers.IO) { backend.version })
            } catch (_: Throwable) {
                getContext().getString(R.string.version_summary_unknown, getBackendPrettyName(context, backend).lowercase())
            }
            notifyChanged()
        }
    }
}
