package com.shilapi.xcertplay

import android.content.res.Configuration

internal fun isDarkMode(uiMode: Int): Boolean =
    uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

/** BYD screen mode: 0 follows the vehicle day/night signal, 1 is light, 2 is dark. */
internal fun resolveCarPlayDarkMode(
    uiMode: Int,
    screenMode: String?,
    vehicleDayOrNight: String?,
): Boolean = when (screenMode) {
    "1" -> false
    "2" -> true
    "0" -> when (vehicleDayOrNight) {
        "0" -> true
        "1" -> false
        else -> isDarkMode(uiMode)
    }
    else -> isDarkMode(uiMode)
}
