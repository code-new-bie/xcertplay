package com.shilapi.xcertplay.hud

import android.content.Context

/** The BYD settings entry is identified only by the stock BydLogTool package. */
object BydSettingsAvailability {
    fun available(context: Context): Boolean =
        runCatching {
            context.packageManager.getPackageInfo("com.byd.bydlogtool", 0)
        }.isSuccess
}
