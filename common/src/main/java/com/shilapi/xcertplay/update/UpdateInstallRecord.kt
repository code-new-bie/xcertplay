package com.shilapi.xcertplay.update

/**
 * What the install script wrote to [UpdateInstaller.LOG_PATH]: a marked line for each step, with
 * pm's and am's own output between them. Read over ADB, it tells an app opened again whether its
 * update is still installing, and why it failed.
 */
internal data class UpdateInstallRecord(
    /** The token of the install that wrote this record; null before the script's first line. */
    val token: String?,
    val finished: Boolean,
    val copyFailed: Boolean,
    val pmExit: Int?,
    /** pm's own lines, such as `Success` or `Failure [INSTALL_FAILED_…]`. */
    val pmOutput: List<String>,
    val lines: List<String>,
) {
    enum class State { NOT_STARTED, RUNNING, FAILED, INSTALLED }

    val state: State
        get() = when {
            token == null -> State.NOT_STARTED
            !finished -> State.RUNNING
            !copyFailed && pmExit == 0 -> State.INSTALLED
            else -> State.FAILED
        }

    /** Why the install failed, in pm's words where it gave any. */
    fun failure(): String = when {
        copyFailed -> "the APK could not be copied to /data/local/tmp"
        else -> pmOutput.firstOrNull { it.startsWith("Failure") || it.startsWith("Error") }
            ?: pmOutput.lastOrNull()
            ?: "pm exit=${pmExit ?: "?"}"
    }

    /** One line for the session log. */
    fun summary(): String = lines.joinToString(" | ").take(SUMMARY_CHARS)

    companion object {
        const val MARK = "xcertplay-update"
        private const val SUMMARY_CHARS = 600

        fun parse(text: String?): UpdateInstallRecord {
            val lines = text.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }
            var token: String? = null
            var finished = false
            var copyFailed = false
            var pmExit: Int? = null
            var inPm = false
            val pmOutput = mutableListOf<String>()
            for (line in lines) {
                if (!line.startsWith("$MARK ")) {
                    if (inPm) pmOutput += line
                    continue
                }
                val step = line.removePrefix("$MARK ")
                when {
                    step.startsWith("started ") -> token = step.removePrefix("started ").substringBefore(' ')
                    step.startsWith("installing") -> inPm = true
                    step.startsWith("copy failed") -> copyFailed = true
                    step.startsWith("pm exit=") -> {
                        pmExit = step.removePrefix("pm exit=").trim().toIntOrNull()
                        inPm = false
                    }
                    step.startsWith("done") -> finished = true
                }
            }
            return UpdateInstallRecord(token, finished, copyFailed, pmExit, pmOutput, lines)
        }
    }
}
