package com.shilapi.xcertplay.update

import androidx.core.content.FileProvider

/**
 * Hands a verified update APK in this app's cache to the system installer. A class of its own, as in
 * DiPlay, so that no other provider merged into the app manifest can widen these paths.
 */
class UpdateApkProvider : FileProvider()
