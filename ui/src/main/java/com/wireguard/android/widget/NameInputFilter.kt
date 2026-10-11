/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.widget

import android.text.InputFilter
import android.text.SpannableStringBuilder
import android.text.Spanned
import com.wireguard.android.backend.Tunnel

/**
 * InputFilter for entering WireGuard configuration names (Linux interface names).
 */
class NameInputFilter : InputFilter {
    override fun filter(
        source: CharSequence,
        sStart: Int, sEnd: Int,
        dest: Spanned,
        dStart: Int, dEnd: Int
    ): CharSequence? {
        var replacement: SpannableStringBuilder? = null
        var rIndex = 0
        val replacedLength = dEnd - dStart
        val resultingLength = dest.length - replacedLength
        for (sIndex in sStart until sEnd) {
            val c = source[sIndex]
            val pos = dStart + rIndex
            // Restrict characters to those valid in interfaces, and account for the length of the
            // text being replaced so that replacing a full name still accepts new content.
            if (pos < Tunnel.NAME_MAX_LENGTH && isAllowed(c) &&
                resultingLength + rIndex < Tunnel.NAME_MAX_LENGTH
            ) {
                ++rIndex
            } else {
                if (replacement == null) replacement = SpannableStringBuilder(source, sStart, sEnd)
                replacement.delete(rIndex, rIndex + 1)
            }
        }
        return replacement
    }

    companion object {
        private fun isAllowed(c: Char) = Character.isLetterOrDigit(c) || "_=+.-".indexOf(c) >= 0

        @JvmStatic
        fun newInstance() = NameInputFilter()
    }
}
