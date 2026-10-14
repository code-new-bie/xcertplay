package com.shilapi.xcertplay.update

import android.content.Context

/** When updates were checked, what was found, and an install that was started. */
internal class UpdateStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var autoCheck: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CHECK, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CHECK, value).apply()

    val lastSuccessMs: Long get() = prefs.getLong(KEY_LAST_SUCCESS, 0L)
    val lastAttemptMs: Long get() = prefs.getLong(KEY_LAST_ATTEMPT, 0L)
    val lastError: String? get() = prefs.getString(KEY_LAST_ERROR, null)

    /** The newest release seen by the last successful check; null when there was none. */
    val latest: UpdateRelease?
        get() {
            val tag = prefs.getString(KEY_TAG, null) ?: return null
            return UpdateRelease(
                version = ReleaseVersion.parse(tag) ?: return null,
                tag = tag,
                notes = prefs.getString(KEY_NOTES, "").orEmpty(),
                publishedAt = prefs.getString(KEY_PUBLISHED, null),
                apkName = prefs.getString(KEY_APK_NAME, null) ?: return null,
                apkUrl = prefs.getString(KEY_APK_URL, null) ?: return null,
                apkSize = prefs.getLong(KEY_APK_SIZE, -1L).takeIf { it > 0 } ?: return null,
                sha256 = prefs.getString(KEY_SHA256, null) ?: return null,
            )
        }

    fun recordAttempt(nowMs: Long) {
        prefs.edit().putLong(KEY_LAST_ATTEMPT, nowMs).apply()
    }

    fun recordSuccess(nowMs: Long, release: UpdateRelease?) {
        val edit = prefs.edit()
            .putLong(KEY_LAST_SUCCESS, nowMs)
            .putLong(KEY_LAST_ATTEMPT, nowMs)
            .remove(KEY_LAST_ERROR)
        if (release == null) {
            edit.remove(KEY_TAG)
        } else {
            edit.putString(KEY_TAG, release.tag)
                .putString(KEY_NOTES, release.notes)
                .putString(KEY_PUBLISHED, release.publishedAt)
                .putString(KEY_APK_NAME, release.apkName)
                .putString(KEY_APK_URL, release.apkUrl)
                .putLong(KEY_APK_SIZE, release.apkSize)
                .putString(KEY_SHA256, release.sha256)
        }
        edit.apply()
    }

    fun recordFailure(nowMs: Long, reason: String) {
        prefs.edit().putLong(KEY_LAST_ATTEMPT, nowMs).putString(KEY_LAST_ERROR, reason).apply()
    }

    /** The version an install was started for; read once at the next start to report the outcome. */
    var pendingInstall: String?
        get() = prefs.getString(KEY_PENDING, null)
        set(value) {
            // commit, not apply: the process is ended right after an install starts.
            prefs.edit().apply { if (value == null) remove(KEY_PENDING) else putString(KEY_PENDING, value) }.commit()
        }

    private companion object {
        const val PREFS = "xcertplay_update"
        const val KEY_AUTO_CHECK = "auto_check"
        const val KEY_LAST_SUCCESS = "last_success_ms"
        const val KEY_LAST_ATTEMPT = "last_attempt_ms"
        const val KEY_LAST_ERROR = "last_error"
        const val KEY_TAG = "latest_tag"
        const val KEY_NOTES = "latest_notes"
        const val KEY_PUBLISHED = "latest_published"
        const val KEY_APK_NAME = "latest_apk_name"
        const val KEY_APK_URL = "latest_apk_url"
        const val KEY_APK_SIZE = "latest_apk_size"
        const val KEY_SHA256 = "latest_sha256"
        const val KEY_PENDING = "pending_install"
    }
}

/** When the next automatic check is due. */
internal object UpdateSchedule {
    const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
    const val RETRY_AFTER_FAILURE_MS = 60 * 60 * 1000L
    /** After start: CarPlay connects first, and the car's mobile data needs a moment after power-on. */
    const val FIRST_CHECK_DELAY_MS = 60 * 1000L

    /**
     * Due once a day after a successful check, and an hour after a failed one. A clock that went
     * backwards (a head unit that lost its time) makes a check due rather than never.
     */
    fun isDue(nowMs: Long, lastSuccessMs: Long, lastAttemptMs: Long): Boolean =
        delayUntilDue(nowMs, lastSuccessMs, lastAttemptMs) == 0L

    /** How long until a check is due: both the daily wait and the wait after a failure must be over. */
    fun delayUntilDue(nowMs: Long, lastSuccessMs: Long, lastAttemptMs: Long): Long {
        val sinceSuccess = nowMs - lastSuccessMs
        val successWait = if (lastSuccessMs > 0 && sinceSuccess in 0 until CHECK_INTERVAL_MS) {
            CHECK_INTERVAL_MS - sinceSuccess
        } else {
            0L
        }
        val sinceAttempt = nowMs - lastAttemptMs
        val failureWait = if (lastAttemptMs > lastSuccessMs && sinceAttempt in 0 until RETRY_AFTER_FAILURE_MS) {
            RETRY_AFTER_FAILURE_MS - sinceAttempt
        } else {
            0L
        }
        return maxOf(successWait, failureWait)
    }
}
