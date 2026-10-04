package com.shilapi.xcertplay

import android.Manifest
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplaySettings
import com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeBasis
import com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeMm
import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayIcon
import com.shilapi.xcertplay.airplay.AirPlaySafeArea
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.CarPlayMediaEngine
import com.shilapi.xcertplay.airplay.CarPlayVoiceKey
import com.shilapi.xcertplay.airplay.SafeAreaRect
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.BydClusterSongSettings
import com.shilapi.xcertplay.location.AndroidCarPlayLocationProvider
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.hud.BydBatteryProvider
import com.shilapi.xcertplay.hud.BydCallUiSettings
import com.shilapi.xcertplay.hud.BydVehicleAccess
import com.shilapi.xcertplay.hud.BydVehicleSettings
import com.shilapi.xcertplay.hud.BydWheelSpeedSource
import com.shilapi.xcertplay.network.BluetoothHandoffSettings
import com.shilapi.xcertplay.network.P2pChannelPreference
import com.shilapi.xcertplay.network.WifiScanPauseSettings
import com.shilapi.xcertplay.transport.EvChargingConnectors
import com.shilapi.xcertplay.transport.VehicleSpeedLocationProvider
import com.shilapi.xcertplay.hud.BydCallUiSuppressor
import com.shilapi.xcertplay.hud.BydSettingsAvailability
import com.shilapi.xcertplay.media.CarPlayTouchMapper
import com.shilapi.xcertplay.media.CarPlayVideoLayout
import com.shilapi.xcertplay.media.MainMediaAudioBuffer
import com.shilapi.xcertplay.media.VehicleAudioChannel
import com.shilapi.xcertplay.media.MediaMetricsMonitor
import com.shilapi.xcertplay.media.MicrophoneGain
import com.shilapi.xcertplay.media.MicrophoneLevelMonitor
import com.shilapi.xcertplay.network.CarPlayVpnService
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.orchestration.isManualHotspotChannelCompatible
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.Iap2LocationProvider
import com.shilapi.xcertplay.transport.UsbDeviceId
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full-screen CarPlay host. It renders decoded video through a [TextureView], forwards touch to
 * the active AirPlay session, and drives the complete wired or wireless bring-up through
 * [CarPlayController].
 *
 * Apple devices are discovered by vendor ID; CH341 uses the configured VID/PID below.
 */
class CarPlayHostActivity : ComponentActivity() {
    private data class SettingsBaseline(
        val safeAreaSize: DisplaySize?,
        val safeAreaRect: SafeAreaRect?,
        val customIconBytes: ByteArray?,
        val handshake: List<Any?>,
    )

    private lateinit var airPlayIdentity: AirPlayIdentity

    // CH341 USB\VID_1A86&PID_5512&REV_0304 is the deployment-supplied bridge identity.
    private fun createRuntimeConfig(): CarPlayRuntimeConfig {
        val (name, source) = VehicleName.resolveWithSource(customVehicleName, VehicleName.bluetoothName(this))
        appendLog("Vehicle name \"$name\" (from ${source.label})")
        val accessoryIds = AccessoryIds.of(this, airPlayIdentity)
        appendLog(
            "Accessory deviceID=${accessoryIds.deviceId} " +
                "bluetoothID=${accessoryIds.bluetoothId} (${accessoryIds.bluetoothSource.label}) " +
                "serial=${AccessorySerial.of(this)}",
        )
        return buildRuntimeConfig()
    }

    private fun buildRuntimeConfig(): CarPlayRuntimeConfig = CarPlayRuntimeConfig(
        mfiTarget = mfiTarget,
        ch341Devices = if (mfiTarget == MfiTarget.USB_CH341) {
            listOf(UsbDeviceId(0x1a86, 0x5512))
        } else {
            emptyList()
        },
        // The CP latches its I2C address from the RST level at its own power-up, so the host must
        // not pulse RST before discovery. Driving D0 re-latches the part onto the alternate
        // address (0x10), where the accessory certificate is not readable. Leave RST at its
        // hardware pull (VCC -> 0x11) and let the scanner find the part with its certificate.
        // Set this back to 0 to restore the D0 pulse.
        ch341MfiResetGpio = null,
        linuxI2cPath = if (mfiTarget == MfiTarget.I2C) mfiI2cPath.trim() else null,
        remoteMfiServer = remoteMfiServer.trim().takeIf { it.isNotEmpty() },
        remoteMfiToken = remoteMfiToken.takeIf { it.isNotEmpty() },
        localMfiCertificateUri = localMfiCertificateUri.takeIf { it.isNotEmpty() },
        localMfiPrivateKeyUri = localMfiPrivateKeyUri.takeIf { it.isNotEmpty() },
        identification = Iap2IdentificationConfig(
            name = vehicleName(),
            modelIdentifier = normalizedModel(),
            manufacturer = normalizedManufacturer(),
            serialNumber = AccessorySerial.of(this),
            firmwareVersion = "1.0.0",
            hardwareVersion = "1.0",
            carPlayUsbInterfaceNumber = 3,
            locationInformationEnabled = locationReportingEnabled,
            vehicleSpeedEnabled = locationReportingEnabled && BydVehicleSettings.speedEnabled(this),
            vehicleStatusEnabled = BydVehicleSettings.batteryEnabled(this),
            chargingConnectors = if (BydVehicleSettings.dcChargingEnabled(this)) {
                EvChargingConnectors.GB_T
            } else {
                EvChargingConnectors.GB_T_AC_ONLY
            },
        ),
        transport = if (wirelessEnabled) CarPlayTransport.WIRELESS else CarPlayTransport.WIRED,
        wirelessHotspotMode = wirelessHotspotMode,
        manualHotspotSsid = manualHotspotSsid,
        manualHotspotPassphrase = manualHotspotPassphrase,
        manualHotspotBand = manualHotspotBand,
        manualHotspotChannel = manualHotspotChannel,
        manualHotspotSecurity = manualHotspotSecurity,
        wifiP2pChannel = wifiP2pChannel,
        locationReportingEnabled = locationReportingEnabled,
    )

    private val vpnConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            awaitingVpnConsent = false
            if (result.resultCode == RESULT_OK) {
                vpnReady = true
                maybeStartCarPlay()
            } else {
                setStatus(getString(R.string.vpn_denied))
            }
        }
    private val wirelessPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            awaitingWirelessPermissions = false
            wirelessPermissionsReady = hasRequiredWirelessPermissions()
            appendLog(
                if (wirelessPermissionsReady) {
                    "Wireless startup permissions granted"
                } else {
                    "Wireless startup permissions denied"
                },
            )
            updateHotspotStatusBlock()
            maybeStartCarPlay()
        }
    private val microphonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            microphoneAvailable = granted
            microphonePermissionResolved = true
            appendLog(if (granted) "Microphone permission granted" else "Microphone permission denied")
            val startGainTest = granted && microphoneGainTestAfterPermission && menuOpen
            microphoneGainTestAfterPermission = false
            requestStartupPrerequisites()
            if (startGainTest) startMicrophoneGainTest()
        }
    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            awaitingLocationPermission = false
            locationPermissionAvailable = hasFineLocationPermission()
            if (locationPermissionAvailable) {
                appendLog("Location permission granted")
            } else if (locationReportingEnabled) {
                locationReportingEnabled = false
                if (!menuOpen) {
                    AirPlayPersistence.saveLocationReportingEnabled(
                        this@CarPlayHostActivity,
                        false,
                    )
                }
                locationReportingSwitch?.isChecked = false
                val approximateOnly =
                    grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
                appendLog(
                    if (approximateOnly) {
                        "Precise location permission denied; location reporting disabled"
                    } else {
                        "Location permission denied; location reporting disabled"
                    },
                )
            }
            updateResolutionMenu()
            if (!menuOpen) requestStartupPrerequisites()
        }

    private val imagePicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) {
                externalActivityInProgress = false
                return@registerForActivityResult
            }
            imageCrop.launch(
                Intent(this, ImageCropActivity::class.java)
                    .setData(uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        }
    private val imageCrop =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            externalActivityInProgress = false
            if (result.resultCode == RESULT_OK) {
                updateAirPlayIconPreview()
                appendLog("Custom AirPlay icon updated")
            }
        }
    private val localMfiCertificatePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            externalActivityInProgress = false
            if (uri != null && retainDocumentReadPermission(uri, getString(R.string.doc_certificate))) {
                localMfiCertificateUri = uri.toString()
                updateLocalMfiDocumentViews()
                mfiErrorView?.visibility = View.GONE
            }
        }
    private val localMfiPrivateKeyPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            externalActivityInProgress = false
            if (uri != null && retainDocumentReadPermission(uri, getString(R.string.doc_private_key))) {
                localMfiPrivateKeyUri = uri.toString()
                updateLocalMfiDocumentViews()
                mfiErrorView?.visibility = View.GONE
            }
        }

    private var videoView: TextureView? = null
    private var contentRoot: FrameLayout? = null
    private var gestureOverlay: View? = null
    private var disconnectedSettingsButton: View? = null
    private var homeScreen: HomeScreenView? = null
    private var settingsCategory = SettingsCategory.CONNECTION
    private val settingsNavItems = linkedMapOf<SettingsCategory, TextView>()
    private var settingsPageView: LinearLayout? = null
    private var settingsPageScroll: ScrollView? = null
    private var settingsTitleView: TextView? = null
    private var settingsSubtitleView: TextView? = null
    private var settingsSummaryView: TextView? = null
    private var homeStep = ConnectionStep.AUTHENTICATION
    private var homeLinkDetail: String? = null
    /** The last failure, shown until CarPlay video arrives; [homeFailureFresh] marks its step. */
    private var homeFailureMessage: String? = null
    private var homeFailureFresh = false
    private var homeFailedAttempts = 0
    private var homeSessionDropped = false
    private var settingsMenu: View? = null
    private var mfiTargetGroup: RadioGroup? = null
    private var mfiI2cFields: View? = null
    private var mfiRemoteFields: View? = null
    private var mfiLocalFields: View? = null
    private var mfiErrorView: TextView? = null
    private var mfiI2cPathInput: EditText? = null
    private var remoteMfiServerInput: EditText? = null
    private var remoteMfiTokenInput: EditText? = null
    private var localMfiCertificateDocumentView: TextView? = null
    private var localMfiPrivateKeyDocumentView: TextView? = null
    private var settingsBaseline: SettingsBaseline? = null
    private var locationReportingSwitch: Switch? = null
    private var mainMediaAudioBufferSeekBar: SeekBar? = null
    private var mainMediaAudioBufferValueView: TextView? = null
    private var microphoneGainSeekBar: SeekBar? = null
    private var microphoneGainValueView: TextView? = null
    private var microphoneTestButton: Button? = null
    private var microphoneLevelBar: ProgressBar? = null
    private var microphoneLevelValueView: TextView? = null
    private var microphoneLevelMonitor: MicrophoneLevelMonitor? = null
    private var microphoneGainTestAfterPermission = false
    private var statusView: TextView? = null
    private var statusScrollView: ScrollView? = null
    private var stageStatusView: TextView? = null
    private var resolutionValueView: TextView? = null
    private var resolutionPreviewView: TextView? = null
    private var hotspotStatusView: TextView? = null
    private var manualHotspotFields: View? = null
    private var manualHotspotErrorView: TextView? = null
    private var iconPreviewView: ImageView? = null
    private var iconStatusView: TextView? = null
    private var safeAreaSummaryView: TextView? = null
    private var safeAreaEditor: View? = null
    private var safeAreaEditorView: SafeAreaEditorView? = null
    private var safeAreaEditSize: DisplaySize? = null
    private var safeAreaEditorActive = false
    private var mediaMetricsOverlay: MediaMetricsOverlayView? = null
    private var mediaMetricsMonitor: MediaMetricsMonitor? = null
    private var externalActivityInProgress = false
    private var sink: AndroidMediaSink? = null
    private var controller: CarPlayController? = null
    private var currentSurface: Surface? = null
    private var currentSurfaceTexture: SurfaceTexture? = null
    private var activeDisplaySize: DisplaySize? = null
    private var pendingDisplaySize: DisplaySize? = null
    private var displayScaleTenths = CarPlayDisplayScale.DEFAULT_TENTHS
    private var hevcEnabled = true
    private var hevcSoftwareDecoderEnabled = false
    private var advancedAudioChannelMappingSupported = false
    private var advancedAudioChannelMapping = false
    private var mediaAudioChannel = VehicleAudioChannel.AUTOMATIC
    private var navigationAudioChannel = VehicleAudioChannel.AUTOMATIC
    private var phoneAudioChannel = VehicleAudioChannel.AUTOMATIC
    private val channelPreview = AudioChannelPreview { channel ->
        Toast.makeText(this, getString(R.string.audio_channel_preview_failed, channel), Toast.LENGTH_SHORT).show()
    }
    private var mainMediaAudioBufferDurationMs = MainMediaAudioBuffer.DEFAULT_DURATION_MS
    @Volatile private var debugLogsEnabled = false
    private var mediaMetricsEnabled = false
    private var audioPacketCaptureEnabled = false
    private var moreGesturesToSettings = false
    private var keepSessionOnWindowShrink = false
    private var sessionDisplay: CarPlaySessionDisplay? = null
    private var autoStartOnBoot = false
    private var manufacturer = AirPlayPersistence.DEFAULT_MANUFACTURER
    private var model = AirPlayPersistence.DEFAULT_MODEL
    private var customVehicleName = ""
    private var fps = AirPlayDisplaySettings.DEFAULT_FPS
    private var widthPhysicalMm = AirPlayDisplaySettings.DEFAULT_WIDTH_PHYSICAL_MM
    private var physicalSizeBasis = AirPlayDisplaySettings.DEFAULT_PHYSICAL_SIZE_BASIS
    private var maximumDetectedWidthPixels = 0
    private var maximumDetectedHeightPixels = 0
    private var rightHandDrive = false
    private var hideTopBar = true
    private var hideBottomBar = true
    private var safeAreaDrawOutside = true
    private var locationReportingEnabled = false
    private var locationPermissionAvailable = false
    private var microphoneAvailable = false
    private var microphonePermissionResolved = false
    @Volatile private var microphoneGainPercent = MicrophoneGain.DEFAULT_PERCENT
    private var wirelessEnabled = false
    private var mfiTarget = MfiTarget.USB_CH341
    private var mfiI2cPath = AirPlayPersistence.DEFAULT_MFI_I2C_PATH
    private var remoteMfiServer = ""
    private var remoteMfiToken = ""
    private var localMfiCertificateUri = ""
    private var localMfiPrivateKeyUri = ""
    private var wirelessPermissionsReady = false
    private var wirelessHotspotMode = WirelessHotspotMode.WIFI_P2P
    private var manualHotspotSsid = ""
    private var manualHotspotPassphrase = ""
    private var manualHotspotBand = ManualHotspotBand.AUTO
    private var manualHotspotChannel = 0
    private var wifiP2pChannel = P2pChannelPreference.AUTOMATIC
    private var manualHotspotSecurity = ManualHotspotSecurity.OPEN
    private var awaitingVpnConsent = false
    private var awaitingWirelessPermissions = false
    private var awaitingLocationPermission = false
    private var vpnReady = false
    private var hotspotStatus = HotspotStatus(state = "off")
    private var menuOpen = false
    private var latestStage = ""
    private var darkMode = false
    private var appearanceMonitor: CarPlayAppearanceMonitor? = null
    private val activeScreenStreamTypes = mutableSetOf<Int>()
    private var handshakeResetInProgress = false
    private var startAfterHandshakeReset = false
    /** The settings page is open over a running session that was not torn down. */
    private var sessionKeptForSettings = false
    private var displayChangedWhileSettingsOpen = false
    private var connectionLostWhileSettingsOpen = false
    private var restartGeneration = 0
    private var reconnectScheduled = false
    private var sessionLog: SessionLogFile? = null
    private var gestureSequenceActive = false
    private var gestureTracking = false
    private var gestureStartX = 0f
    private var gestureStartY = 0f
    private var edgeSettingsGestureCaptured = false
    private var edgeSettingsGestureEligible = false
    private val shuttingDown = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var carPlayPageVisible = false
    private val bydCallUiSuppressor by lazy { BydCallUiSuppressor(applicationContext) }
    private val appearanceSync = CarPlayAppearanceSync(mainHandler, ::syncAirPlayDarkMode)
    private val teardownExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val airPlayCommandExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val logLines = ScreenLogBuffer(MAX_SCREEN_LOG_LINES)
    private val pendingScreenLogs = ArrayDeque<PendingLog>()
    private val pendingScreenLogsLock = Any()
    private var screenDrainPosted = false
    private val drainScreenLogs = Runnable {
        val pending = synchronized(pendingScreenLogsLock) {
            screenDrainPosted = false
            pendingScreenLogs.toList().also { pendingScreenLogs.clear() }
        }
        if (debugLogsEnabled && !menuOpen) {
            pending.forEach { entry ->
                if (entry.generation == restartGeneration) {
                    appendScreenLog(entry.timestampMillis, entry.message)
                }
            }
        }
    }
    private val expireOldLogLines = Runnable { refreshLogView(System.currentTimeMillis()) }
    private var logRenderScheduled = false
    private val renderLogLines = Runnable {
        logRenderScheduled = false
        refreshLogView(System.currentTimeMillis())
    }
    private val applyDisplaySize = Runnable {
        val size = pendingDisplaySize ?: return@Runnable
        pendingDisplaySize = null
        applyDisplaySize(size)
    }

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            val existing = currentSurface
            val surface = if (
                existing != null &&
                currentSurfaceTexture === texture &&
                existing.isValid
            ) {
                existing
            } else {
                Surface(texture).also {
                    existing?.release()
                    currentSurface = it
                    currentSurfaceTexture = texture
                }
            }
            appendLog(if (existing === surface) "Texture surface reused" else "Texture surface created")
            attachSurface(surface)
            scheduleDisplaySize(width, height)
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
            updateVideoLayout(width, height)
            scheduleDisplaySize(width, height)
        }

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            if (currentSurfaceTexture !== texture) return true
            currentSurface?.let { surface ->
                sink?.clearSurface(SCREEN_TYPE_MAIN, surface)
                sink?.clearSurface(SCREEN_TYPE_ALT, surface)
                surface.release()
            }
            currentSurface = null
            currentSurfaceTexture = null
            appendLog("Texture surface destroyed")
            return true
        }

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        latestStage = getString(R.string.stage_preparing)
        initializeSessionLog()
        darkMode = isDarkMode(applicationContext.resources.configuration.uiMode)
        appearanceMonitor = CarPlayAppearanceMonitor(
            this, mainHandler, applicationContext.resources.configuration.uiMode, diagnostic = ::appendLog,
        ) { night ->
            if (night != darkMode) {
                darkMode = night
                appendLog("CarPlay appearance changed to ${if (night) "dark" else "light"}")
                syncAirPlayDarkMode()
            }
        }.also { it.start() }
        advancedAudioChannelMappingSupported =
            resources.getBoolean(R.bool.config_advanced_audio_channel_mapping)
        airPlayIdentity = AirPlayPersistence.loadIdentity(this)
        loadPersistedSettings()
        locationPermissionAvailable = hasFineLocationPermission()
        setContentView(buildContentView())
        applyFullscreenMode()
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (menuOpen) {
                        if (safeAreaEditorActive) closeSafeAreaEditor() else cancelSettingsEdits()
                    } else {
                        // Keep this screen and its CarPlay video alive; reopening the app returns to it.
                        appendLog("Back pressed; opening the head-unit home screen")
                        try {
                            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
                        } catch (_: ActivityNotFoundException) {
                            moveTaskToBack(true)
                        }
                    }
                }
            },
        )

        appendLog(
            "Host started; MFI target=${mfiTargetLabel(mfiTarget)}; " +
                "transport=${if (wirelessEnabled) "wireless" else "wired"}",
        )
        val reusedBackgroundSession = adoptBackgroundSession()
        microphoneAvailable =
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        microphonePermissionResolved = microphoneAvailable
        if (reusedBackgroundSession) {
            updateDebugOverlays()
        } else if (microphonePermissionResolved) {
            requestStartupPrerequisites()
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun loadPersistedSettings() {
        displayScaleTenths = AirPlayPersistence.loadDisplayScaleTenths(this)
        hevcEnabled = AirPlayPersistence.loadHevcEnabled(this)
        hevcSoftwareDecoderEnabled =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                AirPlayPersistence.loadHevcSoftwareDecoderEnabled(this)
        advancedAudioChannelMapping =
            advancedAudioChannelMappingSupported &&
                AirPlayPersistence.loadAdvancedAudioChannelMapping(this)
        mediaAudioChannel = AirPlayPersistence.loadMediaAudioChannel(this)
        navigationAudioChannel = AirPlayPersistence.loadNavigationAudioChannel(this)
        phoneAudioChannel = AirPlayPersistence.loadPhoneAudioChannel(this)
        mainMediaAudioBufferDurationMs =
            AirPlayPersistence.loadMainMediaAudioBufferDurationMs(this)
        microphoneGainPercent = AirPlayPersistence.loadMicrophoneGainPercent(this)
        debugLogsEnabled = AirPlayPersistence.loadDebugLogsEnabled(this)
        mediaMetricsEnabled = AirPlayPersistence.loadMediaMetricsEnabled(this)
        audioPacketCaptureEnabled = AirPlayPersistence.loadAudioPacketCaptureEnabled(this)
        moreGesturesToSettings = AirPlayPersistence.loadMoreGesturesToSettings(this)
        keepSessionOnWindowShrink = AirPlayPersistence.loadKeepSessionOnWindowShrink(this)
        autoStartOnBoot = AirPlayPersistence.loadAutoStartOnBoot(this)
        manufacturer = AirPlayPersistence.loadManufacturer(this)
        model = AirPlayPersistence.loadModel(this)
        customVehicleName = AirPlayPersistence.loadCustomVehicleName(this)
        fps = AirPlayPersistence.loadFps(this)
        widthPhysicalMm = AirPlayPersistence.loadWidthPhysicalMm(this)
        physicalSizeBasis = AirPlayPersistence.loadPhysicalSizeBasis(this)
        AirPlayPersistence.loadMaximumDetectedDisplay(this).let { (width, height) ->
            maximumDetectedWidthPixels = width
            maximumDetectedHeightPixels = height
        }
        rightHandDrive = AirPlayPersistence.loadRightHandDrive(this)
        hideTopBar = AirPlayPersistence.loadHideTopBar(this)
        hideBottomBar = AirPlayPersistence.loadHideBottomBar(this)
        safeAreaDrawOutside = AirPlayPersistence.loadSafeAreaDrawOutside(this)
        locationReportingEnabled = AirPlayPersistence.loadLocationReportingEnabled(this)
        locationPermissionAvailable = hasFineLocationPermission()
        wirelessEnabled = AirPlayPersistence.loadWirelessEnabled(this)
        mfiTarget = AirPlayPersistence.loadMfiTarget(this)
        mfiI2cPath = AirPlayPersistence.loadMfiI2cPath(this)
        remoteMfiServer = AirPlayPersistence.loadRemoteMfiServer(this)
        remoteMfiToken = AirPlayPersistence.loadRemoteMfiToken(this)
        localMfiCertificateUri = AirPlayPersistence.loadLocalMfiCertificateUri(this)
        localMfiPrivateKeyUri = AirPlayPersistence.loadLocalMfiPrivateKeyUri(this)
        wirelessHotspotMode = AirPlayPersistence.loadWirelessHotspotMode(this)
        manualHotspotSsid = AirPlayPersistence.loadManualHotspotSsid(this)
        manualHotspotPassphrase = AirPlayPersistence.loadManualHotspotPassphrase(this)
        manualHotspotBand = AirPlayPersistence.loadManualHotspotBand(this)
        manualHotspotChannel = AirPlayPersistence.loadManualHotspotChannel(this)
        wifiP2pChannel = AirPlayPersistence.loadWifiP2pChannel(this)
        manualHotspotSecurity = AirPlayPersistence.loadManualHotspotSecurity(this)
        wirelessPermissionsReady = !wirelessEnabled || hasRequiredWirelessPermissions()
    }

    private fun requestStartupPrerequisites() {
        if (locationReportingEnabled && !locationPermissionAvailable) {
            requestLocationPermission()
            return
        }
        if (wirelessEnabled) {
            requestWirelessPermissions()
        } else {
            requestVpnConsent()
        }
    }

    private fun requestLocationPermission() {
        if (locationPermissionAvailable || awaitingLocationPermission) return
        awaitingLocationPermission = true
        locationPermission.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
        )
    }

    private fun hasFineLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestVpnConsent() {
        val consent = CarPlayVpnService.prepare(this)
        if (consent == null) {
            vpnReady = true
            maybeStartCarPlay()
        } else {
            awaitingVpnConsent = true
            vpnConsent.launch(consent)
        }
    }

    private fun requestWirelessPermissions() {
        val permissions = requiredWirelessPermissions()
        if (permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            wirelessPermissionsReady = true
            updateHotspotStatusBlock()
            maybeStartCarPlay()
            return
        }
        wirelessPermissionsReady = false
        updateHotspotStatusBlock()
        awaitingWirelessPermissions = true
        wirelessPermissions.launch(permissions.toTypedArray())
    }

    private fun hasRequiredWirelessPermissions(): Boolean =
        requiredWirelessPermissions().all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

    private fun requiredWirelessPermissions(): List<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        else -> listOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }

    override fun onStart() {
        super.onStart()
        carPlayPageVisible = true
        updateBydCallUi()
    }

    private fun updateBydCallUi() {
        bydCallUiSuppressor.updateUsage(
            carPlayPageVisible && !menuOpen,
            controller?.hasActiveAirPlaySession() == true,
        )
    }

    override fun onResume() {
        super.onResume()
        appearanceMonitor?.updateUiMode(applicationContext.resources.configuration.uiMode)
        locationPermissionAvailable = hasFineLocationPermission()
        if (locationReportingEnabled && !locationPermissionAvailable && !menuOpen) {
            requestLocationPermission()
        }
        wirelessPermissionsReady = !wirelessEnabled || hasRequiredWirelessPermissions()
        maybeStartCarPlay()
        applyFullscreenMode()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val currentController = controller
        if (!CarPlayVoiceKey.handles(event.keyCode) ||
            currentController?.hasActiveAirPlaySession() != true) {
            return super.dispatchKeyEvent(event)
        }
        if (CarPlayVoiceKey.invokesSiri(event)) {
            val queued = currentController.requestSiri()
            appendLog("Siri: long voice key ${event.keyCode} queued=$queued")
        }
        return true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreenMode()
    }

    override fun onStop() {
        carPlayPageVisible = false
        updateBydCallUi()
        stopMicrophoneGainTest()
        channelPreview.stop()
        // The controller, USB/iAP2 link, and VPN attachment intentionally outlive the UI.
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        appearanceMonitor?.updateUiMode(newConfig.uiMode)
        applyFullscreenMode()
        stageStatusView?.maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
        scrollLogsToBottom()
        videoView?.post {
            val view = videoView ?: return@post
            scheduleDisplaySize(view.width, view.height)
        }
    }

    override fun onDestroy() {
        bydCallUiSuppressor.close()
        channelPreview.close()
        appearanceMonitor?.stop()
        appearanceSync.stop()
        stopMicrophoneGainTest()
        mainHandler.removeCallbacks(applyDisplaySize)
        mainHandler.removeCallbacks(expireOldLogLines)
        mainHandler.removeCallbacks(renderLogLines)
        mainHandler.removeCallbacks(drainScreenLogs)
        currentSurface?.let { surface ->
            sink?.clearSurface(SCREEN_TYPE_MAIN, surface)
            sink?.clearSurface(SCREEN_TYPE_ALT, surface)
            surface.release()
        }
        currentSurface = null
        currentSurfaceTexture = null
        sessionLog?.append("Activity destroyed")
        sessionLog?.close()
        sessionLog = null
        super.onDestroy()
    }

    private fun buildContentView(): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(NO_VIDEO_BACKGROUND)
        }
        contentRoot = root
        val video = TextureView(this).apply {
            isOpaque = false
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            surfaceTextureListener = textureListener
        }
        val gestureLayer = View(this).apply {
            isClickable = true
            setOnTouchListener { view, event -> onHostTouch(view, event) }
        }
        val log = TextView(this).apply {
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            text = ""
        }
        val logScroll = object : ScrollView(this) {
            override fun onInterceptTouchEvent(event: MotionEvent): Boolean = false
            override fun onTouchEvent(event: MotionEvent): Boolean = false
        }.apply {
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            isFillViewport = false
            isFocusable = false
            addView(
                log,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> scrollLogsToBottom() }
        }
        val home = HomeScreenView(
            this,
            onSettings = ::openSettingsMenu,
            onBluetoothSettings = ::openSystemBluetoothSettings,
            onReconnect = ::reconnectFromHome,
            onUseLocalHotspot = ::useLocalHotspotFromHome,
        )
        val statusParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.START,
        )
        statusParams.setMargins(dp(12), 0, dp(12), dp(12))

        val settings = buildSettingsMenu().apply { visibility = View.GONE }
        val editor = buildSafeAreaEditor().apply { visibility = View.GONE }

        root.addView(video)
        root.addView(
            gestureLayer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(
            home,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(logScroll, statusParams)
        root.addView(
            settings,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(
            editor,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                updateMediaMetricsOverlayLayout(right - left, bottom - top)
            }
        }
        videoView = video
        gestureOverlay = gestureLayer
        disconnectedSettingsButton = home
        homeScreen = home
        settingsMenu = settings
        safeAreaEditor = editor
        statusView = log
        statusScrollView = logScroll
        updateDebugOverlays()
        renderHome()
        return root
    }

    private enum class SettingsCategory(val title: Int, val subtitle: Int) {
        CONNECTION(R.string.cat_connection, R.string.cat_connection_sub),
        DISPLAY(R.string.cat_display, R.string.cat_display_sub),
        AUDIO(R.string.cat_audio, R.string.cat_audio_sub),
        VEHICLE(R.string.cat_vehicle, R.string.cat_vehicle_sub),
        ADVANCED(R.string.cat_advanced, R.string.cat_advanced_sub),
        DIAGNOSTICS(R.string.cat_diagnostics, R.string.cat_diagnostics_sub),
    }

    /**
     * Settings: categories on the left, the selected category on the right, Cancel/Save fixed at the
     * bottom. A category page is rebuilt whenever it is shown, so it always reflects current values.
     */
    private fun buildSettingsMenu(): View {
        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(MENU_BACKGROUND)
            isClickable = true
        }

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(MENU_NAV_BACKGROUND)
            setPadding(dp(20), dp(28), dp(20), dp(20))
        }
        nav.addView(
            menuText(getString(R.string.settings_title), 26f, Color.WHITE, bold = true),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(24) },
        )
        settingsNavItems.clear()
        for (category in SettingsCategory.values()) {
            val item = menuText(getString(category.title), 20f, MENU_SECONDARY).apply {
                setPadding(dp(18), dp(14), dp(18), dp(14))
                isClickable = true
                setOnClickListener { showSettingsCategory(category) }
            }
            settingsNavItems[category] = item
            nav.addView(
                item,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(4) },
            )
        }
        nav.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        nav.addView(
            Button(this).apply {
                text = getString(R.string.exit_app)
                isAllCaps = false
                textSize = 16f
                setTextColor(Color.WHITE)
                backgroundTintList = ColorStateList.valueOf(MENU_DANGER)
                minHeight = dp(48)
                setOnClickListener { exitApplication() }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(40), dp(28), dp(40), dp(8))
        }
        val titleView = menuText("", 28f, Color.WHITE, bold = true)
        val subtitleView = menuText("", 15f, MENU_SECONDARY)
        header.addView(titleView)
        header.addView(subtitleView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(6) })
        right.addView(header)

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(40), dp(12), dp(40), dp(32))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(page, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        right.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(MENU_NAV_BACKGROUND)
            setPadding(dp(40), dp(14), dp(24), dp(14))
        }
        val summary = menuText("", 15f, MENU_SECONDARY).apply {
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        bottomBar.addView(summary, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bottomBar.addView(
            Button(this).apply {
                text = getString(R.string.cancel)
                isAllCaps = false
                textSize = 17f
                setTextColor(Color.WHITE)
                backgroundTintList = ColorStateList.valueOf(MENU_TRACK_OFF)
                minHeight = dp(52)
                minWidth = dp(128)
                contentDescription = getString(R.string.discard_exit)
                setOnClickListener { cancelSettingsEdits() }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = dp(16) },
        )
        bottomBar.addView(
            Button(this).apply {
                text = getString(R.string.save_reconnect)
                isAllCaps = false
                textSize = 17f
                setTextColor(MENU_BUTTON_TEXT)
                backgroundTintList = ColorStateList.valueOf(MENU_ACCENT)
                minHeight = dp(52)
                minWidth = dp(160)
                setOnClickListener { saveSettingsAndReconnect() }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = dp(12) },
        )
        right.addView(bottomBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        overlay.addView(nav, LinearLayout.LayoutParams(dp(260), ViewGroup.LayoutParams.MATCH_PARENT))
        overlay.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        settingsPageView = page
        settingsPageScroll = scroll
        settingsTitleView = titleView
        settingsSubtitleView = subtitleView
        settingsSummaryView = summary
        showSettingsCategory(settingsCategory)
        return overlay
    }

    private fun showSettingsCategory(category: SettingsCategory) {
        settingsCategory = category
        settingsNavItems.forEach { (item, view) ->
            val selected = item == category
            view.setTextColor(if (selected) Color.WHITE else MENU_SECONDARY)
            view.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            view.background = if (selected) {
                GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(Color.argb(56, 127, 205, 154))
                }
            } else {
                null
            }
        }
        settingsTitleView?.text = getString(category.title)
        settingsSubtitleView?.text = getString(category.subtitle)
        val page = settingsPageView ?: return
        page.removeAllViews()
        when (category) {
            SettingsCategory.CONNECTION -> buildConnectionPage(page)
            SettingsCategory.DISPLAY -> buildDisplayPage(page)
            SettingsCategory.AUDIO -> buildAudioPage(page)
            SettingsCategory.VEHICLE -> buildVehiclePage(page)
            SettingsCategory.ADVANCED -> buildAdvancedPage(page)
            SettingsCategory.DIAGNOSTICS -> buildDiagnosticsPage(page)
        }
        settingsPageScroll?.scrollTo(0, 0)
        updateResolutionMenu()
    }

    /** Adds one setting with an optional plain-language hint under it. */
    private fun addSetting(page: LinearLayout, view: View, hint: String? = null, topMarginDp: Int = 28) {
        page.addView(
            view,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(topMarginDp) },
        )
        if (hint != null) addHint(page, hint)
    }

    private fun addHint(page: LinearLayout, hint: String) {
        page.addView(
            menuText(hint, 14f, MENU_SECONDARY).apply { setLineSpacing(0f, 1.15f) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(6) },
        )
    }

    private fun addGroupHeader(page: LinearLayout, title: String) {
        page.addView(
            settingsCategoryHeader(title),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(if (page.childCount == 0) 8 else 40) },
        )
    }

    /** A secondary action inside a settings page, sized to its label. */
    private fun styleSecondaryButton(button: Button) = button.apply {
        isAllCaps = false
        textSize = 17f
        setTextColor(Color.WHITE)
        backgroundTintList = ColorStateList.valueOf(MENU_TRACK_OFF)
        minHeight = dp(52)
        minWidth = dp(220)
        setPadding(dp(24), 0, dp(24), 0)
    }

    private fun addButton(page: LinearLayout, button: Button, hint: String?) {
        page.addView(
            styleSecondaryButton(button),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(28) },
        )
        if (hint != null) addHint(page, hint)
    }

    private fun buildConnectionPage(page: LinearLayout) {
        addSetting(
            page,
            settingsSwitchRow(
                label = getString(R.string.wireless_carplay),
                checked = wirelessEnabled,
                description = getString(R.string.wireless_carplay_switch_desc),
            ) { checked ->
                if (wirelessEnabled == checked) return@settingsSwitchRow
                wirelessEnabled = checked
                hotspotStatus = HotspotStatus(state = if (wirelessEnabled) "stopped" else "off")
                updateHotspotStatusBlock()
                appendLog(
                    "Wireless CarPlay ${if (wirelessEnabled) "enabled" else "disabled"}; " +
                        "applies when settings close",
                )
                requestStartupPrerequisites()
                updateResolutionMenu()
            },
            topMarginDp = 8,
        )
        addSetting(page, buildHotspotModeSection(), getString(R.string.hint_hotspot_mode))
        addSetting(
            page,
            settingsSwitchRow(
                label = getString(R.string.bluetooth_handoff_calls_label),
                checked = BluetoothHandoffSettings.callsEnabled(this),
                description = getString(R.string.bluetooth_handoff_calls_desc),
            ) { checked -> BluetoothHandoffSettings.setCallsEnabled(this, checked) },
        )
        addSetting(
            page,
            settingsSwitchRow(
                label = getString(R.string.bluetooth_handoff_audio_label),
                checked = BluetoothHandoffSettings.audioEnabled(this),
                description = getString(R.string.bluetooth_handoff_audio_desc),
            ) { checked -> BluetoothHandoffSettings.setAudioEnabled(this, checked) },
            getString(R.string.bluetooth_handoff_note),
            topMarginDp = 12,
        )
        addSetting(
            page,
            settingsSwitchRow(
                label = getString(R.string.wifi_scan_pause_label),
                checked = WifiScanPauseSettings.enabled(this),
                description = getString(R.string.wifi_scan_pause_desc),
            ) { checked -> WifiScanPauseSettings.setEnabled(this, checked) },
            getString(R.string.wifi_scan_pause_note),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            addSetting(
                page,
                settingsChoiceRow(
                    getString(R.string.wifi_p2p_channel_label),
                    listOf(P2pChannelPreference.AUTOMATIC to getString(R.string.wifi_p2p_channel_auto)) +
                        P2pChannelPreference.CHANNELS.map { channel ->
                            channel to getString(
                                R.string.wifi_p2p_channel_value,
                                channel,
                                P2pChannelPreference.frequency(channel) ?: 0,
                            )
                        },
                    wifiP2pChannel,
                ) { channel ->
                    if (wifiP2pChannel == channel) return@settingsChoiceRow
                    wifiP2pChannel = channel
                    appendLog(
                        "Wi-Fi P2P channel: ${P2pChannelPreference.describe(channel)}; applies when settings close",
                    )
                    updateResolutionMenu()
                },
                getString(R.string.hint_wifi_p2p_channel),
            )
        }
        addSetting(page, menuText(getString(R.string.hotspot_status), 18f, MENU_SECONDARY), topMarginDp = 20)
        val hotspotStatusView = menuText("", 16f, MENU_ACCENT)
        addSetting(page, hotspotStatusView, topMarginDp = 6)
        this.hotspotStatusView = hotspotStatusView
        updateHotspotStatusBlock()
        addSetting(page, buildMfiTargetSection(), getString(R.string.hint_mfi))
        addSetting(
            page,
            settingsSwitchRow(
                label = getString(R.string.auto_start_label),
                checked = autoStartOnBoot,
                description = getString(R.string.auto_start_desc),
            ) { checked ->
                autoStartOnBoot = checked
                appendLog("Boot auto-start ${if (checked) "enabled" else "disabled"}")
            },
        )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            addGroupHeader(page, getString(R.string.cat_android9))
            addSetting(page, menuText(getString(R.string.android9_note), 15f, MENU_SECONDARY), topMarginDp = 10)
        }
    }

    private fun buildDisplayPage(page: LinearLayout) {
        addSetting(page, buildIconSizeSection(), topMarginDp = 8)
        addSetting(page, buildResolutionSection(), getString(R.string.hint_resolution))
        addSetting(page, buildDrivingSideSection(), getString(R.string.hint_driving_side))
        addSetting(page, buildFullscreenSection())
    }

    private fun buildAudioPage(page: LinearLayout) {
        addSetting(
            page,
            buildStepSliderSection(
                title = getString(R.string.main_media_buffer),
                values = (
                    MainMediaAudioBuffer.MIN_DURATION_MS..MainMediaAudioBuffer.MAX_DURATION_MS
                        step MainMediaAudioBuffer.STEP_DURATION_MS
                    ).toList(),
                selectedValue = mainMediaAudioBufferDurationMs,
                label = ::mainMediaAudioBufferLabel,
                onControlsCreated = { valueView, seekBar ->
                    mainMediaAudioBufferValueView = valueView
                    mainMediaAudioBufferSeekBar = seekBar
                },
                onValueChanged = { value -> mainMediaAudioBufferDurationMs = value },
            ),
            getString(R.string.hint_music_buffer),
            topMarginDp = 8,
        )
        addSetting(page, buildMicrophoneGainSection(), getString(R.string.hint_mic_gain))
        if (sessionKeptForSettings) {
            microphoneTestButton?.isEnabled = false
            microphoneLevelValueView?.text = getString(R.string.mic_test_while_connected)
        } else {
            microphoneTestButton?.isEnabled = !handshakeResetInProgress
        }
    }

    private fun buildVehiclePage(page: LinearLayout) {
        addSetting(
            page,
            settingsInputRow(
                getString(R.string.vehicle_name_label),
                customVehicleName,
                onInputCreated = { input ->
                    input.hint = VehicleName.bluetoothName(this) ?: VehicleName.DEFAULT
                },
            ) { value ->
                customVehicleName = value
                updateResolutionMenu()
            },
            getString(R.string.hint_vehicle_name),
            topMarginDp = 8,
        )
        addSetting(page, buildLocationReportingSection())
        if (BydSettingsAvailability.available(this)) {
            addGroupHeader(page, getString(R.string.byd_settings_header))
            addSetting(
                page,
                settingsSwitchRow(
                    label = getString(R.string.byd_hide_call_label),
                    checked = BydCallUiSettings.enabled(this),
                    description = getString(R.string.byd_hide_call_desc),
                ) { checked -> BydCallUiSettings.setEnabled(this, checked) },
                getString(R.string.byd_hide_call_note),
                topMarginDp = 12,
            )
            addSetting(page, buildBydVehicleDataSection())
            buildHeadUnitBluetoothSection(page)
            addSetting(
                page,
                settingsSwitchRow(
                    label = getString(R.string.cluster_song_label),
                    checked = BydClusterSongSettings.enabled(this),
                    description = getString(R.string.cluster_song_desc),
                ) { checked -> BydClusterSongSettings.setEnabled(this, checked) },
                topMarginDp = 12,
            )
            addSetting(
                page,
                settingsSwitchRow(
                    label = getString(R.string.cluster_song_on_change_label),
                    checked = BydClusterSongSettings.onlyOnChange(this),
                    description = getString(R.string.cluster_song_on_change_desc),
                ) { checked -> BydClusterSongSettings.setOnlyOnChange(this, checked) },
                getString(R.string.cluster_song_note),
                topMarginDp = 12,
            )
            addButton(page, buildClusterCallTestButton(), getString(R.string.cluster_call_test_note))
        }
        addButton(
            page,
            Button(this).apply {
                text = getString(R.string.open_bt_settings)
                setOnClickListener { openSystemBluetoothSettings() }
            },
            getString(R.string.hint_bt_settings),
        )
    }

    /** BYD group: sends the cluster a test call (name and timer) the way BYD's phone app does. */
    private fun buildClusterCallTestButton(): Button = Button(this).apply {
        text = getString(R.string.cluster_call_test)
        setOnClickListener {
            isEnabled = false
            text = getString(R.string.cluster_call_test_running)
            appendLog("Cluster call-info test started")
            kotlin.concurrent.thread(name = "xcertplay-cluster-call-test", isDaemon = true) {
                val result = runCatching { BydVehicleAccess.callInfoTest(applicationContext) }
                runOnUiThread {
                    isEnabled = true
                    text = getString(R.string.cluster_call_test)
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    val message = result.fold({ (access, lines) ->
                        when (access) {
                            LocalAdb.Access.READY -> {
                                lines.forEach { appendLog("Cluster call-info test: $it") }
                                getString(R.string.cluster_call_test_done, lines.joinToString("\n"))
                            }
                            LocalAdb.Access.NOT_APPROVED -> getString(R.string.byd_adb_not_approved)
                            LocalAdb.Access.UNREACHABLE -> getString(R.string.byd_adb_unreachable)
                            LocalAdb.Access.UNSUPPORTED -> getString(R.string.byd_adb_unsupported)
                        }
                    }, { getString(R.string.byd_adb_failed, it.javaClass.simpleName) })
                    AlertDialog.Builder(this@CarPlayHostActivity).setTitle(R.string.cluster_call_test)
                        .setMessage(message).setPositiveButton(android.R.string.ok, null).show()
                }
            }
        }
    }

    /** BYD group: reads the head unit's real Bluetooth address through ADB so the iPhone receives it. */
    private fun buildHeadUnitBluetoothSection(page: LinearLayout) {
        addSetting(page, menuText(getString(R.string.head_unit_bt_header), 18f, Color.WHITE))
        val status = menuText("", 16f, Color.WHITE)
        fun refreshStatus() {
            val ids = AccessoryIds.of(this, airPlayIdentity)
            status.text = when (ids.bluetoothSource) {
                AccessoryIds.BluetoothSource.SAVED -> getString(R.string.head_unit_bt_saved, ids.bluetoothId)
                AccessoryIds.BluetoothSource.SYSTEM -> getString(R.string.head_unit_bt_system, ids.bluetoothId)
                AccessoryIds.BluetoothSource.DEVICE_ID -> getString(R.string.head_unit_bt_generated, ids.bluetoothId)
            }
        }
        refreshStatus()
        addSetting(page, status, topMarginDp = 8)
        val readButton = Button(this).apply { text = getString(R.string.head_unit_bt_read) }
        readButton.setOnClickListener {
            readButton.isEnabled = false
            readButton.text = getString(R.string.head_unit_bt_reading)
            kotlin.concurrent.thread(name = "xcertplay-bt-address", isDaemon = true) {
                val result = runCatching { AccessoryIds.readThroughAdb(applicationContext) }
                runOnUiThread {
                    readButton.isEnabled = true
                    readButton.text = getString(R.string.head_unit_bt_read)
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    val message = result.fold({ (access, address) ->
                        when (access) {
                            LocalAdb.Access.READY -> if (address != null) {
                                AirPlayPersistence.saveHeadUnitBluetoothAddress(this, address)
                                appendLog("Head-unit Bluetooth address read through ADB: $address")
                                getString(R.string.head_unit_bt_read_ok, address)
                            } else {
                                appendLog("Head-unit Bluetooth address: ADB returned no valid address")
                                getString(R.string.head_unit_bt_read_empty)
                            }
                            LocalAdb.Access.NOT_APPROVED -> getString(R.string.byd_adb_not_approved)
                            LocalAdb.Access.UNREACHABLE -> getString(R.string.byd_adb_unreachable)
                            LocalAdb.Access.UNSUPPORTED -> getString(R.string.byd_adb_unsupported)
                        }
                    }, { getString(R.string.byd_adb_failed, it.javaClass.simpleName) })
                    refreshStatus()
                    AlertDialog.Builder(this).setTitle(R.string.head_unit_bt_header).setMessage(message)
                        .setPositiveButton(android.R.string.ok, null).show()
                }
            }
        }
        addButton(page, readButton, null)
        addButton(
            page,
            Button(this).apply {
                text = getString(R.string.head_unit_bt_clear)
                setOnClickListener {
                    AirPlayPersistence.saveHeadUnitBluetoothAddress(this@CarPlayHostActivity, null)
                    appendLog("Head-unit Bluetooth address cleared")
                    refreshStatus()
                }
            },
            getString(R.string.head_unit_bt_note),
        )
    }

    private fun buildAdvancedPage(page: LinearLayout) {
        addGroupHeader(page, getString(R.string.group_video))
        addSetting(
            page,
            settingsSwitchRow(
                label = getString(R.string.hevc_label),
                checked = hevcEnabled,
                description = getString(R.string.hevc_switch_desc),
            ) { checked ->
                if (hevcEnabled == checked) return@settingsSwitchRow
                hevcEnabled = checked
                appendLog("HEVC (H.265) ${if (hevcEnabled) "enabled" else "disabled"}; applies when settings close")
                updateResolutionMenu()
            },
            topMarginDp = 12,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            addSetting(
                page,
                settingsSwitchRow(
                    label = getString(R.string.hevc_sw),
                    checked = hevcSoftwareDecoderEnabled,
                    description = getString(R.string.hevc_sw_desc),
                ) { checked ->
                    if (hevcSoftwareDecoderEnabled == checked) return@settingsSwitchRow
                    hevcSoftwareDecoderEnabled = checked
                    appendLog(
                        "HEVC software decoder ${if (checked) "enabled" else "disabled"}; applies when settings close",
                    )
                    updateResolutionMenu()
                },
            )
        }
        addSetting(
            page,
            buildStepSliderSection(
                title = getString(R.string.frame_rate),
                values = (
                    AirPlayDisplaySettings.MIN_FPS..AirPlayDisplaySettings.MAX_FPS
                        step AirPlayDisplaySettings.FPS_STEP
                    ).toList(),
                selectedValue = fps,
                label = { "$it fps" },
                onValueChanged = { value ->
                    fps = value
                    updateResolutionMenu()
                },
            ),
            getString(R.string.hint_frame_rate),
        )

        addGroupHeader(page, getString(R.string.group_display_size))
        addSetting(
            page,
            settingsChoiceRow(
                label = getString(R.string.physical_basis),
                options = listOf(
                    AirPlayPhysicalSizeBasis.WIDTH to getString(R.string.basis_width),
                    AirPlayPhysicalSizeBasis.HEIGHT to getString(R.string.basis_height),
                ),
                selected = physicalSizeBasis,
            ) { value ->
                physicalSizeBasis = value
                updateResolutionMenu()
            },
            topMarginDp = 12,
        )
        addSetting(
            page,
            buildStepSliderSection(
                title = getString(R.string.physical_length),
                values = (
                    AirPlayDisplaySettings.MIN_WIDTH_PHYSICAL_MM..AirPlayDisplaySettings.MAX_WIDTH_PHYSICAL_MM
                        step AirPlayDisplaySettings.WIDTH_PHYSICAL_MM_STEP
                    ).toList(),
                selectedValue = widthPhysicalMm,
                label = { "$it mm" },
                onValueChanged = { value ->
                    widthPhysicalMm = value
                    updateResolutionMenu()
                },
            ),
            getString(R.string.hint_physical),
            topMarginDp = 20,
        )

        addGroupHeader(page, getString(R.string.group_display_area))
        addSetting(page, buildSafeAreaSection(), topMarginDp = 12)

        addGroupHeader(page, getString(R.string.group_audio_routing))
        addSetting(
            page,
            buildVehicleAudioChannelRow(
                getString(R.string.media_audio_channel_label),
                { mediaAudioChannel },
                { mediaAudioChannel = it },
                AudioAttributes.USAGE_MEDIA,
            ),
            topMarginDp = 12,
        )
        addSetting(
            page,
            buildVehicleAudioChannelRow(
                getString(R.string.navigation_audio_channel_label),
                { navigationAudioChannel },
                { navigationAudioChannel = it },
                AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
            ),
            topMarginDp = 10,
        )
        addSetting(
            page,
            buildVehicleAudioChannelRow(
                getString(R.string.phone_audio_channel_label),
                { phoneAudioChannel },
                { phoneAudioChannel = it },
                AudioAttributes.USAGE_VOICE_COMMUNICATION,
            ),
            getString(R.string.audio_channel_note, VehicleAudioChannel.MAX) + "\n" +
                getString(R.string.phone_audio_channel_note),
            topMarginDp = 10,
        )
        if (advancedAudioChannelMappingSupported) {
            addSetting(
                page,
                settingsSwitchRow(
                    label = getString(R.string.advanced_mapping_label),
                    checked = advancedAudioChannelMapping,
                    description = getString(R.string.advanced_mapping_desc),
                ) { checked ->
                    advancedAudioChannelMapping = checked
                    appendLog(
                        "Advanced audio channel mapping ${if (checked) "enabled" else "disabled"}; " +
                            "applies when settings close",
                    )
                    updateResolutionMenu()
                },
            )
        }

        addGroupHeader(page, getString(R.string.group_identity))
        addSetting(
            page,
            settingsInputRow(getString(R.string.manufacturer), manufacturer) { value ->
                manufacturer = value
                updateResolutionMenu()
            },
            topMarginDp = 12,
        )
        addSetting(
            page,
            settingsInputRow(getString(R.string.model), model) { value ->
                model = value
                updateResolutionMenu()
            },
            topMarginDp = 10,
        )
        addSetting(page, buildAirPlayIconSection())

        addGroupHeader(page, getString(R.string.group_other))
        addSetting(
            page,
            settingsSwitchRow(
                label = getString(R.string.more_gestures_label),
                checked = moreGesturesToSettings,
                description = getString(R.string.more_gestures_desc),
            ) { checked -> moreGesturesToSettings = checked },
            topMarginDp = 12,
        )
        addSetting(
            page,
            settingsSwitchRow(
                label = getString(R.string.keep_session_label),
                checked = keepSessionOnWindowShrink,
                description = getString(R.string.keep_session_desc),
            ) { checked -> keepSessionOnWindowShrink = checked },
            getString(R.string.keep_session_note),
        )
    }

    private fun buildDiagnosticsPage(page: LinearLayout) {
        addSetting(page, buildDebugLogsSection(), topMarginDp = 8)
        addSetting(page, buildMediaMetricsSection(), topMarginDp = 20)
        addSetting(page, buildAudioPacketCaptureSection(), topMarginDp = 20)
        addButton(page, buildExportLogsButton(), getString(R.string.export_logs_note))
        addButton(page, buildWifiScanDiagnosticsButton(), getString(R.string.wifi_scan_diag_note))
        addGroupHeader(page, getString(R.string.technical_params))
        val preview = menuText("", 15f, MENU_SECONDARY).apply { setLineSpacing(0f, 1.2f) }
        addSetting(page, preview, topMarginDp = 12)
        resolutionPreviewView = preview
    }

    /** Writes who scans Wi-Fi, read through ADB, to the session log. */
    private fun buildWifiScanDiagnosticsButton(): Button = Button(this).apply {
        text = getString(R.string.wifi_scan_diag)
        setOnClickListener {
            isEnabled = false
            text = getString(R.string.wifi_scan_diag_running)
            kotlin.concurrent.thread(name = "xcertplay-wifi-scan-diag", isDaemon = true) {
                val result = runCatching { WifiScanDiagnostics.collect(applicationContext) }
                runOnUiThread {
                    isEnabled = true
                    text = getString(R.string.wifi_scan_diag)
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    val message = result.fold({ (access, lines) ->
                        when (access) {
                            LocalAdb.Access.READY -> {
                                appendLog("Wi-Fi scan diagnostics (${lines.size} lines):")
                                lines.forEach { appendLog("WifiScanDiag| $it") }
                                getString(R.string.wifi_scan_diag_done, lines.size)
                            }
                            LocalAdb.Access.NOT_APPROVED -> getString(R.string.byd_adb_not_approved)
                            LocalAdb.Access.UNREACHABLE -> getString(R.string.byd_adb_unreachable)
                            LocalAdb.Access.UNSUPPORTED -> getString(R.string.byd_adb_unsupported)
                        }
                    }, { getString(R.string.byd_adb_failed, it.javaClass.simpleName) })
                    AlertDialog.Builder(this@CarPlayHostActivity).setTitle(R.string.wifi_scan_diag)
                        .setMessage(message).setPositiveButton(android.R.string.ok, null).show()
                }
            }
        }
    }

    /** One line for the settings bottom bar: the values the next connection uses. */
    private fun settingsSummary(): String {
        val native = activeDisplaySize ?: currentActivitySize()
        val negotiated = native?.let {
            CarPlayDisplayScale.apply(
                AirPlayDisplayConfig(widthPixels = it.width, heightPixels = it.height, widthPhysicalMm = widthPhysicalMm, fps = fps),
                displayScaleTenths,
            )
        }
        val width = negotiated?.widthPixels ?: 0
        val height = negotiated?.heightPixels ?: 0
        val icons = getString(
            when {
                physicalSizeBasis != AirPlayPhysicalSizeBasis.WIDTH -> R.string.icon_size_custom_label
                widthPhysicalMm == ICON_SIZE_LARGE_MM -> R.string.icon_size_large
                widthPhysicalMm == ICON_SIZE_MEDIUM_MM -> R.string.icon_size_medium
                widthPhysicalMm == ICON_SIZE_SMALL_MM -> R.string.icon_size_small
                else -> R.string.icon_size_custom_label
            },
        )
        return if (wirelessEnabled) {
            getString(R.string.settings_summary_wireless, hotspotModeName(wirelessHotspotMode), width, height, fps, icons)
        } else {
            getString(R.string.settings_summary_wired, width, height, fps, icons)
        }
    }

    private fun buildIconSizeSection(): View {
        val section = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        section.addView(menuText(getString(R.string.icon_size), 20f, MENU_SECONDARY))
        val presets = listOf(
            ICON_SIZE_LARGE_MM to getString(R.string.icon_size_large),
            ICON_SIZE_MEDIUM_MM to getString(R.string.icon_size_medium),
            ICON_SIZE_SMALL_MM to getString(R.string.icon_size_small),
        )
        val customNote = menuText("", 14f, MENU_ACCENT)
        val group = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        fun current(): Int? = widthPhysicalMm.takeIf {
            physicalSizeBasis == AirPlayPhysicalSizeBasis.WIDTH && presets.any { (mm, _) -> mm == it }
        }
        fun refreshNote() {
            val custom = current() == null
            customNote.visibility = if (custom) View.VISIBLE else View.GONE
            customNote.text = getString(R.string.icon_size_custom, widthPhysicalMm)
        }
        for ((mm, label) in presets) {
            val button = RadioButton(this).apply {
                id = View.generateViewId()
                text = label
                textSize = 18f
                tag = mm
                setTextColor(MENU_SECONDARY)
                buttonTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(MENU_ACCENT, MENU_SECONDARY),
                )
                isChecked = current() == mm
            }
            group.addView(button, RadioGroup.LayoutParams(dp(120), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        group.setOnCheckedChangeListener { radioGroup, checkedId ->
            val mm = radioGroup.findViewById<RadioButton>(checkedId)?.tag as? Int ?: return@setOnCheckedChangeListener
            physicalSizeBasis = AirPlayPhysicalSizeBasis.WIDTH
            widthPhysicalMm = mm
            refreshNote()
            updateResolutionMenu()
        }
        section.addView(group)
        section.addView(customNote, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(6) })
        section.addView(menuText(getString(R.string.icon_size_hint), 14f, MENU_SECONDARY), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(6) })
        refreshNote()
        return section
    }

    private fun buildResolutionSection(): View {
        val section = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            menuText(getString(R.string.resolution), 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val value = menuText(CarPlayDisplayScale.label(displayScaleTenths), 24f, MENU_ACCENT, bold = true)
        header.addView(value)
        section.addView(header)
        section.addView(
            SeekBar(this).apply {
                max = CarPlayDisplayScale.MAX_TENTHS - CarPlayDisplayScale.MIN_TENTHS
                progress = displayScaleTenths - CarPlayDisplayScale.MIN_TENTHS
                splitTrack = false
                progressTintList = ColorStateList.valueOf(MENU_ACCENT)
                thumbTintList = ColorStateList.valueOf(MENU_ACCENT)
                progressBackgroundTintList = ColorStateList.valueOf(MENU_TRACK_OFF)
                setOnSeekBarChangeListener(
                    object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                            displayScaleTenths = CarPlayDisplayScale.sanitize(CarPlayDisplayScale.MIN_TENTHS + progress)
                            updateResolutionMenu()
                        }

                        override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                        override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
                    },
                )
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(8) },
        )
        val range = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        range.addView(menuText("0.3x", 15f, MENU_SECONDARY), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        range.addView(menuText("1.0x", 15f, MENU_SECONDARY))
        section.addView(range)
        resolutionValueView = value
        return section
    }

    private fun persistMenuSettings() {
        AirPlayPersistence.saveWirelessEnabled(this, wirelessEnabled)
        AirPlayPersistence.saveMfiTarget(this, mfiTarget)
        AirPlayPersistence.saveMfiI2cPath(this, mfiI2cPath)
        AirPlayPersistence.saveRemoteMfiServer(this, remoteMfiServer)
        AirPlayPersistence.saveRemoteMfiToken(this, remoteMfiToken)
        AirPlayPersistence.saveLocalMfiCertificateUri(this, localMfiCertificateUri)
        AirPlayPersistence.saveLocalMfiPrivateKeyUri(this, localMfiPrivateKeyUri)
        AirPlayPersistence.saveWirelessHotspotMode(this, wirelessHotspotMode)
        AirPlayPersistence.saveManualHotspotSsid(this, manualHotspotSsid)
        AirPlayPersistence.saveManualHotspotPassphrase(this, manualHotspotPassphrase)
        AirPlayPersistence.saveManualHotspotBand(this, manualHotspotBand)
        AirPlayPersistence.saveManualHotspotChannel(this, manualHotspotChannel)
        AirPlayPersistence.saveWifiP2pChannel(this, wifiP2pChannel)
        AirPlayPersistence.saveManualHotspotSecurity(this, manualHotspotSecurity)
        AirPlayPersistence.saveLocationReportingEnabled(this, locationReportingEnabled)
        AirPlayPersistence.saveAutoStartOnBoot(this, autoStartOnBoot)
        AirPlayPersistence.saveAdvancedAudioChannelMapping(this, advancedAudioChannelMapping)
        AirPlayPersistence.saveMediaAudioChannel(this, mediaAudioChannel)
        AirPlayPersistence.saveNavigationAudioChannel(this, navigationAudioChannel)
        AirPlayPersistence.savePhoneAudioChannel(this, phoneAudioChannel)
        AirPlayPersistence.saveMainMediaAudioBufferDurationMs(
            this,
            mainMediaAudioBufferDurationMs,
        )
        AirPlayPersistence.saveMicrophoneGainPercent(this, microphoneGainPercent)
        AirPlayPersistence.saveDisplayScaleTenths(this, displayScaleTenths)
        AirPlayPersistence.saveFps(this, fps)
        AirPlayPersistence.saveWidthPhysicalMm(this, widthPhysicalMm)
        AirPlayPersistence.savePhysicalSizeBasis(this, physicalSizeBasis)
        AirPlayPersistence.saveHevcEnabled(this, hevcEnabled)
        AirPlayPersistence.saveHevcSoftwareDecoderEnabled(this, hevcSoftwareDecoderEnabled)
        AirPlayPersistence.saveManufacturer(this, manufacturer)
        AirPlayPersistence.saveModel(this, model)
        AirPlayPersistence.saveCustomVehicleName(this, customVehicleName)
        AirPlayPersistence.saveDebugLogsEnabled(this, debugLogsEnabled)
        AirPlayPersistence.saveMediaMetricsEnabled(this, mediaMetricsEnabled)
        AirPlayPersistence.saveAudioPacketCaptureEnabled(this, audioPacketCaptureEnabled)
        AirPlayPersistence.saveMoreGesturesToSettings(this, moreGesturesToSettings)
        AirPlayPersistence.saveKeepSessionOnWindowShrink(this, keepSessionOnWindowShrink)
        AirPlayPersistence.saveRightHandDrive(this, rightHandDrive)
        AirPlayPersistence.saveHideTopBar(this, hideTopBar)
        AirPlayPersistence.saveHideBottomBar(this, hideBottomBar)
        AirPlayPersistence.saveSafeAreaDrawOutside(this, safeAreaDrawOutside)
    }

    private fun captureSettingsBaseline(): SettingsBaseline {
        val safeAreaSize = currentActivitySize()
        val customIconBytes = try {
            AirPlayPersistence.loadCustomAirPlayIconFile(this)?.readBytes()
        } catch (error: Exception) {
            Log.w(TAG, "Could not read the current AirPlay icon for settings rollback", error)
            null
        }
        return SettingsBaseline(
            safeAreaSize = safeAreaSize,
            safeAreaRect = safeAreaSize?.let {
                AirPlayPersistence.loadSafeAreaRect(this, it.width, it.height)
            },
            customIconBytes = customIconBytes,
            handshake = handshakeSettings(),
        )
    }

    /**
     * Every setting the next connection reads when it starts. If any differs after the settings
     * page closes, the session must be renegotiated; otherwise the running session is kept.
     */
    private fun handshakeSettings(): List<Any?> {
        val size = currentActivitySize()
        return listOf(
            wirelessEnabled, mfiTarget, mfiI2cPath, remoteMfiServer, remoteMfiToken,
            localMfiCertificateUri, localMfiPrivateKeyUri,
            wirelessHotspotMode, manualHotspotSsid, manualHotspotPassphrase, manualHotspotBand,
            manualHotspotChannel, manualHotspotSecurity, wifiP2pChannel,
            locationReportingEnabled, customVehicleName, manufacturer, model,
            displayScaleTenths, fps, widthPhysicalMm, physicalSizeBasis, hevcEnabled, hevcSoftwareDecoderEnabled,
            rightHandDrive, hideTopBar, hideBottomBar, safeAreaDrawOutside,
            size?.let { AirPlayPersistence.loadSafeAreaRect(this, it.width, it.height) },
            runCatching { AirPlayPersistence.loadCustomAirPlayIconFile(this)?.readBytes()?.contentHashCode() }
                .getOrNull(),
            audioPacketCaptureEnabled,
            advancedAudioChannelMapping, mediaAudioChannel, navigationAudioChannel, phoneAudioChannel,
            mainMediaAudioBufferDurationMs, microphoneGainPercent,
            BydVehicleSettings.speedEnabled(this), BydVehicleSettings.batteryEnabled(this),
            BydVehicleSettings.dcChargingEnabled(this), BydVehicleSettings.capacityKwh(this),
        )
    }

    private fun restoreSettingsBaseline() {
        val baseline = settingsBaseline ?: return
        loadPersistedSettings()
        baseline.safeAreaSize?.let { size ->
            baseline.safeAreaRect?.let { rect ->
                AirPlayPersistence.saveSafeAreaRect(
                    this,
                    size.width,
                    size.height,
                    rect,
                    commit = true,
                )
            } ?: AirPlayPersistence.clearSafeAreaRect(
                this,
                size.width,
                size.height,
                commit = true,
            )
        }
        try {
            baseline.customIconBytes?.let { bytes ->
                AirPlayPersistence.saveCustomAirPlayIcon(this, bytes)
            } ?: AirPlayPersistence.clearCustomAirPlayIcon(this)
        } catch (error: Exception) {
            Log.w(TAG, "Could not restore the previous AirPlay icon", error)
        }
        settingsBaseline = null
        locationPermissionAvailable = hasFineLocationPermission()
        hotspotStatus = HotspotStatus(state = if (wirelessEnabled) "stopped" else "off")
        syncMfiSettingsControls()
        syncMainMediaAudioBufferControls()
        syncMicrophoneGainControls()
        updateManualHotspotFields()
        updateAirPlayIconPreview()
        updateSafeAreaSummary()
        updateHotspotStatusBlock()
        updateResolutionMenu()
        updateDebugOverlays()
        applyFullscreenMode()
        refreshDisplaySizeAfterLayout()
    }

    private fun buildMfiTargetSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val targetChoice = settingsChoiceRow(
            label = getString(R.string.mfi_target),
            options = listOf(
                MfiTarget.USB_CH341 to "USB/CH341",
                MfiTarget.I2C to "I2C",
                MfiTarget.REMOTE to getString(R.string.mfi_remote),
                MfiTarget.LOCAL_FILES to getString(R.string.mfi_local),
            ),
            selected = mfiTarget,
        ) { target ->
            if (mfiTarget == target) return@settingsChoiceRow
            mfiTarget = target
            updateMfiTargetFields()
            appendLog("MFI target: ${mfiTargetLabel(target)}; applies when settings close")
        }
        mfiTargetGroup = (targetChoice as ViewGroup).getChildAt(1) as RadioGroup
        section.addView(
            targetChoice,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val i2cFields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                settingsInputRow(
                    getString(R.string.i2c_device),
                    mfiI2cPath,
                    onInputCreated = { mfiI2cPathInput = it },
                ) { value ->
                    mfiI2cPath = value
                    mfiErrorView?.visibility = View.GONE
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                menuText(getString(R.string.i2c_hint), 14f, MENU_SECONDARY),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(4) },
            )
        }
        section.addView(
            i2cFields,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        mfiI2cFields = i2cFields

        val remoteFields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                settingsInputRow(
                    getString(R.string.server_address),
                    remoteMfiServer,
                    onInputCreated = { remoteMfiServerInput = it },
                ) { value ->
                    remoteMfiServer = value
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                settingsInputRow(
                    getString(R.string.token_optional),
                    remoteMfiToken,
                    password = true,
                    onInputCreated = { remoteMfiTokenInput = it },
                ) { value ->
                    remoteMfiToken = value
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(8) },
            )
            addView(
                menuText(
                    getString(R.string.remote_note),
                    14f,
                    MENU_SECONDARY,
                ),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(4) },
            )
        }
        section.addView(
            remoteFields,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        mfiRemoteFields = remoteFields

        val localFields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                localMfiDocumentRow(
                    getString(R.string.cert_p7b),
                    localMfiCertificateUri,
                    onDocumentViewCreated = { localMfiCertificateDocumentView = it },
                ) {
                    externalActivityInProgress = true
                    localMfiCertificatePicker.launch(arrayOf("*/*"))
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                localMfiDocumentRow(
                    getString(R.string.key_pk8),
                    localMfiPrivateKeyUri,
                    onDocumentViewCreated = { localMfiPrivateKeyDocumentView = it },
                ) {
                    externalActivityInProgress = true
                    localMfiPrivateKeyPicker.launch(arrayOf("*/*"))
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(8) },
            )
            addView(
                menuText(
                    getString(R.string.local_files_note),
                    14f,
                    MENU_SECONDARY,
                ),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(4) },
            )
        }
        section.addView(
            localFields,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        mfiLocalFields = localFields
        val error = menuText("", 14f, MENU_DANGER).apply {
            visibility = View.GONE
        }
        section.addView(
            error,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) },
        )
        mfiErrorView = error
        updateMfiTargetFields()
        return section
    }

    private fun updateMfiTargetFields() {
        mfiI2cFields?.visibility = if (mfiTarget == MfiTarget.I2C) View.VISIBLE else View.GONE
        mfiRemoteFields?.visibility = if (mfiTarget == MfiTarget.REMOTE) View.VISIBLE else View.GONE
        mfiLocalFields?.visibility = if (mfiTarget == MfiTarget.LOCAL_FILES) View.VISIBLE else View.GONE
        mfiErrorView?.visibility = View.GONE
    }

    private fun localMfiDocumentRow(
        label: String,
        uri: String,
        onDocumentViewCreated: (TextView) -> Unit,
        onChoose: () -> Unit,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(
            menuText(label, 15f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val row = LinearLayout(this@CarPlayHostActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val documentView = menuText(localMfiDocumentLabel(uri), 14f, MENU_SECONDARY)
        onDocumentViewCreated(documentView)
        row.addView(
            documentView,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        row.addView(
            Button(this@CarPlayHostActivity).apply {
                text = getString(R.string.choose)
                isAllCaps = false
                contentDescription = getString(R.string.choose_desc, label)
                setOnClickListener { onChoose() }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(12) },
        )
        addView(
            row,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(4) },
        )
    }

    private fun updateLocalMfiDocumentViews() {
        localMfiCertificateDocumentView?.text = localMfiDocumentLabel(localMfiCertificateUri)
        localMfiPrivateKeyDocumentView?.text = localMfiDocumentLabel(localMfiPrivateKeyUri)
    }

    private fun localMfiDocumentLabel(value: String): String {
        if (value.isEmpty()) return getString(R.string.not_selected)
        val uri = try {
            Uri.parse(value)
        } catch (_: Exception) {
            return getString(R.string.selection_unavailable)
        }
        return try {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0) cursor.getString(column) else null
            } ?: uri.lastPathSegment ?: getString(R.string.selected_document)
        } catch (_: Exception) {
            uri.lastPathSegment ?: getString(R.string.selected_document)
        }
    }

    private fun retainDocumentReadPermission(uri: Uri, label: String): Boolean = try {
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        true
    } catch (failure: SecurityException) {
        Log.e(TAG, "Could not retain local MFi $label document permission", failure)
        Toast.makeText(
            this,
            getString(R.string.keep_access_failed, label),
            Toast.LENGTH_LONG,
        ).show()
        false
    }

    private fun syncMfiSettingsControls() {
        mfiTargetGroup?.let { group ->
            val button = (0 until group.childCount)
                .map { group.getChildAt(it) }
                .filterIsInstance<RadioButton>()
                .firstOrNull { it.tag == mfiTarget }
            button?.let { group.check(it.id) }
        }
        if (mfiI2cPathInput?.text?.toString() != mfiI2cPath) {
            mfiI2cPathInput?.setText(mfiI2cPath)
        }
        if (remoteMfiServerInput?.text?.toString() != remoteMfiServer) {
            remoteMfiServerInput?.setText(remoteMfiServer)
        }
        if (remoteMfiTokenInput?.text?.toString() != remoteMfiToken) {
            remoteMfiTokenInput?.setText(remoteMfiToken)
        }
        updateLocalMfiDocumentViews()
        updateMfiTargetFields()
    }

    private fun mfiTargetLabel(target: MfiTarget): String = when (target) {
        MfiTarget.USB_CH341 -> "USB/CH341"
        MfiTarget.I2C -> "I2C"
        MfiTarget.REMOTE -> getString(R.string.mfi_remote)
        MfiTarget.LOCAL_FILES -> getString(R.string.mfi_local)
    }

    private fun settingsCategoryHeader(title: String): TextView =
        menuText(title, 16f, MENU_ACCENT, bold = true)

    private fun buildLocationReportingSection(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val row = LinearLayout(this@CarPlayHostActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(
                menuText(getString(R.string.report_location), 19f, Color.WHITE),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            val switch = Switch(this@CarPlayHostActivity).apply {
                isChecked = locationReportingEnabled
                contentDescription = getString(R.string.report_location_desc)
                showText = false
                thumbTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(MENU_ACCENT, MENU_SECONDARY),
                )
                trackTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(MENU_ACCENT_TRACK, MENU_TRACK_OFF),
                )
                setOnCheckedChangeListener { _, checked ->
                    onLocationReportingChanged(checked)
                }
            }
            locationReportingSwitch = switch
            row.addView(
                switch,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                menuText(
                    getString(R.string.report_location_note),
                    14f,
                    MENU_SECONDARY,
                ),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(6) },
            )
        }

    private fun onLocationReportingChanged(checked: Boolean) {
        if (locationReportingEnabled == checked) return
        locationReportingEnabled = checked
        appendLog(
            "Location reporting ${if (locationReportingEnabled) "enabled" else "disabled"}; " +
                "applies when settings close",
        )
        updateResolutionMenu()
        if (locationReportingEnabled && !locationPermissionAvailable) {
            requestLocationPermission()
        }
    }

    private fun buildDebugLogsSection(): View =
        settingsSwitchRow(
            label = getString(R.string.debug_logs),
            checked = debugLogsEnabled,
            description = getString(R.string.debug_logs_desc),
        ) { checked ->
            debugLogsEnabled = checked
            if (!checked) clearScreenLogs()
            appendLog("Debug logs ${if (debugLogsEnabled) "enabled" else "disabled"}")
            updateDebugOverlays()
        }

    private fun buildMediaMetricsSection(): View =
        settingsSwitchRow(
            label = getString(R.string.latency_monitor),
            checked = mediaMetricsEnabled,
            description = getString(R.string.latency_monitor_desc),
        ) { checked ->
            mediaMetricsEnabled = checked
            appendLog("Media latency monitor ${if (checked) "enabled" else "disabled"}")
            updateMediaMetricsOverlay()
        }

    private fun buildAudioPacketCaptureSection(): View =
        settingsSwitchRow(
            label = getString(R.string.audio_capture),
            checked = audioPacketCaptureEnabled,
            description = getString(R.string.audio_capture_desc),
        ) { checked ->
            audioPacketCaptureEnabled = checked
            appendLog(
                "Audio diagnostic capture ${if (checked) "enabled" else "disabled"}; " +
                    "applies when settings close",
            )
        }

    private fun buildExportLogsButton(): Button = Button(this).apply {
        text = getString(R.string.export_logs)
        isAllCaps = false
        textSize = 17f
        setTextColor(MENU_BUTTON_TEXT)
        backgroundTintList = ColorStateList.valueOf(MENU_ACCENT)
        minHeight = dp(52)
        setOnClickListener { button ->
            button.isEnabled = false
            text = getString(R.string.export_logs_running)
            val logFile = sessionLog?.file
            kotlin.concurrent.thread(name = "xcertplay-log-export", isDaemon = true) {
                val result = runCatching { LogExporter.export(applicationContext, logFile) }
                result.exceptionOrNull()?.let { Log.w(TAG, "log export failed", it) }
                runOnUiThread {
                    button.isEnabled = true
                    text = getString(R.string.export_logs)
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    val message = result.fold(
                        { getString(R.string.export_logs_done, it.fileCount, it.location) },
                        { getString(R.string.export_logs_failed, it.message ?: it.javaClass.simpleName) },
                    )
                    Toast.makeText(this@CarPlayHostActivity, message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun buildBydVehicleDataSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        fun addSwitch(label: String, checked: Boolean, note: String, onChanged: (Boolean) -> Unit) {
            section.addView(
                settingsSwitchRow(label = label, checked = checked, description = note, onChanged = onChanged),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(10) },
            )
            section.addView(menuText(note, 14f, MENU_SECONDARY))
        }
        section.addView(menuText(getString(R.string.byd_vehicle_data_title), 20f, MENU_SECONDARY))
        addSwitch(
            getString(R.string.byd_vehicle_speed_label),
            BydVehicleSettings.speedEnabled(this),
            getString(R.string.byd_vehicle_speed_note),
        ) { BydVehicleSettings.setSpeedEnabled(this, it) }
        addSwitch(
            getString(R.string.byd_vehicle_battery_label),
            BydVehicleSettings.batteryEnabled(this),
            getString(R.string.byd_vehicle_battery_note),
        ) { BydVehicleSettings.setBatteryEnabled(this, it) }
        addSwitch(
            getString(R.string.byd_vehicle_dc_label),
            BydVehicleSettings.dcChargingEnabled(this),
            getString(R.string.byd_vehicle_dc_note),
        ) { BydVehicleSettings.setDcChargingEnabled(this, it) }
        val capacity = BydVehicleSettings.capacityKwh(this)
        section.addView(
            settingsInputRow(
                getString(R.string.byd_vehicle_capacity_label),
                if (capacity == 0.0) "0" else String.format(Locale.US, "%.2f", capacity),
            ) { value ->
                val number = value.trim().toDoubleOrNull()
                if (number != null && number.isFinite() && number in 0.0..200.0) {
                    BydVehicleSettings.setCapacityKwh(this, number)
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
        section.addView(
            menuText(getString(R.string.byd_vehicle_capacity_note), 14f, MENU_SECONDARY),
        )
        val checkButton = Button(this).apply {
            text = getString(R.string.byd_adb_check)
            isAllCaps = false
            textSize = 16f
            setTextColor(MENU_BUTTON_TEXT)
            backgroundTintList = ColorStateList.valueOf(MENU_ACCENT)
        }
        checkButton.setOnClickListener {
            checkButton.isEnabled = false
            checkButton.text = getString(R.string.byd_adb_checking)
            kotlin.concurrent.thread(name = "xcertplay-byd-check", isDaemon = true) {
                val result = runCatching { BydVehicleAccess.check(applicationContext) }
                runOnUiThread {
                    checkButton.isEnabled = true
                    checkButton.text = getString(R.string.byd_adb_check)
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    val message = result.fold({ checked ->
                        when (checked.access) {
                            LocalAdb.Access.READY -> {
                                fun state(available: Boolean) = getString(
                                    if (available) R.string.byd_adb_data_available else R.string.byd_adb_data_unavailable,
                                )
                                getString(
                                    R.string.byd_adb_ready,
                                    state(checked.speedAvailable),
                                    state(checked.batteryAvailable),
                                ) + if (checked.details.isBlank()) "" else "\n\n${checked.details}"
                            }
                            LocalAdb.Access.NOT_APPROVED -> getString(R.string.byd_adb_not_approved)
                            LocalAdb.Access.UNREACHABLE -> getString(R.string.byd_adb_unreachable)
                            LocalAdb.Access.UNSUPPORTED -> getString(R.string.byd_adb_unsupported)
                        }
                    }, { getString(R.string.byd_adb_failed, it.javaClass.simpleName) })
                    AlertDialog.Builder(this).setTitle(R.string.byd_adb_check).setMessage(message)
                        .setPositiveButton(android.R.string.ok, null).show()
                }
            }
        }
        section.addView(
            checkButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
        section.addView(
            menuText(getString(R.string.byd_adb_note), 14f, MENU_SECONDARY),
        )
        return section
    }

    /** [usage] is what CarPlay plays on this channel; the test tone uses it when the channel is automatic. */
    private fun buildVehicleAudioChannelRow(
        label: String,
        current: () -> Int,
        update: (Int) -> Unit,
        usage: Int,
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            settingsInputRow(label, current().toString(), numeric = true, signed = true) { value ->
                update(VehicleAudioChannel.sanitize(value.trim().toIntOrNull() ?: VehicleAudioChannel.AUTOMATIC))
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        row.addView(
            Button(this).apply {
                text = getString(R.string.audio_channel_test)
                isAllCaps = false
                textSize = 16f
                setTextColor(MENU_BUTTON_TEXT)
                backgroundTintList = ColorStateList.valueOf(MENU_ACCENT)
                minWidth = dp(78)
                contentDescription = getString(R.string.audio_channel_test_desc, label)
                setOnClickListener { channelPreview.play(current(), usage) }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(12) },
        )
        return row
    }

    private fun buildMicrophoneGainSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            menuText(getString(R.string.mic_gain_title), 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val gainValue = menuText(
            microphoneGainLabel(microphoneGainPercent),
            22f,
            MENU_ACCENT,
            bold = true,
        )
        microphoneGainValueView = gainValue
        header.addView(
            gainValue,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        section.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val gainSlider = SeekBar(this).apply {
            max = (MicrophoneGain.MAX_PERCENT - MicrophoneGain.MIN_PERCENT) /
                MicrophoneGain.STEP_PERCENT
            progress = (microphoneGainPercent - MicrophoneGain.MIN_PERCENT) /
                MicrophoneGain.STEP_PERCENT
            splitTrack = false
            progressTintList = ColorStateList.valueOf(MENU_ACCENT)
            thumbTintList = ColorStateList.valueOf(MENU_ACCENT)
                progressBackgroundTintList = ColorStateList.valueOf(MENU_TRACK_OFF)
            contentDescription = getString(R.string.mic_gain)
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: SeekBar,
                        progress: Int,
                        fromUser: Boolean,
                    ) {
                        val percent = MicrophoneGain.sanitize(
                            MicrophoneGain.MIN_PERCENT +
                                progress * MicrophoneGain.STEP_PERCENT,
                        )
                        microphoneGainPercent = percent
                        microphoneGainValueView?.text = microphoneGainLabel(percent)
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
                },
            )
        }
        microphoneGainSeekBar = gainSlider
        controls.addView(
            gainSlider,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val testButton = Button(this).apply {
            text = getString(R.string.audio_channel_test)
            isAllCaps = false
            textSize = 16f
            setTextColor(MENU_BUTTON_TEXT)
            backgroundTintList = ColorStateList.valueOf(MENU_ACCENT)
            minWidth = dp(78)
            contentDescription = getString(R.string.mic_test_desc)
            setOnClickListener {
                if (microphoneLevelMonitor?.isRunning == true) {
                    stopMicrophoneGainTest()
                } else {
                    startMicrophoneGainTest()
                }
            }
        }
        microphoneTestButton = testButton
        controls.addView(
            testButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = dp(12) },
        )
        section.addView(
            controls,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) },
        )

        val levelHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        levelHeader.addView(
            menuText(getString(R.string.peak_level), 15f, MENU_SECONDARY),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val levelValue = menuText("0%", 15f, MENU_SECONDARY)
        microphoneLevelValueView = levelValue
        levelHeader.addView(
            levelValue,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        section.addView(
            levelHeader,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        val levelBar = ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal,
        ).apply {
            max = 100
            progress = 0
            progressTintList = ColorStateList.valueOf(MENU_ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(MENU_TRACK_OFF)
            contentDescription = getString(R.string.mic_peak_desc)
        }
        microphoneLevelBar = levelBar
        section.addView(
            levelBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(14),
            ).apply { topMargin = dp(4) },
        )
        return section
    }

    private fun mainMediaAudioBufferLabel(durationMs: Int): String =
        String.format(
            Locale.US,
            "%d ms",
            MainMediaAudioBuffer.sanitizeDurationMs(durationMs),
        )

    private fun microphoneGainLabel(percent: Int): String =
        String.format(Locale.US, "%.1fx", MicrophoneGain.sanitize(percent) / 100.0)

    private fun syncMainMediaAudioBufferControls() {
        val durationMs = MainMediaAudioBuffer.sanitizeDurationMs(mainMediaAudioBufferDurationMs)
        mainMediaAudioBufferDurationMs = durationMs
        mainMediaAudioBufferValueView?.text = mainMediaAudioBufferLabel(durationMs)
        mainMediaAudioBufferSeekBar?.progress =
            (durationMs - MainMediaAudioBuffer.MIN_DURATION_MS) /
                MainMediaAudioBuffer.STEP_DURATION_MS
    }

    private fun syncMicrophoneGainControls() {
        microphoneGainValueView?.text = microphoneGainLabel(microphoneGainPercent)
        microphoneGainSeekBar?.progress =
            (microphoneGainPercent - MicrophoneGain.MIN_PERCENT) /
                MicrophoneGain.STEP_PERCENT
    }

    private fun startMicrophoneGainTest() {
        if (!menuOpen || handshakeResetInProgress || sessionKeptForSettings || microphoneLevelMonitor != null) return
        if (!microphoneAvailable) {
            microphoneGainTestAfterPermission = true
            microphoneLevelValueView?.text = getString(R.string.permission_required)
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        lateinit var monitor: MicrophoneLevelMonitor
        monitor = MicrophoneLevelMonitor(
            context = applicationContext,
            gainPercent = { microphoneGainPercent },
            onPeakPercent = { peak ->
                runOnUiThread {
                    if (microphoneLevelMonitor !== monitor || !menuOpen || isDestroyed) {
                        return@runOnUiThread
                    }
                    microphoneLevelBar?.progress = peak
                    microphoneLevelValueView?.text = "$peak%"
                }
            },
            onStopped = { error ->
                runOnUiThread {
                    if (microphoneLevelMonitor !== monitor) return@runOnUiThread
                    microphoneLevelMonitor = null
                    microphoneTestButton?.text = getString(R.string.audio_channel_test)
                    microphoneTestButton?.isEnabled = menuOpen && !handshakeResetInProgress && !sessionKeptForSettings
                    if (error != null) {
                        Log.e(TAG, "microphone gain test failed", error)
                        microphoneLevelBar?.progress = 0
                        microphoneLevelValueView?.text =
                            error.message ?: getString(R.string.test_failed)
                    }
                }
            },
        )
        microphoneLevelMonitor = monitor
        microphoneTestButton?.text = getString(R.string.stop)
        microphoneLevelBar?.progress = 0
        microphoneLevelValueView?.text = getString(R.string.listening)
        if (!monitor.start()) {
            microphoneLevelMonitor = null
            microphoneTestButton?.text = getString(R.string.audio_channel_test)
        }
    }

    private fun stopMicrophoneGainTest() {
        microphoneGainTestAfterPermission = false
        val monitor = microphoneLevelMonitor
        microphoneLevelMonitor = null
        monitor?.close()
        microphoneTestButton?.text = getString(R.string.audio_channel_test)
        microphoneLevelBar?.progress = 0
        microphoneLevelValueView?.text = "0%"
    }

    private fun openSystemBluetoothSettings() {
        // BYD's Bluetooth page declares BLUETOOTH_SETTINGS without CATEGORY_DEFAULT, so the
        // implicit intent finds nothing there; address its activity directly when it exists.
        val byd = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).setComponent(BYD_BLUETOOTH_SETTINGS)
        val intent = if (byd.resolveActivity(packageManager) != null) byd else Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        appendLog("Opening Bluetooth settings: ${intent.component?.flattenToShortString() ?: "Android standard page"}")
        try {
            startActivity(intent)
        } catch (error: ActivityNotFoundException) {
            appendLog("System Bluetooth settings are unavailable: ${error.message}")
            Toast.makeText(this, getString(R.string.bt_settings_unavailable), Toast.LENGTH_LONG).show()
        } catch (error: SecurityException) {
            appendLog("Cannot open system Bluetooth settings: ${error.message}")
            Toast.makeText(this, getString(R.string.bt_settings_failed), Toast.LENGTH_LONG).show()
        }
    }

    private fun buildStepSliderSection(
        title: String,
        values: List<Int>,
        selectedValue: Int,
        label: (Int) -> String,
        onControlsCreated: ((TextView, SeekBar) -> Unit)? = null,
        onValueChanged: (Int) -> Unit,
    ): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            menuText(title, 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val selectedIndex = values.indexOf(selectedValue)
            .takeIf { it >= 0 }
            ?: 0
        val valueView = menuText(label(values[selectedIndex]), 22f, MENU_ACCENT, bold = true)
        header.addView(
            valueView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        section.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val seekBar = SeekBar(this).apply {
            max = (values.size - 1).coerceAtLeast(0)
            progress = selectedIndex
            splitTrack = false
            progressTintList = ColorStateList.valueOf(MENU_ACCENT)
            thumbTintList = ColorStateList.valueOf(MENU_ACCENT)
                progressBackgroundTintList = ColorStateList.valueOf(MENU_TRACK_OFF)
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        val value = values.getOrNull(progress) ?: return
                        valueView.text = label(value)
                        if (fromUser) onValueChanged(value)
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
                },
            )
        }
        section.addView(
            seekBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        onControlsCreated?.invoke(valueView, seekBar)
        return section
    }

    private fun buildAirPlayIconSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            menuText(getString(R.string.airplay_icon), 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(MENU_TRACK_OFF)
            }
        }
        row.addView(
            preview,
            LinearLayout.LayoutParams(dp(72), dp(72)),
        )
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        actions.addView(
            Button(this).apply {
                text = getString(R.string.choose_image)
                isAllCaps = false
                setOnClickListener {
                    externalActivityInProgress = true
                    imagePicker.launch("image/*")
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        actions.addView(
            Button(this).apply {
                text = getString(R.string.default_icon)
                isAllCaps = false
                setOnClickListener {
                    AirPlayPersistence.clearCustomAirPlayIcon(this@CarPlayHostActivity)
                    updateAirPlayIconPreview()
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        row.addView(
            actions,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            ).apply { marginStart = dp(16) },
        )
        section.addView(
            row,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
        val status = menuText("", 14f, MENU_SECONDARY)
        section.addView(
            status,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        iconPreviewView = preview
        iconStatusView = status
        updateAirPlayIconPreview()
        return section
    }

    private fun buildDrivingSideSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            menuText(getString(R.string.driving_side), 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val group = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
        }
        fun sideButton(label: String, checked: Boolean) = RadioButton(this).apply {
            id = View.generateViewId()
            text = label
            textSize = 18f
            setTextColor(MENU_SECONDARY)
            buttonTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(MENU_ACCENT, MENU_SECONDARY),
            )
            isChecked = checked
        }
        val left = sideButton(getString(R.string.lhd), !rightHandDrive)
        val right = sideButton(getString(R.string.rhd), rightHandDrive)
        group.addView(left, RadioGroup.LayoutParams(dp(160), ViewGroup.LayoutParams.WRAP_CONTENT))
        group.addView(right, RadioGroup.LayoutParams(dp(160), ViewGroup.LayoutParams.WRAP_CONTENT))
        group.setOnCheckedChangeListener { _, checkedId ->
            rightHandDrive = checkedId == right.id
            updateResolutionMenu()
        }
        section.addView(
            group,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        return section
    }

    private fun buildFullscreenSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            menuText(getString(R.string.fullscreen), 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        section.addView(
            settingsSwitchRow(
                label = getString(R.string.hide_top),
                checked = hideTopBar,
                description = getString(R.string.hide_top_desc),
            ) { checked ->
                hideTopBar = checked
                applyFullscreenMode()
                refreshDisplaySizeAfterLayout()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
        section.addView(
            settingsSwitchRow(
                label = getString(R.string.hide_bottom),
                checked = hideBottomBar,
                description = getString(R.string.hide_bottom_desc),
            ) { checked ->
                hideBottomBar = checked
                applyFullscreenMode()
                refreshDisplaySizeAfterLayout()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
        return section
    }

    private fun buildSafeAreaSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            menuText(getString(R.string.safe_area), 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val summary = menuText("", 15f, MENU_ACCENT)
        section.addView(
            summary,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) },
        )
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        buttons.addView(
            Button(this).apply {
                text = getString(R.string.set)
                isAllCaps = false
                setOnClickListener { openSafeAreaEditor() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        buttons.addView(
            Button(this).apply {
                text = getString(R.string.reset)
                isAllCaps = false
                setOnClickListener { resetSafeAreaForCurrentSize() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            },
        )
        section.addView(
            buttons,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
        section.addView(
            settingsSwitchRow(
                label = getString(R.string.draw_outside),
                checked = safeAreaDrawOutside,
                description = getString(R.string.draw_outside_desc),
            ) { checked ->
                safeAreaDrawOutside = checked
                updateResolutionMenu()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )
        safeAreaSummaryView = summary
        updateSafeAreaSummary()
        return section
    }

    private fun buildSafeAreaEditor(): View {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
        }
        val editor = SafeAreaEditorView(this)
        overlay.addView(
            editor,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        overlay.addView(
            menuText(getString(R.string.safe_area), 24f, Color.WHITE, bold = true).apply {
                setPadding(dp(16), dp(12), dp(16), dp(8))
            },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            ),
        )
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(16))
        }
        controls.addView(
            Button(this).apply {
                text = getString(R.string.cancel)
                isAllCaps = false
                setOnClickListener { closeSafeAreaEditor() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        controls.addView(
            Button(this).apply {
                text = getString(R.string.save)
                isAllCaps = false
                setOnClickListener { saveSafeAreaEditor() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            },
        )
        overlay.addView(
            controls,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )
        safeAreaEditorView = editor
        return overlay
    }

    private fun settingsInputRow(
        label: String,
        value: String,
        password: Boolean = false,
        numeric: Boolean = false,
        signed: Boolean = false,
        onInputCreated: ((EditText) -> Unit)? = null,
        onChanged: (String) -> Unit,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            menuText(label, 18f, MENU_SECONDARY).apply {
                gravity = Gravity.CENTER_VERTICAL
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        addView(
            EditText(this@CarPlayHostActivity).apply {
                setText(value)
                textSize = 18f
                setTextColor(Color.WHITE)
                setHintTextColor(MENU_SECONDARY)
                backgroundTintList = ColorStateList.valueOf(MENU_ACCENT)
                minHeight = dp(48)
                isSingleLine = true
                inputType = when {
                    numeric && signed -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                    numeric -> InputType.TYPE_CLASS_NUMBER
                    password -> InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_VARIATION_PASSWORD or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                }
                addTextChangedListener(afterTextChanged(onChanged))
                onInputCreated?.invoke(this)
            },
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            ).apply { marginStart = dp(12) },
        )
    }

    private fun settingsSwitchRow(
        label: String,
        checked: Boolean,
        description: String,
        onChanged: (Boolean) -> Unit,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            LinearLayout(this@CarPlayHostActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(menuText(label, 19f, Color.WHITE))
                addView(
                    menuText(description, 14f, MENU_SECONDARY),
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(4) },
                )
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(16) },
        )
        addView(
            Switch(this@CarPlayHostActivity).apply {
                isChecked = checked
                contentDescription = description
                showText = false
                thumbTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(MENU_ACCENT, MENU_SECONDARY),
                )
                trackTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(MENU_ACCENT_TRACK, MENU_TRACK_OFF),
                )
                setOnCheckedChangeListener { _, value -> onChanged(value) }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    private fun afterTextChanged(onChanged: (String) -> Unit): TextWatcher =
        object : TextWatcher {
            override fun beforeTextChanged(
                text: CharSequence?,
                start: Int,
                count: Int,
                after: Int,
            ) = Unit

            override fun onTextChanged(
                text: CharSequence?,
                start: Int,
                before: Int,
                count: Int,
            ) = Unit

            override fun afterTextChanged(text: Editable?) {
                onChanged(text?.toString().orEmpty())
            }
        }

    private fun buildHotspotModeSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            menuText(getString(R.string.wifi_session), 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val group = RadioGroup(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val modes = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(WirelessHotspotMode.WIFI_P2P to getString(R.string.hotspot_mode_p2p_recommended))
            }
            add(WirelessHotspotMode.LOCAL_ONLY_HOTSPOT to getString(R.string.hotspot_mode_local))
            add(WirelessHotspotMode.MANUAL to getString(R.string.manual_hotspot))
        }
        var selectedId = View.NO_ID
        for ((mode, label) in modes) {
            val button = RadioButton(this).apply {
                id = View.generateViewId()
                text = label
                textSize = 18f
                setTextColor(MENU_SECONDARY)
                buttonTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(MENU_ACCENT, MENU_SECONDARY),
                )
                tag = mode
                isChecked = wirelessHotspotMode == mode
            }
            if (wirelessHotspotMode == mode) selectedId = button.id
            group.addView(
                button,
                RadioGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        if (selectedId != View.NO_ID) group.check(selectedId)
        group.setOnCheckedChangeListener { radioGroup, checkedId ->
            val selected = radioGroup.findViewById<RadioButton>(checkedId)
                ?.tag as? WirelessHotspotMode
                ?: return@setOnCheckedChangeListener
            if (wirelessHotspotMode == selected) return@setOnCheckedChangeListener
            wirelessHotspotMode = selected
            hotspotStatus = HotspotStatus(state = if (wirelessEnabled) "stopped" else "off")
            updateHotspotStatusBlock()
            updateManualHotspotFields()
            appendLog(
                "Wi-Fi session mode: ${hotspotModeLabel(wirelessHotspotMode)}; " +
                    "applies when settings close",
            )
        }
        section.addView(
            group,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val manualFields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        manualFields.addView(
            settingsInputRow(getString(R.string.hotspot_ssid), manualHotspotSsid) { value ->
                manualHotspotSsid = value
                manualHotspotErrorView?.visibility = View.GONE
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        manualFields.addView(
            settingsChoiceRow(
                label = getString(R.string.band),
                options = listOf(
                    ManualHotspotBand.AUTO to getString(R.string.auto),
                    ManualHotspotBand.GHZ_2_4 to "2.4 GHz",
                    ManualHotspotBand.GHZ_5 to "5 GHz",
                ),
                selected = manualHotspotBand,
            ) { value ->
                manualHotspotBand = value
                manualHotspotErrorView?.visibility = View.GONE
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )

        manualFields.addView(
            settingsInputRow(
                label = getString(R.string.channel_auto),
                value = manualHotspotChannel.toString(),
                numeric = true,
            ) { value ->
                manualHotspotChannel = value.toIntOrNull() ?: -1
                manualHotspotErrorView?.visibility = View.GONE
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )

        manualFields.addView(
            settingsInputRow(
                label = getString(R.string.hotspot_password),
                value = manualHotspotPassphrase,
                password = true,
            ) { value ->
                manualHotspotPassphrase = value
                manualHotspotErrorView?.visibility = View.GONE
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )

        manualFields.addView(
            settingsChoiceRow(
                label = getString(R.string.security),
                options = listOf(
                    ManualHotspotSecurity.OPEN to getString(R.string.security_open),
                    ManualHotspotSecurity.WPA2 to "WPA2",
                    ManualHotspotSecurity.WPA3_TRANSITION to getString(R.string.wpa3_transition),
                    ManualHotspotSecurity.WPA3 to "WPA3",
                ),
                selected = manualHotspotSecurity,
            ) { value ->
                manualHotspotSecurity = value
                manualHotspotErrorView?.visibility = View.GONE
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )

        val error = menuText("", 14f, Color.rgb(0xff, 0x7a, 0x7a)).apply {
            visibility = View.GONE
        }
        manualFields.addView(
            error,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )

        section.addView(
            manualFields,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        manualHotspotFields = manualFields
        manualHotspotErrorView = error
        updateManualHotspotFields()
        return section
    }

    private fun updateManualHotspotFields() {
        val visible = wirelessHotspotMode == WirelessHotspotMode.MANUAL
        manualHotspotFields?.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) manualHotspotErrorView?.visibility = View.GONE
    }

    private fun validateMfiSettings(): Boolean {
        val error = when {
            mfiTarget == MfiTarget.I2C && mfiI2cPath.isBlank() ->
                getString(R.string.err_i2c_required)
            mfiTarget == MfiTarget.REMOTE && remoteMfiServer.isBlank() ->
                getString(R.string.err_remote_required)
            mfiTarget == MfiTarget.REMOTE &&
                !remoteMfiServer.trim().startsWith("http://") &&
                !remoteMfiServer.trim().startsWith("https://") ->
                getString(R.string.err_remote_scheme)
            mfiTarget == MfiTarget.LOCAL_FILES && localMfiCertificateUri.isBlank() ->
                getString(R.string.err_select_cert)
            mfiTarget == MfiTarget.LOCAL_FILES && localMfiPrivateKeyUri.isBlank() ->
                getString(R.string.err_select_key)
            '\u0000' in mfiI2cPath -> getString(R.string.err_nul, getString(R.string.field_i2c_path))
            '\u0000' in remoteMfiServer -> getString(R.string.err_nul, getString(R.string.field_remote_server))
            '\u0000' in remoteMfiToken -> getString(R.string.err_nul, getString(R.string.field_remote_token))
            '\u0000' in localMfiCertificateUri -> getString(R.string.err_nul, getString(R.string.field_local_cert_uri))
            '\u0000' in localMfiPrivateKeyUri -> getString(R.string.err_nul, getString(R.string.field_local_key_uri))
            else -> null
        }
        mfiErrorView?.text = error.orEmpty()
        mfiErrorView?.visibility = if (error == null) View.GONE else View.VISIBLE
        return error == null
    }

    private fun validateManualHotspotSettings(): Boolean {
        if (wirelessHotspotMode != WirelessHotspotMode.MANUAL) return true
        val error = when {
            manualHotspotSsid.isBlank() -> getString(R.string.err_ssid_required)
            manualHotspotSsid.encodeToByteArray().size > 32 ->
                getString(R.string.err_ssid_length)
            '\u0000' in manualHotspotSsid -> getString(R.string.err_nul, getString(R.string.hotspot_ssid))
            manualHotspotChannel !in 0..196 -> getString(R.string.err_channel_range)
            manualHotspotChannel != 0 &&
                !isManualHotspotChannelCompatible(manualHotspotBand, manualHotspotChannel) ->
                getString(R.string.err_channel_band)
            '\u0000' in manualHotspotPassphrase -> getString(R.string.err_nul, getString(R.string.hotspot_password))
            manualHotspotSecurity == ManualHotspotSecurity.OPEN &&
                manualHotspotPassphrase.isNotEmpty() ->
                getString(R.string.err_open_password)
            manualHotspotSecurity != ManualHotspotSecurity.OPEN &&
                manualHotspotPassphrase.length !in 8..63 ->
                getString(R.string.err_wpa_length)
            else -> null
        }
        manualHotspotErrorView?.text = error.orEmpty()
        manualHotspotErrorView?.visibility = if (error == null) View.GONE else View.VISIBLE
        return error == null
    }

    private fun hotspotModeLabel(mode: WirelessHotspotMode): String = when (mode) {
        WirelessHotspotMode.WIFI_P2P -> "Wi-Fi P2P (5 GHz)"
        WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> "LocalOnlyHotspot"
        WirelessHotspotMode.MANUAL -> getString(R.string.manual_hotspot)
    }

    private fun menuText(
        text: String,
        sizeSp: Float,
        color: Int,
        bold: Boolean = false,
    ): TextView = TextView(this).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        includeFontPadding = false
    }

    private fun updateHotspotStatus(status: CarPlayStatus) {
        if (!wirelessEnabled) return
        hotspotStatus = when (status) {
            CarPlayStatus.StartingHotspot -> HotspotStatus(state = "Starting")
            is CarPlayStatus.HotspotReady -> HotspotStatus(
                state = "Ready",
                ssid = status.ssid,
                band = status.band,
                channel = status.channel,
                backend = status.backend,
            )
            CarPlayStatus.WaitingForPairedIphone ->
                hotspotStatus.copy(state = "Waiting for paired iPhone")
            CarPlayStatus.ConnectingBluetooth ->
                hotspotStatus.copy(state = "Connecting Bluetooth")
            CarPlayStatus.RunningWireless ->
                hotspotStatus.copy(state = "Running")
            CarPlayStatus.WirelessActive ->
                hotspotStatus.copy(state = "Active")
            CarPlayStatus.AttachingNetwork ->
                hotspotStatus.copy(state = "Starting AirPlay service")
            is CarPlayStatus.Failed -> hotspotStatus.copy(state = "Error")
            else -> return
        }
        updateHotspotStatusBlock()
    }

    private fun hotspotStateLabel(state: String): String = when (state) {
        "off" -> getString(R.string.hotspot_state_off)
        "stopped" -> getString(R.string.hotspot_state_stopped)
        "Starting" -> getString(R.string.hotspot_state_starting)
        "Ready" -> getString(R.string.hotspot_state_ready)
        "Waiting for paired iPhone" -> getString(R.string.stage_waiting_paired)
        "Connecting Bluetooth" -> getString(R.string.stage_connecting_bt)
        "Running" -> getString(R.string.hotspot_state_running)
        "Active" -> getString(R.string.hotspot_state_active)
        "Starting AirPlay service" -> getString(R.string.stage_starting_airplay)
        "Error" -> getString(R.string.hotspot_state_error)
        else -> state
    }

    private fun updateHotspotStatusBlock() {
        if (!wirelessEnabled) {
            hotspotStatusView?.text = getString(R.string.hotspot_off)
            return
        }
        val status = hotspotStatus
        hotspotStatusView?.text = buildString {
            append(getString(R.string.hotspot_line, hotspotStateLabel(status.state)))
            status.ssid?.let { append("\nSSID: ").append(it) }
            status.backend?.let { append('\n').append(getString(R.string.hotspot_backend)).append(": ").append(it) }
            status.band?.let { append('\n').append(getString(R.string.band)).append(": ").append(it) }
            status.channel?.let {
                append('\n').append(getString(R.string.channel)).append(": ")
                    .append(if (it == 0) getString(R.string.auto) else it.toString())
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> settingsChoiceRow(
        label: String,
        options: List<Pair<T, String>>,
        selected: T,
        onSelected: (T) -> Unit,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(
            menuText(label, 18f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val group = RadioGroup(this@CarPlayHostActivity).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        var selectedId = View.NO_ID
        for ((value, text) in options) {
            val button = RadioButton(this@CarPlayHostActivity).apply {
                id = View.generateViewId()
                this.text = text
                textSize = 17f
                setTextColor(MENU_SECONDARY)
                buttonTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(MENU_ACCENT, MENU_SECONDARY),
                )
                tag = value
                isChecked = value == selected
            }
            if (value == selected) selectedId = button.id
            group.addView(
                button,
                RadioGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        if (selectedId != View.NO_ID) group.check(selectedId)
        group.setOnCheckedChangeListener { radioGroup, checkedId ->
            val value = radioGroup.findViewById<RadioButton>(checkedId)?.tag as? T ?: return@setOnCheckedChangeListener
            onSelected(value)
        }
        addView(
            group,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    private fun updateResolutionMenu() {
        resolutionValueView?.text = CarPlayDisplayScale.label(displayScaleTenths)
        settingsSummaryView?.text = settingsSummary()
        val native = activeDisplaySize ?: currentActivitySize()
        val resolution = if (native == null) {
            getString(R.string.preview_handshake_waiting)
        } else {
            val negotiated = CarPlayDisplayScale.apply(
                AirPlayDisplayConfig(
                    widthPixels = native.width,
                    heightPixels = native.height,
                    widthPhysicalMm = widthPhysicalMm,
                    fps = fps,
                ),
                displayScaleTenths,
            )
            getString(
                R.string.preview_handshake,
                native.width,
                native.height,
                negotiated.widthPixels,
                negotiated.heightPixels,
            )
        }
        val transport = if (!hevcEnabled) {
            "H.264"
        } else {
            getString(
                R.string.preview_hevc,
                getString(if (hevcSoftwareDecoderEnabled) R.string.preview_software else R.string.preview_hardware),
            )
        }
        val fullscreen = buildString {
            append(getString(if (hideTopBar) R.string.preview_top_hidden else R.string.preview_top_shown))
            append(", ")
            append(getString(if (hideBottomBar) R.string.preview_bottom_hidden else R.string.preview_bottom_shown))
        }
        resolutionPreviewView?.text = buildString {
            append(resolution).append('\n')
            append(getString(R.string.preview_identity, normalizedManufacturer(), normalizedModel())).append('\n')
            append(getString(R.string.preview_vehicle_name, vehicleName())).append('\n')
            append(getString(R.string.preview_frame_rate, fps)).append('\n')
            append(
                getString(R.string.preview_detected_max, maximumDetectedWidthPixels, maximumDetectedHeightPixels),
            ).append('\n')
            val basis = getString(
                when (physicalSizeBasis) {
                    AirPlayPhysicalSizeBasis.WIDTH -> R.string.preview_widest_width
                    AirPlayPhysicalSizeBasis.HEIGHT -> R.string.preview_longest_height
                },
            )
            append(getString(R.string.preview_physical_reference, basis, widthPhysicalMm)).append('\n')
            native?.let { size ->
                val physical = resolvePhysicalSize(size)
                append(
                    getString(
                        R.string.preview_physical_size,
                        physical.widthMm.toString(),
                        physical.heightMm.toString(),
                    ),
                ).append('\n')
            }
            append(
                getString(
                    R.string.preview_driving_side,
                    getString(if (rightHandDrive) R.string.preview_right else R.string.preview_left),
                ),
            ).append('\n')
            append(getString(R.string.preview_fullscreen, fullscreen)).append('\n')
            append(getString(R.string.preview_video_transport, transport)).append('\n')
            append(
                getString(
                    R.string.preview_location,
                    getString(if (locationReportingEnabled) R.string.preview_enabled else R.string.preview_disabled),
                ),
            ).append('\n')
            if (advancedAudioChannelMappingSupported) {
                append(
                    getString(
                        R.string.preview_audio_mapping,
                        getString(
                            if (advancedAudioChannelMapping) {
                                R.string.preview_aaos_buses
                            } else {
                                R.string.preview_mobile_compatible
                            },
                        ),
                    ),
                ).append('\n')
            }
            append(safeAreaSummary())
        }
    }

    private fun createAirPlayConfig(size: DisplaySize): AirPlayConfig {
        val physical = resolvePhysicalSize(size)
        val baseDisplay = AirPlayDisplayConfig(
            widthPixels = size.width,
            heightPixels = size.height,
            widthPhysicalMm = physical.widthMm,
            heightPhysicalMm = physical.heightMm,
            fps = fps,
        )
        val scaledDisplay = CarPlayDisplayScale.apply(
            baseDisplay,
            displayScaleTenths,
        )
        val display = scaledDisplay.copy(
            safeArea = AirPlaySafeArea.toInsets(
                mapping = AirPlayPersistence.loadSafeAreaRect(this, size.width, size.height),
                activityWidthPixels = size.width,
                activityHeightPixels = size.height,
                displayWidthPixels = scaledDisplay.widthPixels,
                displayHeightPixels = scaledDisplay.heightPixels,
            ),
            safeAreaDrawOutside = safeAreaDrawOutside,
        )
        val accessoryIds = AccessoryIds.of(this, airPlayIdentity)
        return AirPlayConfig(
            deviceName = vehicleName(),
            deviceId = accessoryIds.deviceId,
            btMac = accessoryIds.bluetoothId,
            sourceVersion = "950.7.1",
            main = display,
            rightHandDrive = rightHandDrive,
            hevc = hevcEnabled,
            microphone = microphoneAvailable,
            manufacturer = normalizedManufacturer(),
            model = normalizedModel(),
            oemLabel = vehicleName(),
            icons = listOf(loadAirPlayIcon()),
        )
    }

    /** Shown as the iPhone's car name and on the CarPlay return-to-car icon. */
    private fun vehicleName(): String =
        VehicleName.resolve(customVehicleName, VehicleName.bluetoothName(this))

    private fun loadAirPlayIcon(): AirPlayIcon {
        val customBytes = try {
            AirPlayPersistence.loadCustomAirPlayIconFile(this)?.readBytes()
        } catch (_: Exception) {
            null
        }
        if (customBytes != null) {
            decodeAirPlayIcon(customBytes)?.let { return it }
            AirPlayPersistence.clearCustomAirPlayIcon(this)
        }
        return decodeAirPlayIcon(defaultAirPlayIconBytes())
            ?: throw IllegalStateException("Packaged AirPlay icon is invalid")
    }

    private fun decodeAirPlayIcon(encoded: ByteArray): AirPlayIcon? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
            bounds.outWidth != bounds.outHeight
        ) {
            return null
        }
        return AirPlayIcon(bounds.outWidth, bounds.outHeight, encoded)
    }

    private fun defaultAirPlayIconBytes(): ByteArray =
        // Shown in CarPlay's app list as the "back to the car" button.
        resources.openRawResource(R.raw.ic_car_home).use { it.readBytes() }

    private fun updateAirPlayIconPreview() {
        val preview = iconPreviewView ?: return
        val custom = AirPlayPersistence.loadCustomAirPlayIconFile(this)
        var customBitmap: Bitmap? = null
        if (custom != null) {
            customBitmap = BitmapFactory.decodeFile(custom.absolutePath)
            if (customBitmap == null) {
                AirPlayPersistence.clearCustomAirPlayIcon(this)
            }
        }
        val bitmap = customBitmap ?: BitmapFactory.decodeResource(resources, R.raw.ic_car_home)
        preview.setImageBitmap(bitmap)
        iconStatusView?.text =
            if (customBitmap != null) getString(R.string.icon_custom) else getString(R.string.icon_default)
    }

    private fun currentActivitySize(): DisplaySize? {
        val view = videoView
        if (view != null && view.width > 0 && view.height > 0) {
            return DisplaySize(view.width, view.height)
        }
        return activeDisplaySize
    }

    private fun resolvePhysicalSize(size: DisplaySize): AirPlayPhysicalSizeMm =
        AirPlayDisplaySettings.resolvePhysicalSizeMm(
            currentWidthPixels = size.width,
            currentHeightPixels = size.height,
            maximumWidthPixels = maxOf(maximumDetectedWidthPixels, size.width),
            maximumHeightPixels = maxOf(maximumDetectedHeightPixels, size.height),
            referenceMillimeters = widthPhysicalMm,
            basis = physicalSizeBasis,
        )

    private fun safeAreaSummary(): String {
        val size = currentActivitySize() ?: return getString(R.string.safe_area_waiting)
        val mapping = AirPlayPersistence.loadSafeAreaRect(this, size.width, size.height)
        return if (mapping == null) {
            getString(R.string.safe_area_full, size.width, size.height)
        } else {
            getString(
                R.string.safe_area_custom,
                mapping.width,
                mapping.height,
                mapping.left,
                mapping.top,
                size.width,
                size.height,
            )
        }
    }

    private fun updateSafeAreaSummary() {
        safeAreaSummaryView?.text = safeAreaSummary()
    }

    private fun openSafeAreaEditor() {
        val size = currentActivitySize()
        if (size == null) {
            appendLog("Safe area editor is unavailable before display layout")
            return
        }
        val editorView = safeAreaEditorView ?: return
        val initial = AirPlayPersistence.loadSafeAreaRect(this, size.width, size.height)
            ?: AirPlaySafeArea.default(size.width, size.height)
        safeAreaEditSize = size
        safeAreaEditorActive = true
        // Keep the current activity size; changing system bars here would remap the safe area.
        settingsMenu?.visibility = View.GONE
        safeAreaEditor?.visibility = View.VISIBLE
        editorView.setRect(initial, size.width, size.height)
        appendLog(
            "Safe area editor opened for ${size.width}x${size.height}; " +
                "drag the four boundaries",
        )
    }

    private fun closeSafeAreaEditor() {
        if (!safeAreaEditorActive) return
        safeAreaEditorActive = false
        safeAreaEditSize = null
        safeAreaEditor?.visibility = View.GONE
        settingsMenu?.visibility = View.VISIBLE
        updateSafeAreaSummary()
        updateResolutionMenu()
        appendLog("Safe area editor closed")
    }

    private fun saveSafeAreaEditor() {
        val size = safeAreaEditSize ?: currentActivitySize() ?: return
        val rect = safeAreaEditorView?.currentRectForSource() ?: return
        AirPlayPersistence.saveSafeAreaRect(this, size.width, size.height, rect)
        appendLog(
            "Safe area saved for ${size.width}x${size.height}: " +
                "${rect.width}x${rect.height} at (${rect.left}, ${rect.top})",
        )
        closeSafeAreaEditor()
    }

    private fun resetSafeAreaForCurrentSize() {
        val size = currentActivitySize()
        if (size == null) {
            appendLog("Safe area reset is unavailable before display layout")
            return
        }
        AirPlayPersistence.clearSafeAreaRect(this, size.width, size.height)
        updateSafeAreaSummary()
        updateResolutionMenu()
        appendLog("Safe area reset to full screen for ${size.width}x${size.height}")
    }

    private fun refreshDisplaySizeAfterLayout() {
        videoView?.post {
            val view = videoView ?: return@post
            scheduleDisplaySize(view.width, view.height)
        }
    }

    private fun normalizedManufacturer(): String =
        manufacturer.trim().ifBlank { AirPlayPersistence.DEFAULT_MANUFACTURER }

    private fun normalizedModel(): String =
        model.trim().ifBlank { AirPlayPersistence.DEFAULT_MODEL }

    private fun createMediaSink(
        videoWidth: Int,
        videoHeight: Int,
        controllerGeneration: Int,
    ): AndroidMediaSink = AndroidMediaSink(
        context = applicationContext,
        surface = null,
        videoWidth = videoWidth,
        videoHeight = videoHeight,
        preferSoftwareHevcDecoder = hevcSoftwareDecoderEnabled,
        advancedAudioChannelMapping = advancedAudioChannelMapping,
        mainMediaAudioBufferDurationMs = mainMediaAudioBufferDurationMs,
        microphoneGainPercent = microphoneGainPercent,
        mediaMetricsMonitor = mediaMetricsMonitor,
        onScreenStreamActiveChanged = { type, active ->
            onScreenStreamStateChanged(controllerGeneration, type, active)
        },
        mediaChannel = mediaAudioChannel,
        navigationChannel = navigationAudioChannel,
        phoneChannel = phoneAudioChannel,
        diagnostic = { message -> mainHandler.post { appendLog(message) } },
    )

    private fun createMediaEngine(sink: AndroidMediaSink): CarPlayMediaEngine =
        CarPlayMediaEngine(
            sink = sink,
            microphoneEnabled = microphoneAvailable,
            audioCaptureDirectory = audioCaptureDirectory(),
        )

    private fun createSessionListener(controllerGeneration: Int): AirPlaySessionListener =
        object : AirPlaySessionListener {
            override fun onSessionActive(session: AirPlaySession) {
                runOnUiThread {
                    if (controllerGeneration != restartGeneration) {
                        return@runOnUiThread
                    }
                    appearanceSync.start(session)
                    updateBydCallUi()
                    homeStep = ConnectionStep.CARPLAY
                    homeFailureFresh = false
                    renderHome()
                    if (menuOpen) return@runOnUiThread
                    appendLog("AirPlay session active")
                }
            }

            override fun onSessionEnded(session: AirPlaySession) {
                runOnUiThread {
                    appearanceSync.end(session)
                    if (controller?.hasActiveAirPlaySession() != true) appearanceSync.stop()
                    updateBydCallUi()
                    if (menuOpen && sessionKeptForSettings && controllerGeneration == restartGeneration) {
                        connectionLostWhileSettingsOpen = true
                    }
                    if (menuOpen || controllerGeneration != restartGeneration) {
                        return@runOnUiThread
                    }
                    activeScreenStreamTypes.clear()
                    homeSessionDropped = true
                    resetHomeProgress()
                    setConnectionStage(getString(R.string.stage_session_ended))
                    appendLog("AirPlay session ended; reconnecting from scratch")
                    reconnectAfterLoss("AirPlay session ended")
                }
            }

            override fun onTransportError(message: String) {
                runOnUiThread {
                    if (menuOpen && sessionKeptForSettings && controllerGeneration == restartGeneration) {
                        connectionLostWhileSettingsOpen = true
                    }
                    if (menuOpen || controllerGeneration != restartGeneration) {
                        return@runOnUiThread
                    }
                    activeScreenStreamTypes.clear()
                    setConnectionStage(getString(R.string.stage_transport_error))
                    appendLog("CarPlay transport error: $message; reconnecting from scratch")
                    reconnectAfterLoss("CarPlay transport error: $message")
                }
            }

            override fun onDebugLog(message: String) {
                val now = System.currentTimeMillis()
                appendFileLog(message, now)
                if (!debugLogsEnabled || message.startsWith(PROTOCOL_TRACE_PREFIX)) return
                synchronized(pendingScreenLogsLock) {
                    if (!debugLogsEnabled) return
                    pendingScreenLogs.addLast(PendingLog(controllerGeneration, now, message))
                    if (pendingScreenLogs.size > MAX_SCREEN_LOG_LINES) pendingScreenLogs.removeFirst()
                    if (!screenDrainPosted) {
                        screenDrainPosted = true
                        mainHandler.post(drainScreenLogs)
                    }
                }
            }
        }

    private fun createStatusReporter(
        controllerGeneration: Int,
    ): (CarPlayStatus) -> Unit = { status ->
        if (
            menuOpen && sessionKeptForSettings && controllerGeneration == restartGeneration &&
            status is CarPlayStatus.Failed
        ) {
            connectionLostWhileSettingsOpen = true
        }
        if (!menuOpen && controllerGeneration == restartGeneration) {
            updateHotspotStatus(status)
            trackHomeProgress(status)
            val description = status.describe()
            setConnectionStage(description)
            when (status) {
                is CarPlayStatus.Failed -> reconnectAfterLoss(description)
                else -> Unit
            }
        }
    }

    private fun adoptBackgroundSession(): Boolean {
        val snapshot = CarPlayBackgroundSession.snapshot() ?: return false
        if (snapshot.controller.isClosed()) {
            CarPlayBackgroundSession.clear(snapshot.controller)
            return false
        }
        controller = snapshot.controller
        updateBydCallUi()
        sink = snapshot.sink
        snapshot.sink.setMediaMetricsMonitor(mediaMetricsMonitor)
        if (snapshot.width > 0 && snapshot.height > 0) {
            activeDisplaySize = DisplaySize(snapshot.width, snapshot.height)
        }
        sessionDisplay = snapshot.display
        videoView?.let { updateVideoLayout(it.width, it.height) }
        val generation = restartGeneration
        snapshot.controller.attachUi(
            createSessionListener(generation),
            createStatusReporter(generation),
        )
        if (snapshot.controller.hasActiveAirPlaySession()) appearanceSync.start(snapshot.controller)
        snapshot.sink.setScreenStreamActiveChangedListener { type, active ->
            onScreenStreamStateChanged(restartGeneration, type, active)
        }
        currentSurface?.let(::attachSurface)
        val serviceReused = snapshot.controller.hasActiveAirPlayAttachment()
        appendLog(
            if (serviceReused) {
                "Reusing existing background CarPlay service"
            } else {
                "Reusing existing background CarPlay session"
            },
        )
        setConnectionStage(
            if (serviceReused) {
                getString(R.string.stage_service_running)
            } else {
                getString(R.string.stage_session_running)
            },
        )
        updateDebugOverlays()
        return true
    }

    private fun startCarPlay(size: DisplaySize) {
        resetHomeProgress()
        if (shuttingDown.get() || menuOpen || handshakeResetInProgress || controller != null) return
        val controllerGeneration = restartGeneration
        val config = createRuntimeConfig()
        val airPlayConfig = createAirPlayConfig(size)
        val locationProvider: Iap2LocationProvider? =
            if (config.locationReportingEnabled) {
                val position = AndroidCarPlayLocationProvider(this)
                if (config.identification.vehicleSpeedEnabled) {
                    VehicleSpeedLocationProvider(position, BydWheelSpeedSource(this, ::appendLog))
                } else {
                    position
                }
            } else {
                null
            }
        val vehicleStatusProvider = if (config.identification.vehicleStatusEnabled) {
            BydBatteryProvider(this, ::appendLog)
        } else {
            null
        }
        appendLog(
            "Starting CarPlay controller at ${size.width}x${size.height} -> " +
                "${airPlayConfig.main.widthPixels}x${airPlayConfig.main.heightPixels} " +
                "(${CarPlayDisplayScale.label(displayScaleTenths)}) " +
                "physical=${airPlayConfig.main.widthPhysicalMm}x" +
                "${airPlayConfig.main.heightPhysicalMm}mm " +
                "video=${if (airPlayConfig.hevc) "HEVC" else "H.264"} " +
                "decoder=${if (airPlayConfig.hevc && hevcSoftwareDecoderEnabled) "software" else "hardware"} " +
                "microphone=${airPlayConfig.microphone} " +
                "location=${if (config.locationReportingEnabled) "enabled" else "disabled"} " +
                "mfi=${mfiTargetLabel(config.mfiTarget)}",
        )
        Log.i(
            TAG,
            "starting controller display=${size.width}x${size.height} " +
                "negotiated=${airPlayConfig.main.widthPixels}x${airPlayConfig.main.heightPixels} " +
                "scale=${CarPlayDisplayScale.label(displayScaleTenths)} " +
                "hevc=${airPlayConfig.hevc} " +
                "softwareHevc=${airPlayConfig.hevc && hevcSoftwareDecoderEnabled} " +
                "microphone=${airPlayConfig.microphone} " +
                "location=${config.locationReportingEnabled} " +
                "mfi=${config.mfiTarget}",
        )
        val renderer = createMediaSink(
            videoWidth = airPlayConfig.main.widthPixels,
            videoHeight = airPlayConfig.main.heightPixels,
            controllerGeneration = controllerGeneration,
        )
        sink = renderer
        currentSurface?.let(::attachSurface)
        val media = createMediaEngine(renderer)
        val pairings = AirPlayPersistence.loadPairings(this) { id, key ->
            AirPlayPersistence.savePairing(this, id, key)
        }
        val next = CarPlayController(
            context = this,
            config = config,
            airPlayConfig = airPlayConfig,
            identity = airPlayIdentity,
            pairings = pairings,
            listener = createSessionListener(controllerGeneration),
            media = media,
            reportStatus = createStatusReporter(controllerGeneration),
            loadPairRecord = { AirPlayPersistence.loadLockdownRecord(this) },
            savePairRecord = { record -> AirPlayPersistence.saveLockdownRecord(this, record) },
            clearPairRecord = { AirPlayPersistence.clearLockdownRecord(this) },
            locationProvider = locationProvider,
            vehicleStatusProvider = vehicleStatusProvider,
        )
        controller = next
        // Without the setting every size change renegotiates, exactly as before.
        sessionDisplay = CarPlaySessionDisplay(
            airPlayConfig.main.widthPixels,
            airPlayConfig.main.heightPixels,
            displayRotation(),
            hideTopBar,
            hideBottomBar,
            size.width,
            size.height,
        ).takeIf { keepSessionOnWindowShrink }
        videoView?.let { updateVideoLayout(it.width, it.height) }
        CarPlayBackgroundSession.store(next, renderer, size.width, size.height, sessionDisplay)
        next.start()
    }

    private fun syncAirPlayDarkMode() {
        if (shuttingDown.get()) return
        val target = controller ?: return
        val night = darkMode
        airPlayCommandExecutor.execute {
            if (target.isClosed()) return@execute
            try {
                val sent = target.setNightMode(night)
                Log.i(
                    TAG,
                    "AirPlay dark mode=${if (night) "dark" else "light"} eventChannelReady=$sent",
                )
            } catch (error: Throwable) {
                Log.w(TAG, "Could not send AirPlay dark mode update", error)
            }
        }
    }

    private fun audioCaptureDirectory(): File? {
        if (!audioPacketCaptureEnabled) return null
        return File(filesDir, AUDIO_CAPTURE_DIRECTORY)
    }

    private fun scheduleDisplaySize(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || shuttingDown.get()) return
        val size = DisplaySize(width, height)
        if (size == pendingDisplaySize) return
        if (size == activeDisplaySize && !displayLayoutChanged()) return
        pendingDisplaySize = size
        mainHandler.removeCallbacks(applyDisplaySize)
        mainHandler.postDelayed(applyDisplaySize, DISPLAY_CHANGE_DEBOUNCE_MILLIS)
    }

    private fun applyDisplaySize(size: DisplaySize) {
        val layoutChanged = displayLayoutChanged()
        if (shuttingDown.get() || (size == activeDisplaySize && !layoutChanged)) return
        val previous = activeDisplaySize
        val display = sessionDisplay
        activeDisplaySize = size
        recordDetectedMaximum(size)
        updateResolutionMenu()
        if (previous == null) {
            appendLog("Display detected: ${size.width}x${size.height}")
            maybeStartCarPlay()
        } else if (menuOpen || handshakeResetInProgress) {
            if (sessionKeptForSettings) displayChangedWhileSettingsOpen = true
            appendLog(
                "Display updated while handshake is reset: " +
                    "${previous.width}x${previous.height} -> ${size.width}x${size.height}",
            )
        } else if (
            !layoutChanged &&
            display?.canKeepSession(size.width, size.height, displayRotation(), hideTopBar, hideBottomBar) == true
        ) {
            // Keep camera shrink/restore cycles within the original window connected. If the
            // session started in a camera window, growth beyond it needs a full-size canvas.
            appendLog(
                "Display changed ${previous.width}x${previous.height} -> ${size.width}x${size.height}; " +
                    "keeping CarPlay session canvas=${display.width}x${display.height}",
            )
            videoView?.let { updateVideoLayout(it.width, it.height) }
        } else {
            restartCarPlay(
                getString(R.string.stage_display_changed, previous.width, previous.height, size.width, size.height),
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int = videoView?.display?.rotation ?: windowManager.defaultDisplay.rotation

    /** Only a kept session tracks layout; without it every size change already renegotiates. */
    private fun displayLayoutChanged(): Boolean {
        val display = sessionDisplay ?: return false
        // A narrow window can become taller than it is wide without the screen rotating.
        return display.rotation != displayRotation() ||
            display.hideTopBar != hideTopBar || display.hideBottomBar != hideBottomBar
    }

    /** The part of the view that shows the negotiated canvas; the whole view unless a session was kept. */
    private fun contentRect(viewWidth: Int, viewHeight: Int): CarPlayVideoLayout {
        val display = sessionDisplay
        if (display == null || (viewWidth == display.windowWidth && viewHeight == display.windowHeight)) {
            return CarPlayVideoLayout(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        }
        return CarPlayVideoLayout.fit(display.width, display.height, viewWidth, viewHeight)
    }

    private fun updateVideoLayout(viewWidth: Int, viewHeight: Int) {
        val view = videoView ?: return
        if (viewWidth <= 0 || viewHeight <= 0) return
        val content = contentRect(viewWidth, viewHeight)
        view.setTransform(Matrix().apply {
            setScale(content.width / viewWidth, content.height / viewHeight)
            postTranslate(content.left, content.top)
        })
    }

    private fun recordDetectedMaximum(size: DisplaySize) {
        val width = maxOf(maximumDetectedWidthPixels, size.width)
        val height = maxOf(maximumDetectedHeightPixels, size.height)
        if (width == maximumDetectedWidthPixels && height == maximumDetectedHeightPixels) return
        maximumDetectedWidthPixels = width
        maximumDetectedHeightPixels = height
        AirPlayPersistence.saveMaximumDetectedDisplay(this, width, height)
    }

    private fun maybeStartCarPlay() {
        if (controller == null && adoptBackgroundSession()) return
        val size = activeDisplaySize ?: return
        val transportReady = if (wirelessEnabled) wirelessPermissionsReady else vpnReady
        val locationReady = !locationReportingEnabled || locationPermissionAvailable
        if (
            !transportReady ||
            !locationReady ||
            !microphonePermissionResolved ||
            shuttingDown.get() ||
            menuOpen ||
            handshakeResetInProgress ||
            controller != null
        ) {
            return
        }
        startCarPlay(size)
    }

    private fun reconnectAfterLoss(reason: String) {
        if (shuttingDown.get() || menuOpen || handshakeResetInProgress) return
        if (reconnectScheduled) return
        reconnectScheduled = true
        val generation = restartGeneration
        val delayMillis = if (reason.contains("AirPlay iAP tunnel", ignoreCase = true)) {
            IAP_TUNNEL_RECONNECT_DELAY_MILLIS
        } else {
            RECONNECT_DELAY_MILLIS
        }
        appendLog("$reason; retrying in ${delayMillis}ms")
        mainHandler.postDelayed(
            {
                reconnectScheduled = false
                if (
                    shuttingDown.get() ||
                    menuOpen ||
                    handshakeResetInProgress ||
                    generation != restartGeneration
                ) {
                    return@postDelayed
                }
                restartCarPlay(getString(R.string.stage_reconnecting_after, reason))
            },
            delayMillis,
        )
    }

    /** Full-stack fallback when an AirPlay-only reconnect is unavailable. */
    private fun restartCarPlay(reason: String) {
        if (shuttingDown.get() || menuOpen || handshakeResetInProgress) return
        val size = activeDisplaySize ?: return
        appendLog(reason)
        activeScreenStreamTypes.clear()
        setConnectionStage(reason)
        Log.i(TAG, "$reason; rebuilding stack at ${size.width}x${size.height}")
        val generation = ++restartGeneration
        appearanceSync.stop()
        val oldController = controller
        val oldSink = sink
        CarPlayBackgroundSession.clear(oldController)
        sessionDisplay = null
        controller = null
        updateBydCallUi()
        sink = null
        teardownExecutor.execute {
            oldController?.close()
            oldController?.awaitClosed(CONTROLLER_CLOSE_TIMEOUT_MILLIS)
            oldSink?.close()
            runOnUiThread {
                if (!shuttingDown.get() && generation == restartGeneration) startCarPlay(size)
            }
        }
    }

    private fun openSettingsMenu() {
        if (menuOpen || shuttingDown.get()) return
        stopMicrophoneGainTest()
        settingsBaseline = captureSettingsBaseline()
        menuOpen = true
        sessionKeptForSettings = !handshakeResetInProgress && controller?.hasActiveAirPlaySession() == true
        displayChangedWhileSettingsOpen = false
        connectionLostWhileSettingsOpen = false
        if (sessionKeptForSettings) {
            // Release any held touch; the settings page now covers CarPlay.
            controller?.sendTouch(emptyList())
            updateBydCallUi()
            updateHotspotStatusBlock()
        } else {
            resetHandshakeForSettings()
        }
        gestureOverlay?.visibility = View.GONE
        showSettingsCategory(settingsCategory)
        settingsMenu?.visibility = View.VISIBLE
        syncMainMediaAudioBufferControls()
        syncMicrophoneGainControls()
        microphoneTestButton?.isEnabled = false
        if (sessionKeptForSettings) {
            microphoneLevelValueView?.text = getString(R.string.mic_test_while_connected)
        }
        updateDebugOverlays()
        clearScreenLogs()
        appendLog(
            if (sessionKeptForSettings) {
                "Settings opened; CarPlay session kept"
            } else {
                "Settings opened; CarPlay handshake reset"
            },
        )
        updateResolutionMenu()
    }

    /** Tears the session down so the next start renegotiates with the current settings. */
    private fun resetHandshakeForSettings() {
        // New settings start a fresh attempt; earlier failures no longer describe it.
        homeFailureMessage = null
        homeFailedAttempts = 0
        handshakeResetInProgress = true
        startAfterHandshakeReset = false
        hotspotStatus = HotspotStatus(state = if (wirelessEnabled) "stopped" else "off")
        updateHotspotStatusBlock()
        val generation = ++restartGeneration
        controller?.sendTouch(emptyList())
        val oldController = controller
        val oldSink = sink
        CarPlayBackgroundSession.clear(oldController)
        sessionDisplay = null
        controller = null
        updateBydCallUi()
        sink = null
        activeScreenStreamTypes.clear()
        setConnectionStage(getString(R.string.stage_reconnect_settings))
        teardownExecutor.execute {
            try {
                oldController?.close()
                oldController?.awaitClosed(CONTROLLER_CLOSE_TIMEOUT_MILLIS)
            } finally {
                try {
                    oldSink?.close()
                } finally {
                    runOnUiThread {
                        if (shuttingDown.get() || generation != restartGeneration) {
                            return@runOnUiThread
                        }
                        handshakeResetInProgress = false
                        microphoneTestButton?.isEnabled = menuOpen
                        if (!menuOpen && startAfterHandshakeReset) {
                            startAfterHandshakeReset = false
                            maybeStartCarPlay()
                        }
                    }
                }
            }
        }
    }

    private fun saveSettingsAndReconnect() {
        if (!menuOpen) return
        if (!validateMfiSettings() || !validateManualHotspotSettings()) {
            // The errors belong to the Connection page; show it so they are visible.
            if (settingsCategory != SettingsCategory.CONNECTION) {
                showSettingsCategory(SettingsCategory.CONNECTION)
                validateMfiSettings()
                validateManualHotspotSettings()
            }
            return
        }
        val baselineHandshake = settingsBaseline?.handshake
        persistMenuSettings()
        settingsBaseline = null
        closeSettings("Settings saved", baselineHandshake)
    }

    private fun cancelSettingsEdits() {
        if (!menuOpen) return
        // Some settings (safe area, icon, BYD vehicle data) are stored while editing.
        val baselineHandshake = settingsBaseline?.handshake
        restoreSettingsBaseline()
        closeSettings("Settings changes discarded", baselineHandshake)
    }

    private fun closeSettings(prefix: String, baselineHandshake: List<Any?>?) {
        if (sessionKeptForSettings) {
            sessionKeptForSettings = false
            val reconnectReason = when {
                baselineHandshake == null || handshakeSettings() != baselineHandshake -> "connection settings changed"
                displayChangedWhileSettingsOpen -> "display changed while settings were open"
                connectionLostWhileSettingsOpen || controller?.hasActiveAirPlaySession() != true ->
                    "connection lost while settings were open"
                else -> null
            }
            if (reconnectReason == null) {
                closeSettingsKeepingSession(prefix)
                return
            }
            appendLog("$prefix; $reconnectReason")
            resetHandshakeForSettings()
        }
        finishSettingsMenu(prefix)
    }

    private fun closeSettingsKeepingSession(prefix: String) {
        stopMicrophoneGainTest()
        menuOpen = false
        settingsMenu?.visibility = View.GONE
        gestureOverlay?.visibility = View.VISIBLE
        updateDebugOverlays()
        clearScreenLogs()
        updateBydCallUi()
        appendLog("$prefix; CarPlay session kept")
    }

    private fun finishSettingsMenu(prefix: String) {
        if (!menuOpen) return
        stopMicrophoneGainTest()
        menuOpen = false
        settingsMenu?.visibility = View.GONE
        gestureOverlay?.visibility = View.VISIBLE
        updateDebugOverlays()
        clearScreenLogs()
        appendLog(
            "$prefix; starting a fresh handshake at " +
                "${CarPlayDisplayScale.label(displayScaleTenths)} with " +
                (if (hevcEnabled) "HEVC (H.265)" else "H.264") +
                ", MFI ${mfiTargetLabel(mfiTarget)}" +
                ", Wi-Fi session ${hotspotModeLabel(wirelessHotspotMode)}",
        )
        if (handshakeResetInProgress) {
            startAfterHandshakeReset = true
        } else {
            maybeStartCarPlay()
        }
    }

    private fun exitApplication() {
        if (shuttingDown.get()) return
        restoreSettingsBaseline()
        finishAndRemoveTask()
        shutdown(terminateProcess = true, reason = "settings exit application")
    }

    private fun shutdown(terminateProcess: Boolean, reason: String) {
        if (!shuttingDown.compareAndSet(false, true)) return
        stopMicrophoneGainTest()
        restartGeneration += 1
        appearanceSync.stop()
        mainHandler.removeCallbacks(applyDisplaySize)
        val oldController = controller
        val oldSink = sink
        CarPlayBackgroundSession.clear(oldController)
        sessionDisplay = null
        controller = null
        updateBydCallUi()
        sink = null
        Log.i(TAG, "shutdown reason=$reason terminateProcess=$terminateProcess")
        teardownExecutor.execute {
            oldController?.close()
            val clean = oldController?.awaitClosed(CONTROLLER_CLOSE_TIMEOUT_MILLIS) ?: true
            oldSink?.close()
            airPlayCommandExecutor.shutdown()
            if (terminateProcess) {
                applicationContext.stopService(Intent(applicationContext, CarPlayVpnService::class.java))
            }
            Log.i(TAG, "shutdown complete clean=$clean")
            teardownExecutor.shutdown()
            if (terminateProcess) Process.killProcess(Process.myPid())
        }
    }

    private fun attachSurface(surface: Surface) {
        sink?.setSurface(SCREEN_TYPE_MAIN, surface)
        sink?.setSurface(SCREEN_TYPE_ALT, surface)
    }

    private fun onHostTouch(view: View, event: MotionEvent): Boolean {
        if (menuOpen) return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureSequenceActive = false
                gestureTracking = false
                edgeSettingsGestureCaptured = moreGesturesToSettings &&
                    event.x in 0f..(view.width / 8f) &&
                    event.y in 0f..(view.height / 4f)
                edgeSettingsGestureEligible = edgeSettingsGestureCaptured
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == THREE_FINGER_COUNT && !gestureSequenceActive) {
                    edgeSettingsGestureCaptured = false
                    edgeSettingsGestureEligible = false
                    gestureSequenceActive = true
                    gestureTracking = true
                    gestureStartX = pointerCentroid(event, horizontal = true)
                    gestureStartY = pointerCentroid(event, horizontal = false)
                    controller?.sendTouch(emptyList())
                    appendLog("Three-finger swipe tracking started")
                    return true
                }
            }
        }

        if (edgeSettingsGestureCaptured) {
            when (event.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> edgeSettingsGestureEligible = false
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount != 1 || event.x !in 0f..(view.width / 8f)) {
                        edgeSettingsGestureEligible = false
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val openSettings = edgeSettingsGestureEligible &&
                        event.x in 0f..(view.width / 8f) &&
                        event.y in (view.height * 3f / 4f)..view.height.toFloat()
                    edgeSettingsGestureCaptured = false
                    edgeSettingsGestureEligible = false
                    if (openSettings) openSettingsMenu()
                }
                MotionEvent.ACTION_CANCEL -> {
                    edgeSettingsGestureCaptured = false
                    edgeSettingsGestureEligible = false
                }
            }
            return true
        }

        if (gestureSequenceActive) {
            if (!gestureTracking || event.pointerCount != THREE_FINGER_COUNT) {
                if (event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL
                ) {
                    gestureSequenceActive = false
                    gestureTracking = false
                } else if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
                    gestureTracking = false
                }
                return true
            }
            if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                val deltaX = Math.abs(pointerCentroid(event, horizontal = true) - gestureStartX)
                val deltaY = pointerCentroid(event, horizontal = false) - gestureStartY
                if (
                    deltaY >= dp(THREE_FINGER_SWIPE_DISTANCE_DP) &&
                    deltaY >= deltaX * THREE_FINGER_SWIPE_DIRECTION_RATIO
                ) {
                    gestureSequenceActive = false
                    gestureTracking = false
                    openSettingsMenu()
                    return true
                }
            }
            return true
        }

        val contacts = CarPlayTouchMapper.contacts(event, contentRect(view.width, view.height))
        val queued = controller?.sendTouch(contacts) ?: false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL -> Log.i(
                TAG,
                "touch action=${MotionEvent.actionToString(event.actionMasked)} " +
                    "pointers=${event.pointerCount} queued=$queued",
            )
        }
        return true
    }

    private fun pointerCentroid(event: MotionEvent, horizontal: Boolean): Float {
        var total = 0f
        for (index in 0 until event.pointerCount) {
            total += if (horizontal) event.getX(index) else event.getY(index)
        }
        return total / event.pointerCount
    }

    private fun onScreenStreamStateChanged(generation: Int, type: Int, active: Boolean) {
        runOnUiThread {
            if (shuttingDown.get() || generation != restartGeneration) return@runOnUiThread
            if (active) {
                activeScreenStreamTypes.add(type)
                // CarPlay is on screen: earlier failures no longer apply.
                homeFailureMessage = null
                homeFailedAttempts = 0
                homeSessionDropped = false
            } else {
                activeScreenStreamTypes.remove(type)
            }
            updateDebugOverlays()
        }
    }

    private fun setStatus(message: String) {
        runOnUiThread {
            setConnectionStage(message)
            appendLog(message)
        }
    }

    private fun setConnectionStage(message: String) {
        latestStage = message
        stageStatusView?.text = message
        updateDebugOverlays()
        renderHome()
    }

    private fun renderHome() {
        val home = homeScreen ?: return
        val failure = homeFailureMessage
        val failureKind = failure?.let(ConnectionProgress::failureKind)
        val current = homeStep
        val title = when {
            failure != null -> getString(R.string.home_status_retrying, homeFailedAttempts)
            homeSessionDropped -> getString(R.string.home_status_reconnecting)
            controller == null && current == ConnectionStep.AUTHENTICATION -> getString(R.string.home_status_preparing)
            current == ConnectionStep.PHONE -> getString(R.string.home_status_waiting_phone)
            current == ConnectionStep.CARPLAY -> getString(R.string.home_status_starting)
            else -> getString(R.string.home_status_connecting)
        }
        val steps = ConnectionStep.values().map { step ->
            val state = ConnectionProgress.stateOf(step, current, homeFailureFresh)
            val detail = when {
                // A retry stage repeats the failure text the failure card already shows.
                state == StepState.ACTIVE ->
                    latestStage.takeIf { it.isNotBlank() && (failure == null || failure !in it) }
                state == StepState.DONE && step == ConnectionStep.LINK -> homeLinkDetail
                else -> null
            }
            HomeStep(homeStepTitle(step), detail, state)
        }
        home.render(
            HomeModel(
                title = title,
                hint = getString(if (wirelessEnabled) R.string.home_hint_wireless else R.string.home_hint_wired),
                modeLabel = if (wirelessEnabled) {
                    getString(R.string.home_mode_wireless, hotspotModeName(wirelessHotspotMode))
                } else {
                    getString(R.string.home_mode_wired)
                },
                steps = steps,
                failure = failure?.let {
                    HomeFailure(failureAdvice(checkNotNull(failureKind)), getString(R.string.failure_reason, it))
                },
                showUseLocalHotspot = failureKind == FailureKind.WIFI_P2P && wirelessEnabled &&
                    wirelessHotspotMode == WirelessHotspotMode.WIFI_P2P,
            ),
        )
    }

    private fun homeStepTitle(step: ConnectionStep): String = getString(
        when (step) {
            ConnectionStep.AUTHENTICATION -> R.string.step_authentication
            ConnectionStep.LINK -> if (wirelessEnabled) R.string.step_link_wireless else R.string.step_link_wired
            ConnectionStep.PHONE -> R.string.step_phone
            ConnectionStep.HANDSHAKE -> R.string.step_handshake
            ConnectionStep.CARPLAY -> R.string.step_carplay
        },
    )

    private fun hotspotModeName(mode: WirelessHotspotMode): String = getString(
        when (mode) {
            WirelessHotspotMode.WIFI_P2P -> R.string.hotspot_mode_p2p
            WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> R.string.hotspot_mode_local
            WirelessHotspotMode.MANUAL -> R.string.manual_hotspot
        },
    )

    private fun failureAdvice(kind: FailureKind): String = getString(
        when (kind) {
            FailureKind.AUTHENTICATION -> R.string.failure_authentication
            FailureKind.BLUETOOTH_OFF -> R.string.failure_bluetooth_off
            FailureKind.NO_PAIRED_IPHONE -> R.string.failure_no_paired_iphone
            FailureKind.WIFI_P2P -> R.string.failure_wifi_p2p
            FailureKind.HOTSPOT -> R.string.failure_hotspot
            FailureKind.USB -> R.string.failure_usb
            FailureKind.TIMEOUT -> R.string.failure_timeout
            FailureKind.OTHER -> R.string.failure_other
        },
    )

    /** Feeds a controller stage into the home screen's steps; a failure stays shown until CarPlay connects. */
    private fun trackHomeProgress(status: CarPlayStatus) {
        when (status) {
            is CarPlayStatus.Failed -> {
                homeFailureMessage = status.message
                homeFailureFresh = true
                homeFailedAttempts++
            }
            is CarPlayStatus.HotspotReady -> {
                homeLinkDetail = getString(R.string.step_link_detail, hotspotBackendName(status.backend), status.band, status.channel)
                homeStep = ConnectionStep.PHONE
                homeFailureFresh = false
            }
            else -> ConnectionProgress.stepOf(status)?.let {
                homeStep = it
                homeFailureFresh = false
            }
        }
    }

    private fun hotspotBackendName(backend: String): String = when {
        backend.contains("P2P", ignoreCase = true) -> getString(R.string.hotspot_mode_p2p)
        backend.contains("LocalOnly", ignoreCase = true) -> getString(R.string.hotspot_mode_local)
        else -> getString(R.string.manual_hotspot)
    }

    private fun resetHomeProgress() {
        homeStep = ConnectionStep.AUTHENTICATION
        homeLinkDetail = null
        homeFailureFresh = false
    }

    private fun reconnectFromHome() {
        if (menuOpen || shuttingDown.get()) return
        homeFailedAttempts = 0
        homeFailureMessage = null
        resetHomeProgress()
        appendLog("Reconnect requested from the home screen")
        if (controller != null) {
            restartCarPlay(getString(R.string.stage_manual_reconnect))
        } else {
            requestStartupPrerequisites()
            maybeStartCarPlay()
            renderHome()
        }
    }

    private fun useLocalHotspotFromHome() {
        wirelessHotspotMode = WirelessHotspotMode.LOCAL_ONLY_HOTSPOT
        AirPlayPersistence.saveWirelessHotspotMode(this, wirelessHotspotMode)
        hotspotStatus = HotspotStatus(state = "stopped")
        appendLog("Wi-Fi session mode switched to LocalOnlyHotspot from the home screen")
        reconnectFromHome()
    }

    private fun updateDebugOverlays() {
        val showLogs = debugLogsEnabled && !menuOpen
        statusScrollView?.visibility = if (showLogs) View.VISIBLE else View.GONE
        disconnectedSettingsButton?.visibility =
            if (!menuOpen && activeScreenStreamTypes.isEmpty()) View.VISIBLE else View.GONE
        updateMediaMetricsOverlay()
    }

    private fun updateMediaMetricsOverlay() {
        val root = contentRoot ?: return
        if (!mediaMetricsEnabled) {
            sink?.setMediaMetricsMonitor(null)
            mediaMetricsOverlay?.let(root::removeView)
            mediaMetricsOverlay = null
            mediaMetricsMonitor = null
            return
        }
        val monitor = mediaMetricsMonitor ?: MediaMetricsMonitor().also {
            mediaMetricsMonitor = it
        }
        sink?.setMediaMetricsMonitor(monitor)
        // The frame-rate axis follows the configured target, which the settings menu can change
        // while the overlay stays alive.
        val overlay = mediaMetricsOverlay ?: MediaMetricsOverlayView(
            this,
            monitor,
            fpsAxisMax = { fps.toFloat() },
        ).also {
            mediaMetricsOverlay = it
            val settingsIndex = root.indexOfChild(settingsMenu)
            root.addView(
                it,
                if (settingsIndex >= 0) settingsIndex else root.childCount,
                mediaMetricsOverlayLayoutParams(root.width, root.height),
            )
        }
        overlay.visibility = if (!menuOpen && !safeAreaEditorActive) View.VISIBLE else View.GONE
        updateMediaMetricsOverlayLayout(root.width, root.height)
    }

    private fun updateMediaMetricsOverlayLayout(screenWidth: Int, screenHeight: Int) {
        val overlay = mediaMetricsOverlay ?: return
        val desired = mediaMetricsOverlayLayoutParams(screenWidth, screenHeight)
        val current = overlay.layoutParams
        if (current.width != desired.width || current.height != desired.height) {
            overlay.layoutParams = desired
        }
    }

    private fun mediaMetricsOverlayLayoutParams(
        screenWidth: Int,
        screenHeight: Int,
    ): FrameLayout.LayoutParams {
        val fallback = resources.displayMetrics
        val panelWidth = (screenWidth.takeIf { it > 0 } ?: fallback.widthPixels) / 3
        val panelHeight = (screenHeight.takeIf { it > 0 } ?: fallback.heightPixels) / 3
        return FrameLayout.LayoutParams(panelWidth, panelHeight, Gravity.TOP or Gravity.START)
    }

    private fun appendLog(message: String) {
        val now = System.currentTimeMillis()
        appendFileLog(message, now)
        if (debugLogsEnabled && !menuOpen) appendScreenLog(now, message)
    }

    private fun appendScreenLog(timestampMillis: Long, message: String) {
        logLines.add(timestampMillis, formattedLogLine(message, timestampMillis))
        if (!logRenderScheduled) {
            logRenderScheduled = true
            mainHandler.postDelayed(renderLogLines, LOG_RENDER_INTERVAL_MILLIS)
        }
    }

    private fun appendFileLog(message: String, timestampMillis: Long) =
        sessionLog?.appendTimestamped(message, timestampMillis)

    private fun formattedLogLine(message: String, nowMillis: Long): String =
        "${SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(nowMillis))}  $message"

    private fun initializeSessionLog() {
        val baseDirectory = getExternalFilesDir(null) ?: filesDir
        val logFile = File(File(baseDirectory, "logs"), "xcertplay.log")
        val activeLog = SessionLogFile(logFile)
        runCatching {
            activeLog.reset(
                "xcertplay log started " +
                    "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())} " +
                    "pid=${Process.myPid()} path=${logFile.absolutePath}",
            )
        }
        sessionLog = activeLog
    }

    private fun refreshLogView(nowMillis: Long) {
        if (!debugLogsEnabled || menuOpen) return
        val cutoff = nowMillis - LOG_RETENTION_MILLIS
        logLines.expireBefore(cutoff)
        statusView?.text = logLines.renderedText()
        scrollLogsToBottom()

        mainHandler.removeCallbacks(expireOldLogLines)
        logLines.firstTimestampMillis?.let { oldest ->
            val delay = (oldest + LOG_RETENTION_MILLIS - nowMillis + 1L)
                .coerceAtLeast(1L)
            mainHandler.postDelayed(expireOldLogLines, delay)
        }
    }

    private fun clearScreenLogs() {
        synchronized(pendingScreenLogsLock) {
            pendingScreenLogs.clear()
            screenDrainPosted = false
        }
        mainHandler.removeCallbacks(drainScreenLogs)
        logLines.clear()
        mainHandler.removeCallbacks(expireOldLogLines)
        mainHandler.removeCallbacks(renderLogLines)
        logRenderScheduled = false
        statusView?.text = ""
    }

    private fun scrollLogsToBottom() {
        statusScrollView?.post {
            statusScrollView?.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun applyFullscreenMode() {
        val hideTop = hideTopBar
        val hideBottom = hideBottomBar
        WindowCompat.setDecorFitsSystemWindows(window, !(hideTop && hideBottom))
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (hideTop) {
            controller.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
        if (hideBottom) {
            controller.hide(WindowInsetsCompat.Type.navigationBars())
        } else {
            controller.show(WindowInsetsCompat.Type.navigationBars())
        }
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun CarPlayStatus.describe(): String = when (this) {
        CarPlayStatus.DiscoveringMfi -> getString(R.string.stage_preparing_mfi)
        CarPlayStatus.WaitingForMfi -> getString(R.string.stage_waiting_mfi)
        CarPlayStatus.RequestingMfiPermission -> getString(R.string.stage_mfi_permission)
        CarPlayStatus.MfiReady -> getString(R.string.stage_mfi_ready)
        CarPlayStatus.StartingHotspot -> getString(R.string.stage_starting_hotspot)
        is CarPlayStatus.HotspotReady ->
            getString(
                R.string.stage_hotspot_ready,
                backend,
                ssid,
                band,
                if (channel == 0) getString(R.string.auto) else channel.toString(),
            )
        CarPlayStatus.WaitingForPairedIphone -> getString(R.string.stage_waiting_paired)
        CarPlayStatus.ConnectingBluetooth -> getString(R.string.stage_connecting_bt)
        CarPlayStatus.RunningWireless -> getString(R.string.stage_wireless_running)
        CarPlayStatus.WirelessActive -> getString(R.string.stage_wireless_active)
        CarPlayStatus.DiscoveringIphone -> getString(R.string.stage_discovering_iphone)
        CarPlayStatus.WaitingForIphone -> getString(R.string.stage_waiting_iphone)
        CarPlayStatus.RequestingIphonePermission -> getString(R.string.stage_iphone_permission)
        CarPlayStatus.WaitingForReenumeration -> getString(R.string.stage_reenumeration)
        CarPlayStatus.SelectingConfiguration -> getString(R.string.stage_selecting_config)
        CarPlayStatus.OpeningDataPaths -> getString(R.string.stage_opening_paths)
        CarPlayStatus.Pairing -> getString(R.string.stage_pairing)
        CarPlayStatus.ConnectingControl -> getString(R.string.stage_connecting_control)
        CarPlayStatus.AttachingNetwork ->
            if (wirelessEnabled) getString(R.string.stage_starting_airplay) else getString(R.string.stage_attaching_network)
        CarPlayStatus.RunningControl -> getString(R.string.stage_control_running)
        CarPlayStatus.ControlEnded -> getString(R.string.stage_control_ended)
        is CarPlayStatus.Failed -> getString(R.string.stage_failed, message)
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        /** BYD DiLink's own Bluetooth settings page (com.byd.btsetting in the head-unit firmware). */
        val BYD_BLUETOOTH_SETTINGS = ComponentName("com.byd.btsetting", "com.byd.btsetting.BluetoothManagerDialog")
        const val SCREEN_TYPE_MAIN = 110
        const val SCREEN_TYPE_ALT = 111
        const val LOG_RETENTION_MILLIS = 5 * 60_000L
        const val LOG_RENDER_INTERVAL_MILLIS = 100L
        const val MAX_SCREEN_LOG_LINES = 100
        const val DISPLAY_CHANGE_DEBOUNCE_MILLIS = 500L
        const val RECONNECT_DELAY_MILLIS = 2_000L
        const val IAP_TUNNEL_RECONNECT_DELAY_MILLIS = 15_000L
        const val CONTROLLER_CLOSE_TIMEOUT_MILLIS = 4_000L
        const val AUDIO_CAPTURE_DIRECTORY = "audio-captures"
        const val PROTOCOL_TRACE_PREFIX = "TRACE "
        const val THREE_FINGER_COUNT = 3
        const val THREE_FINGER_SWIPE_DISTANCE_DP = 72
        const val THREE_FINGER_SWIPE_DIRECTION_RATIO = 1.15f
        const val MAX_SETTINGS_MENU_WIDTH_PX = 1200
        val MENU_BACKGROUND = Color.rgb(12, 16, 19)
        val MENU_NAV_BACKGROUND = Color.rgb(20, 25, 29)
        const val ICON_SIZE_LARGE_MM = 250
        const val ICON_SIZE_MEDIUM_MM = 300
        const val ICON_SIZE_SMALL_MM = 350
        val MENU_SECONDARY = Color.rgb(170, 180, 190)
        val MENU_ACCENT = Color.rgb(127, 205, 154)
        val MENU_ACCENT_TRACK = Color.rgb(78, 143, 102)
        val MENU_TRACK_OFF = Color.rgb(64, 74, 80)
        val MENU_BUTTON_TEXT = Color.rgb(8, 17, 11)
        val MENU_DANGER = Color.rgb(190, 45, 45)
        val NO_VIDEO_BACKGROUND = Color.rgb(0x16, 0x16, 0x18)
    }

    private data class DisplaySize(val width: Int, val height: Int)
    private data class PendingLog(
        val generation: Int,
        val timestampMillis: Long,
        val message: String,
    )
    private data class HotspotStatus(
        val state: String,
        val ssid: String? = null,
        val band: String? = null,
        val channel: Int? = null,
        val backend: String? = null,
    )
}

/** Process-local hand-off for keeping the CarPlay session alive while no Activity is visible. */
private object CarPlayBackgroundSession {
    data class Snapshot(
        val controller: CarPlayController,
        val sink: AndroidMediaSink,
        val width: Int,
        val height: Int,
        val display: CarPlaySessionDisplay?,
    )

    private var controller: CarPlayController? = null
    private var sink: AndroidMediaSink? = null
    private var width = 0
    private var height = 0
    private var display: CarPlaySessionDisplay? = null

    @Synchronized
    fun snapshot(): Snapshot? {
        val currentController = controller ?: return null
        val currentSink = sink ?: return null
        return Snapshot(currentController, currentSink, width, height, display)
    }

    @Synchronized
    fun store(
        controller: CarPlayController,
        sink: AndroidMediaSink,
        width: Int,
        height: Int,
        display: CarPlaySessionDisplay?,
    ) {
        this.controller = controller
        this.sink = sink
        this.width = width
        this.height = height
        this.display = display
    }

    @Synchronized
    fun clear(expected: CarPlayController? = null) {
        if (expected != null && controller !== expected) return
        controller = null
        sink = null
        width = 0
        height = 0
        display = null
    }
}

/** The canvas negotiated at session start and the window it was negotiated for. */
internal data class CarPlaySessionDisplay(
    val width: Int,
    val height: Int,
    val rotation: Int,
    val hideTopBar: Boolean,
    val hideBottomBar: Boolean,
    // Compare unscaled startup window dimensions, not the scaled video canvas.
    val windowWidth: Int,
    val windowHeight: Int,
) {
    fun canKeepSession(
        newWidth: Int,
        newHeight: Int,
        newRotation: Int,
        newHideTopBar: Boolean,
        newHideBottomBar: Boolean,
    ): Boolean =
        newWidth > 0 && newHeight > 0 && newWidth <= windowWidth && newHeight <= windowHeight &&
            newRotation == rotation && newHideTopBar == hideTopBar && newHideBottomBar == hideBottomBar
}
