package me.kavishdevar.librepods.services

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import android.os.ext.SdkExtensions
import android.provider.Settings
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.LibrePodsApplication
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.audio.EldDecoder
import me.kavishdevar.librepods.audio.WavWriter
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.bluetooth.aacp.types.MagicKeyType
import me.kavishdevar.librepods.core.bluetooth.ble.BLEManager
import me.kavishdevar.librepods.core.data.apple.AccessoryState
import me.kavishdevar.librepods.core.data.heartrate.HeartRateSample
import me.kavishdevar.librepods.core.data.recording.Recording
import me.kavishdevar.librepods.core.devices.AirPodsSpecs
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.AppleSettings
import me.kavishdevar.librepods.core.devices.AppleState
import me.kavishdevar.librepods.core.devices.BatteryComponent
import me.kavishdevar.librepods.core.devices.BatteryStatus
import me.kavishdevar.librepods.core.devices.ComponentStatus
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.core.devices.Device
import me.kavishdevar.librepods.core.devices.DeviceComponent
import me.kavishdevar.librepods.core.devices.DeviceComponentState
import me.kavishdevar.librepods.core.devices.format
import me.kavishdevar.librepods.core.devices.hasSameState
import me.kavishdevar.librepods.core.utils.redactMac
import me.kavishdevar.librepods.data.app.LibrePodsIntents
import me.kavishdevar.librepods.database.app.AppSettingsEntity
import me.kavishdevar.librepods.presentation.activities.MainActivity
import me.kavishdevar.librepods.presentation.overlays.IslandType
import me.kavishdevar.librepods.presentation.overlays.IslandWindow
import me.kavishdevar.librepods.presentation.overlays.ProximityCardWindow
import me.kavishdevar.librepods.presentation.widgets.BatteryWidget
import me.kavishdevar.librepods.presentation.widgets.NoiseControlWidget
import me.kavishdevar.librepods.utils.ACTION_INJECT_COLOROS_BATTERY
import me.kavishdevar.librepods.utils.AppShortcutsManager
import me.kavishdevar.librepods.utils.EXTRA_COLOROS_BATTERY_ARRAY
import me.kavishdevar.librepods.utils.EXTRA_COLOROS_BATTERY_MAC
import me.kavishdevar.librepods.utils.MediaController
import me.kavishdevar.librepods.utils.calculateLevel
import java.time.ZoneOffset
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.getOrNull
import kotlin.time.Duration
import kotlin.time.toJavaInstant

private const val TAG = "LibrePodsService"

// A2DP and HFP disconnect within a few hundred ms of each other; wait this long
// before deciding the user disconnected the device's audio.
private const val AUDIO_RELEASE_GRACE_MS = 3_000L
private const val PREF_SAVED_SPEAKER_VOLUME = "yielded_saved_speaker_volume"

@SuppressLint("MissingPermission")
class LibrePodsService: Service() {
    companion object {
        const val PREF_NOTIFICATION_MODE_BUTTONS = "notification_mode_buttons"

        @Volatile
        var instance: LibrePodsService? = null
            private set
    }

    inner class LocalBinder : Binder() {
        fun getService(): LibrePodsService = this@LibrePodsService
    }

    private val binder = LocalBinder()

    private val _devices = MutableStateFlow<Map<MacAddress, Device<*, *, *>>>(emptyMap())
    val devices = _devices.asStateFlow()

    private val serviceCoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val irkMap = mutableMapOf<MacAddress, ByteArray>()
    val encKeyMap = mutableMapOf<MacAddress, ByteArray>()

    private val eldDecoder = EldDecoder()

    private val wavWriters = mutableMapOf<Recording, WavWriter>()

    private val appleRepository by lazy {
        (application as LibrePodsApplication).appleRepository
    }

    private val appDataRepository by lazy {
        (application as LibrePodsApplication).appDataRepository
    }

    private val widgetConfigRepository by lazy {
        (application as LibrePodsApplication).widgetConfigRepository
    }

    private val heartRateRepository by lazy {
        (application as LibrePodsApplication).heartRateRepository
    }


    private val bleManager by lazy {
        BLEManager(
            context = this,
            scope = serviceCoroutineScope,

            scanMode = ScanSettings.SCAN_MODE_BALANCED,
            reportDelay = 500L,

            getDevices = { devices.value },

            irkMap = irkMap,
            encKeyMap = encKeyMap
        )
    }

    @Suppress("unused")
    private val recordingRepository by lazy {
        (application as LibrePodsApplication).recordingRepository
    }

    private val healthConnectClient by lazy {
        (application as LibrePodsApplication).healthConnectClient
    }

    private val islandWindow: IslandWindow by lazy {
        (application as LibrePodsApplication).islandWindow
    }

    private val proximityCardWindow: ProximityCardWindow by lazy {
        (application as LibrePodsApplication).proximityCardWindow
    }

    // Used to check whether this phone actually has a device's audio before
    // LibrePods attaches to it in the background. While the AACP socket is open
    // the Bluetooth link stays up, and the AirPods treat the phone as a connected
    // host and bring A2DP/HFP back on their own.
    @Volatile private var a2dpProxy: BluetoothA2dp? = null
    @Volatile private var headsetProxy: BluetoothHeadset? = null

    // Devices whose audio LibrePods itself dropped ("disconnect when not
    // wearing"). Their AACP link is kept so ear detection can restore audio.
    private val audioReleasedByLibrePods: MutableSet<MacAddress> =
        Collections.synchronizedSet(mutableSetOf())

    // Devices currently streaming to another host (e.g. Mac). We clear them as
    // Android's active A2DP device so phone notifications / app launches don't
    // steal audio, and restore them when the user starts media on this phone.
    private val audioYieldedToOtherDevice: MutableSet<MacAddress> =
        Collections.synchronizedSet(mutableSetOf())

    private val pendingAudioReleaseChecks = ConcurrentHashMap<MacAddress, Job>()

    @Volatile
    private var speakerVolumeJob: Job? = null

    // Devices whose A2DP/HFP profile is disconnecting externally (outside
    // LibrePods's "disconnect when not wearing"). Prevents ear-detection from
    // re-enabling audio and fighting a manual disconnect during the grace window.
    private val audioDisconnectingDevices: MutableSet<MacAddress> =
        Collections.synchronizedSet(mutableSetOf())

    private val pendingA2dpReceivers = ConcurrentHashMap<MacAddress, BroadcastReceiver>()

    private fun cancelPendingA2dpReceiver(mac: MacAddress) {
        pendingA2dpReceivers.remove(mac)?.let { receiver ->
            runCatching { unregisterReceiver(receiver) }
        }
    }

    @Suppress("unused")
    private fun observeCameraState() {
        CoroutineScope(Dispatchers.IO).launch {
            LibrePodsAccessibilityService.isCameraOpen.collect { isCamera ->
                if (isCamera) {
                    Log.d(TAG, "Camera is open")
                } else {
                    Log.d(TAG, "Camera is closed")
                }
            }
        }
    }

    private var isCallRinging = false

    private val telephonyCallback = object: TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            if (state == TelephonyManager.CALL_STATE_RINGING) {
                isCallRinging = true
                attemptConnect("phone")
                _devices.value.values.firstOrNull { device ->
                    device is AppleDevice &&
                    device.connectionState.value == ConnectionState.CONNECTED &&
                    device.state.value.componentState.any { it.status == ComponentStatus.IN_EAR }
                }?.let { device ->
                    (device as AppleDevice).detectHeadGestures { accept ->
                        if (!isCallRinging) return@detectHeadGestures

                        try {
                            val telecomManager = getSystemService(TelecomManager::class.java)

                            @Suppress("DEPRECATION")
                            if (accept) {
                                telecomManager?.acceptRingingCall(
                                    VideoProfile.STATE_AUDIO_ONLY
                                )
                                Toast.makeText(
                                    this@LibrePodsService,
                                    getString(R.string.call_accepted),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                Toast.makeText(
                                    this@LibrePodsService,
                                    getString(R.string.call_rejected),
                                    Toast.LENGTH_SHORT
                                ).show()
                                telecomManager?.endCall()
                            }

                            isCallRinging = false
                            device.stopHeadTracking()

                        } catch (e: Exception) {
                            Log.e(TAG, "Error accepting call", e)
                        }
                    }
                }
            } else {
                if (isCallRinging) {
                    _devices.value.values.firstOrNull { device ->
                        device is AppleDevice &&
                        device.connectionState.value == ConnectionState.CONNECTED &&
                        device.state.value.componentState.any { it.status == ComponentStatus.IN_EAR }
                    }?.let { device ->
                        (device as AppleDevice).stopHeadTracking()
                    }
                }
            }
        }
    }

    private fun attemptConnect(trigger: String) {
        val candidates = bleManager.getCandidateDevicesForProxConnect()
        if (candidates.isEmpty()) {
            Log.d(TAG, "No valid BLE candidates found for $trigger takeover")
            return
        }

        for ((device, accessoryState) in candidates) {
            val settings = device.settings.value

            val shouldConnect = when (trigger) {
                "phone" -> when (accessoryState) {
                    AccessoryState.Disconnected -> settings.connectOnPhoneWhenAccessoryDisconnected
                    AccessoryState.Idle -> settings.connectOnPhoneWhenAccessoryIdle
                    AccessoryState.Music -> settings.connectOnPhoneWhenAccessoryMusic
                    else -> false
                }
                "media" -> when (accessoryState) {
                    AccessoryState.Disconnected -> settings.connectOnMediaWhenAccessoryDisconnected
                    AccessoryState.Idle -> settings.connectOnMediaWhenAccessoryIdle
                    AccessoryState.Music -> settings.connectOnMediaWhenAccessoryMusic
                    else -> false
                } else -> false
            }

            if (shouldConnect) {
                Log.i(
                    TAG,
                    "Connecting to ${device.macAddress.toRedactedString()} (state state: $accessoryState) for $trigger"
                )
                device.connect()
                break
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        observeAppSettings()

        registerBluetoothReceivers()

        registerAudioProfileProxies()

        loadDevices()

        clearYieldedAudioStrategies()
        restoreSavedSpeakerVolumeIfOnSpeaker()

        observeDynamicShortcuts()

        bleManager.start()

        MediaController.initialize(
            audioManager = getSystemService(AudioManager::class.java),
            sharedPreferences = getSharedPreferences("settings", MODE_PRIVATE),
            localMac = getLocalBluetoothMac(),
            context = this,
            musicPlayed = {
                restoreYieldedAudioDevice()
                attemptConnect("media")
            },
            musicStopped = {
                notifyMediaStoppedToPeers()
            }
        )

        if (
            checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED
        ) {
            val telephonyManager = getSystemService(TelephonyManager::class.java)

            telephonyManager.registerTelephonyCallback(
                mainExecutor,
                telephonyCallback
            )
        }

        startForegroundNotification()
    }

    private fun observeDynamicShortcuts() {
        serviceCoroutineScope.launch {
            devices.collectLatest { devicesMap ->
                val list = devicesMap.values.toList()
                AppShortcutsManager.update(this@LibrePodsService, list)

                val appleDevices = list.filterIsInstance<AppleDevice>()
                if (appleDevices.isEmpty()) return@collectLatest

                val flows = appleDevices.map { dev ->
                    combine(dev.connectionState, dev.metadata, dev.state) { conn, meta, st ->
                        Triple(
                            conn,
                            meta,
                            st.controlStates[ControlCommandIdentifier.ALLOW_OFF_OPTION]?.getOrNull(0)?.toInt() == 1
                        )
                    }
                }
                combine(flows) { it }.collectLatest {
                    AppShortcutsManager.update(this@LibrePodsService, list)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            when (intent.action) {
                LibrePodsIntents.ACTION_CONNECT -> {
                    val macAddress = intent.getStringExtra(LibrePodsIntents.Extras.MAC_ADDRESS)?.let { MacAddress(it) }
                    val device = devices.value[macAddress]
                    device?.connect()
                }
                LibrePodsIntents.ACTION_SET_ANC_MODE -> {
                    val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
                    val macAddress = intent.getStringExtra(LibrePodsIntents.Extras.MAC_ADDRESS)?.let { MacAddress(it) }

                    val ancMode = intent.getIntExtra(LibrePodsIntents.Extras.ANC_MODE, -1)
                    val device =
                        widgetConfigRepository.widgetConfigs.value.find { it.appWidgetId == appWidgetId }
                            ?.let { config ->
                                devices.value[config.macAddress]
                            } ?: devices.value[macAddress]
                        ?: devices.value.values.firstOrNull { it.connectionState.value == ConnectionState.CONNECTED }

                    if (device != null && ancMode != -1) {
                        Log.i(
                            TAG,
                            "Setting ANC mode to $ancMode for device ${device.macAddress.toRedactedString()}"
                        )
                        when (device) {
                            is AppleDevice -> device.setControlCommand(
                                ControlCommandIdentifier.LISTENING_MODE,
                                ancMode
                            )
                        }
                    } else {
                        Log.w(TAG, "No connected Apple device found or invalid ANC mode: $ancMode")
                    }
                }
            }
        }

        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        pendingAudioReleaseChecks.values.forEach { it.cancel() }
        pendingAudioReleaseChecks.clear()
        pendingA2dpReceivers.keys.toList().forEach { cancelPendingA2dpReceiver(it) }
        audioDisconnectingDevices.clear()
        audioYieldedToOtherDevice.clear()
        clearYieldedAudioStrategies()
        restoreSavedSpeakerVolumeIfOnSpeaker()
        runCatching { unregisterBluetoothReceivers() }
        closeAudioProfileProxies()
        bleManager.stop()
        devices.value.values.filterIsInstance<AppleDevice>().forEach { it.closeAacp() }
        serviceCoroutineScope.cancel("Service stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun onDeviceConnected(bluetoothDevice: BluetoothDevice) {
        if (devices.value[MacAddress(bluetoothDevice.address)]?.connectionState?.value == ConnectionState.CONNECTED) {
            Log.d(TAG, "Device already connected: ${bluetoothDevice.address}")
            return
        }

        val device = devices.value[MacAddress(bluetoothDevice.address)] ?: createDevice(bluetoothDevice)

        if (device == null) {
            Log.d(TAG, "Unsupported device connected: ${bluetoothDevice.address}")
            return
        }

        // A link can come up without this phone having the audio: an SDP query,
        // a brief page from the AirPods, or Android's own reconnect attempt.
        // Attaching AACP then holds the link open and the AirPods move their
        // audio here, taking it from whichever device was using them. Attach
        // once A2DP or HFP is connected instead (see onAudioProfileStateChanged).
        if (isAudioConnected(bluetoothDevice) != true) {
            Log.i(
                TAG,
                "Not attaching to ${device.macAddress.toRedactedString()}: its audio isn't connected to this phone"
            )
            _devices.update { it + (device.macAddress to device) }
            return
        }

        when (device) {
            is AppleDevice -> CoroutineScope(Dispatchers.IO).launch {
                Log.i(TAG, "Loading device ${device.macAddress.toRedactedString()} from db")

                appleRepository.load(device.macAddress)?.let { entity ->
                    val cache = entity.cache
                    Log.i(
                        TAG,
                        "Loaded cached state for device ${device.macAddress.toRedactedString()}: $cache"
                    )
                    val settings = entity.settings
                    Log.i(
                        TAG,
                        "Loaded settings for device ${device.macAddress.toRedactedString()}: $settings"
                    )
                    val metadata = entity.metadata
                    Log.i(
                        TAG,
                        "Loaded metadata for device ${device.macAddress.toRedactedString()}: $metadata"
                    )

                    val currentState = device.state.value
                    device.loadInitialState(
                        state = currentState.copy(
                            capabilities = cache.capabilities,
                            magicKeys = cache.magicKeys,
                            controlStates = cache.controlStates,
                        ),
                        settings = settings,
                        metadata = metadata
                    )

                    if (device.settings.value.hrmAlertEnabled) {
                        device.startHr()
                    }
                }
            }
        }

        device.connect()

        Log.i(
            TAG,
            "Device connected: ${device.macAddress.toRedactedString()} (${device.javaClass.simpleName})"
        )

        _devices.update { it + (device.macAddress to device) }
    }

    private fun getLocalBluetoothMac(): String? = try {
        getSystemService(BluetoothManager::class.java)?.adapter?.address
            ?.takeIf { it.isNotBlank() && it != "02:00:00:00:00:00" }
    } catch (e: Exception) {
        Log.w(TAG, "couldn't read local Bluetooth MAC", e)
        null
    }

    private fun hasActiveA2dpDevice(): Boolean {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter != null) {
            try {
                val method = adapter.javaClass.getMethod("getActiveDevices", Int::class.javaPrimitiveType)
                @Suppress("UNCHECKED_CAST")
                val active = method.invoke(adapter, BluetoothProfile.A2DP) as? List<BluetoothDevice?>
                if (active != null) return active.any { it != null }
            } catch (_: Exception) {
            }
        }
        val proxy = a2dpProxy ?: return false
        return try {
            val method = proxy.javaClass.getMethod("getActiveDevice")
            method.invoke(proxy) != null
        } catch (_: Exception) {
            false
        }
    }

    private fun isMediaRoutedToSpeaker(): Boolean {
        val audioManager = getSystemService(AudioManager::class.java) ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return try {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                val devices = audioManager.getAudioDevicesForAttributes(attrs)
                devices.isNotEmpty() && devices.all { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            } catch (e: Exception) {
                Log.w(TAG, "getAudioDevicesForAttributes failed", e)
                false
            }
        }
        return !hasActiveA2dpDevice()
    }

    private fun hasModifyAudioRoutingPermission(): Boolean =
        checkSelfPermission("android.permission.MODIFY_AUDIO_ROUTING") == PackageManager.PERMISSION_GRANTED

    private fun getYieldAudioProductStrategies(): List<Any> {
        return try {
            val strategyClass = Class.forName("android.media.audiopolicy.AudioProductStrategy")
            val getAll = AudioManager::class.java.getMethod("getAudioProductStrategies")
            val getName = strategyClass.getMethod("getName")
            val getId = strategyClass.getMethod("getId")
            val targetNames = setOf(
                "STRATEGY_MEDIA",
                "STRATEGY_SONIFICATION",
                "STRATEGY_SONIFICATION_RESPECTFUL",
                "STRATEGY_DTMF",
                "STRATEGY_ACCESSIBILITY",
                "STRATEGY_ASSISTANT"
            )
            val targetIds = setOf(0, 2, 3, 4, 7, 11)
            @Suppress("UNCHECKED_CAST")
            val all = getAll.invoke(null) as? List<Any> ?: return emptyList()
            all.filter { strategy ->
                val name = runCatching { getName.invoke(strategy) as? String }.getOrNull()
                val id = runCatching { getId.invoke(strategy) as? Int }.getOrNull()
                (name != null && name in targetNames) || (id != null && id in targetIds)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query AudioProductStrategies", e)
            emptyList()
        }
    }

    /**
     * Routes media/notification strategies to TYPE_BUILTIN_SPEAKER while keeping
     * A2DP attached and open in AudioPolicyManager. Avoids the ~450 ms teardown
     * and re-open overhead of BluetoothAdapter.removeActiveDevice() / setActiveDevice().
     */
    private fun yieldStrategiesToSpeaker(): Boolean {
        if (!hasModifyAudioRoutingPermission()) return false
        val audioManager = getSystemService(AudioManager::class.java) ?: return false
        val strategies = getYieldAudioProductStrategies()
        if (strategies.isEmpty()) return false
        return try {
            val strategyClass = Class.forName("android.media.audiopolicy.AudioProductStrategy")
            val attrClass = Class.forName("android.media.AudioDeviceAttributes")
            val roleOutput = attrClass.getField("ROLE_OUTPUT").getInt(null)
            val speakerAttr = attrClass.getConstructor(
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java
            ).newInstance(roleOutput, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "")
            val setPreferred = audioManager.javaClass.getMethod(
                "setPreferredDeviceForStrategy",
                strategyClass,
                attrClass
            )
            var setCount = 0
            for (strategy in strategies) {
                val ok = setPreferred.invoke(audioManager, strategy, speakerAttr) as? Boolean ?: false
                if (ok) setCount++
            }
            Log.i(TAG, "Smart Routing: setPreferredDeviceForStrategy(SPEAKER) applied to $setCount/${strategies.size} strategies")
            setCount > 0
        } catch (e: Exception) {
            Log.w(TAG, "setPreferredDeviceForStrategy(SPEAKER) failed; falling back to removeActiveDevice", e)
            false
        }
    }

    private fun clearYieldedAudioStrategies() {
        if (!hasModifyAudioRoutingPermission()) return
        val audioManager = getSystemService(AudioManager::class.java) ?: return
        val strategies = getYieldAudioProductStrategies()
        if (strategies.isEmpty()) return
        try {
            val strategyClass = Class.forName("android.media.audiopolicy.AudioProductStrategy")
            val removePreferred = audioManager.javaClass.getMethod(
                "removePreferredDeviceForStrategy",
                strategyClass
            )
            var cleared = 0
            for (strategy in strategies) {
                val ok = removePreferred.invoke(audioManager, strategy) as? Boolean ?: false
                if (ok) cleared++
            }
            if (cleared > 0) {
                Log.i(TAG, "Smart Routing: removePreferredDeviceForStrategy cleared $cleared/${strategies.size} strategies")
            }
        } catch (e: Exception) {
            Log.w(TAG, "removePreferredDeviceForStrategy failed", e)
        }
    }

    private fun getSavedSpeakerVolume(): Int =
        getSharedPreferences("settings", MODE_PRIVATE).getInt(PREF_SAVED_SPEAKER_VOLUME, -1)

    private fun setSavedSpeakerVolume(volume: Int) {
        getSharedPreferences("settings", MODE_PRIVATE).edit().apply {
            if (volume < 0) remove(PREF_SAVED_SPEAKER_VOLUME) else putInt(PREF_SAVED_SPEAKER_VOLUME, volume)
            apply()
        }
    }

    /**
     * While AirPods are yielded to another host, Android routes STREAM_MUSIC to
     * the phone speaker. Silently zeroing speaker volume prevents the brief
     * (~40-50 ms) speaker bleed when media starts before A2DP finishes switching.
     * Android tracks A2DP volume separately, so AirPods play at their own level.
     *
     * Must only run once AudioPolicyManager actually routes USAGE_MEDIA to
     * TYPE_BUILTIN_SPEAKER (which lags ~500 ms behind removeActiveDevice()),
     * otherwise setStreamVolume would mutate A2DP volume instead of speaker.
     */
    @Synchronized
    private fun muteSpeakerWhileYielded(): Boolean {
        if (audioYieldedToOtherDevice.isEmpty()) return false
        if (!isMediaRoutedToSpeaker()) return false
        val audioManager = getSystemService(AudioManager::class.java) ?: return false
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (current > 0) {
            setSavedSpeakerVolume(current)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            Log.i(TAG, "Smart Routing: muted phone speaker while yielded (saved volume=$current)")
        }
        return true
    }

    private fun scheduleMuteSpeakerWhileYielded() {
        speakerVolumeJob?.cancel()
        speakerVolumeJob = serviceCoroutineScope.launch {
            repeat(15) {
                if (audioYieldedToOtherDevice.isEmpty()) return@launch
                if (muteSpeakerWhileYielded()) return@launch
                delay(100L)
            }
        }
    }

    @Synchronized
    private fun restoreSavedSpeakerVolumeIfOnSpeaker(): Boolean {
        val saved = getSavedSpeakerVolume()
        if (saved < 0) return true
        if (audioYieldedToOtherDevice.isNotEmpty()) return false
        if (!isMediaRoutedToSpeaker()) return false
        val audioManager = getSystemService(AudioManager::class.java) ?: return false
        setSavedSpeakerVolume(-1)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (current == 0) {
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val target = saved.coerceIn(0, maxVol)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            Log.i(TAG, "Smart Routing: restored phone speaker volume to $target")
        } else {
            Log.d(TAG, "Smart Routing: speaker volume already changed by user ($current); not overwriting with $saved")
        }
        return true
    }

    private fun scheduleRestoreSpeakerVolume() {
        if (getSavedSpeakerVolume() < 0) return
        speakerVolumeJob?.cancel()
        speakerVolumeJob = serviceCoroutineScope.launch {
            repeat(15) {
                if (audioYieldedToOtherDevice.isNotEmpty()) return@launch
                if (restoreSavedSpeakerVolumeIfOnSpeaker()) return@launch
                delay(100L)
            }
        }
    }

    private fun removeActiveAudioDevice() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return
        try {
            // BluetoothAdapter.ACTIVE_DEVICE_AUDIO = 0
            val method = adapter.javaClass.getMethod("removeActiveDevice", Int::class.javaPrimitiveType)
            val ok = method.invoke(adapter, 0) as? Boolean
            Log.i(TAG, "removeActiveDevice(ACTIVE_DEVICE_AUDIO) -> $ok")
        } catch (e: Exception) {
            Log.w(TAG, "removeActiveDevice(ACTIVE_DEVICE_AUDIO) failed", e)
        }
    }

    private fun setActiveAudioDevice(bluetoothDevice: BluetoothDevice) {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return
        try {
            // BluetoothAdapter.ACTIVE_DEVICE_AUDIO = 0
            val method = adapter.javaClass.getMethod(
                "setActiveDevice",
                BluetoothDevice::class.java,
                Int::class.javaPrimitiveType
            )
            val ok = method.invoke(adapter, bluetoothDevice, 0) as? Boolean
            Log.i(TAG, "setActiveDevice(${MacAddress(bluetoothDevice.address).toRedactedString()}, ACTIVE_DEVICE_AUDIO) -> $ok")
        } catch (e: Exception) {
            Log.w(TAG, "setActiveDevice(ACTIVE_DEVICE_AUDIO) failed", e)
        }
    }

    private fun restoreYieldedAudioDevice() {
        val localMac = getLocalBluetoothMac()
        val connectedApple = devices.value.values
            .filterIsInstance<AppleDevice>()
            .firstOrNull { it.connectionState.value == ConnectionState.CONNECTED } ?: return

        val wasYielded = audioYieldedToOtherDevice.remove(connectedApple.macAddress)
        if (wasYielded) {
            speakerVolumeJob?.cancel()
        }
        val hasOtherPeer = localMac != null &&
            connectedApple.state.value.connectedDevices.any { !it.macAddress.value.equals(localMac, ignoreCase = true) }

        // Claim ownership & send hijack BEFORE clearing preferred strategies:
        // removePreferredDeviceForStrategy() blocks on AudioFlinger's A2DP start
        // handshake, so sending AACP ownership first lets the AirPods begin
        // tearing down the Mac stream in parallel with Android's PAL route switch.
        if (localMac != null && hasOtherPeer) {
            Log.i(TAG, "Smart Routing: claiming ownership & sending hijack request for ${connectedApple.macAddress.toRedactedString()}")
            serviceCoroutineScope.launch {
                connectedApple.aacp.sendControlCommand(ControlCommandIdentifier.OWNS_CONNECTION.value, 1)
                connectedApple.aacp.sendMediaInformataion(localMac, streamingState = false)
                if (wasYielded || !connectedApple.state.value.owns) {
                    connectedApple.aacp.sendSmartRoutingShowUI(localMac)
                    connectedApple.aacp.sendHijackRequest(localMac)
                }
            }
        }

        if (wasYielded) {
            serviceCoroutineScope.launch {
                clearYieldedAudioStrategies()
            }
        }

        // Always ensure the AirPods are active A2DP when intentional media starts
        // on this phone (prevents getting stuck on speaker if state ever desyncs).
        val audioConnected = isAudioConnected(connectedApple.bluetoothDevice)
        if (audioConnected == false && earPresenceOf(connectedApple.state.value.componentState) != EarPresence.NONE) {
            Log.i(TAG, "Phone started media playback while wearing ${connectedApple.macAddress.toRedactedString()} but A2DP is disconnected; re-enabling audio")
            audioReleasedByLibrePods.remove(connectedApple.macAddress)
            connectedApple.enableAudio()
            connectedApple.connectA2dp()
            setActiveAudioDevice(connectedApple.bluetoothDevice)
        } else if (wasYielded || audioConnected == true) {
            if (!hasActiveA2dpDevice()) {
                Log.i(TAG, "Phone started media playback; ensuring ${connectedApple.macAddress.toRedactedString()} is active A2DP device (wasYielded=$wasYielded)")
                setActiveAudioDevice(connectedApple.bluetoothDevice)
            } else {
                Log.i(TAG, "Phone started media playback; A2DP already active for ${connectedApple.macAddress.toRedactedString()} (fast strategy resume, wasYielded=$wasYielded)")
            }
        }

        if (wasYielded) {
            showIsland(
                device = connectedApple,
                type = IslandType.TAKING_OVER
            )
        }
    }

    private fun notifyMediaStoppedToPeers() {
        val localMac = getLocalBluetoothMac() ?: return
        val connectedApple = devices.value.values
            .filterIsInstance<AppleDevice>()
            .firstOrNull {
                it.connectionState.value == ConnectionState.CONNECTED &&
                    it.state.value.connectedDevices.any { peer -> !peer.macAddress.value.equals(localMac, ignoreCase = true) }
            } ?: return
        Log.d(TAG, "Smart Routing: notifying peers that phone stopped streaming")
        serviceCoroutineScope.launch {
            connectedApple.aacp.sendMediaInformataion(localMac, streamingState = false)
        }
    }

    private fun resolvePeerDeviceName(device: AppleDevice, peerMac: String? = null): String {
        val localMac = getLocalBluetoothMac()
        val connectedDevices = device.state.value.connectedDevices
        val targetMac = peerMac
            ?: device.state.value.audioSource.mac.value.takeIf {
                it != "00:00:00:00:00:00" && (localMac == null || !it.equals(localMac, ignoreCase = true))
            }
        val matched = targetMac?.let { mac ->
            connectedDevices.find { it.macAddress.value.equals(mac, ignoreCase = true) }
        } ?: connectedDevices.firstOrNull { peer ->
            localMac == null || !peer.macAddress.value.equals(localMac, ignoreCase = true)
        }
        return matched?.type?.takeIf { it.isNotBlank() } ?: "another device"
    }

    private fun reverseHandoff(device: AppleDevice) {
        val localMac = getLocalBluetoothMac()
        audioYieldedToOtherDevice.remove(device.macAddress)
        speakerVolumeJob?.cancel()
        clearYieldedAudioStrategies()
        Log.i(TAG, "Smart Routing: user tapped reverse banner; reclaiming ${device.macAddress.toRedactedString()}")
        if (!hasActiveA2dpDevice()) {
            setActiveAudioDevice(device.bluetoothDevice)
        }
        if (localMac != null) {
            serviceCoroutineScope.launch {
                device.aacp.sendControlCommand(ControlCommandIdentifier.OWNS_CONNECTION.value, 1)
                device.aacp.sendMediaInformataion(localMac, streamingState = false)
                device.aacp.sendSmartRoutingShowUI(localMac)
                device.aacp.sendHijackReversed(localMac)
            }
        }
    }

    private fun yieldOwnershipToOtherDevice(
        device: AppleDevice,
        reason: String,
        peerMac: String? = null,
        reversed: Boolean = false
    ) {
        if (!audioYieldedToOtherDevice.add(device.macAddress)) {
            Log.d(TAG, "Smart Routing: already yielded ${device.macAddress.toRedactedString()} ($reason)")
            return
        }
        Log.i(TAG, "Smart Routing: yielding ${device.macAddress.toRedactedString()} ($reason)")
        device.aacp.sendControlCommand(ControlCommandIdentifier.OWNS_CONNECTION.value, byteArrayOf(0x00))
        MediaController.onOwnershipLost()
        if (!yieldStrategiesToSpeaker()) {
            removeActiveAudioDevice()
        }
        scheduleMuteSpeakerWhileYielded()
        showIsland(
            device = device,
            type = IslandType.MOVED_TO_OTHER_DEVICE,
            reversed = reversed,
            otherDeviceName = resolvePeerDeviceName(device, peerMac),
            onReverseAction = { reverseHandoff(device) }
        )
    }

    private fun observeAppleEvents(device: AppleDevice) {
        serviceCoroutineScope.launch {
            device.events.collect { event ->
                when (event) {
                    is me.kavishdevar.librepods.core.bluetooth.aacp.types.AppleEvent.OwnershipToFalseRequest -> {
                        yieldOwnershipToOtherDevice(
                            device = device,
                            reason = "SetOwnershipToFalse from ${event.sender}, reverseTapped=${event.reverseTapped}",
                            peerMac = event.sender,
                            reversed = event.reverseTapped
                        )
                    }
                    else -> {}
                }
            }
        }
    }

    private fun onDeviceDisconnected(mac: MacAddress) {
        audioYieldedToOtherDevice.remove(mac)
        audioDisconnectingDevices.remove(mac)
        audioReleasedByLibrePods.remove(mac)
        pendingAudioReleaseChecks.remove(mac)?.cancel()
        cancelPendingA2dpReceiver(mac)
        clearYieldedAudioStrategies()
        scheduleRestoreSpeakerVolume()
        Log.i(TAG, "Device disconnected: $mac")
        val device = devices.value[mac]?: return
        device.disconnect()
        if (device is AppleDevice) {
            broadcastColorOsBattery(device, disconnected = true)
        }
        updateDeviceNotification(device = device)
    }

    private fun loadDevices() {
        val bluetoothAdapter = getSystemService(BluetoothManager::class.java).adapter
        val bondedDevices = bluetoothAdapter.bondedDevices

        bondedDevices.forEach { bluetoothDevice ->
            val device = createDevice(bluetoothDevice)
            if (device != null) {
                val notificationManager = getSystemService(NotificationManager::class.java)
                val channel = NotificationChannel(
                    "device_${device.macAddress}",
                    "Device ${device.metadata.value.name}",
                    NotificationManager.IMPORTANCE_LOW
                )
                notificationManager.createNotificationChannel(channel)

                _devices.update { it + (device.macAddress to device) }
            }
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent) {
            val action = intent.action
            if (action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_ON -> {
                        Log.i(TAG, "Bluetooth turned ON; re-registering audio proxies and restarting BLE scan")
                        registerAudioProfileProxies()
                        bleManager.start()
                    }
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                        bleManager.stop()
                    }
                }
                return
            }

            val bluetoothDevice = intent.getParcelableExtra(
                "android.bluetooth.device.extra.DEVICE",
                BluetoothDevice::class.java
            )

            if (action == "android.bluetooth.a2dp.profile.action.ACTIVE_DEVICE_CHANGED") {
                if (bluetoothDevice != null) {
                    // If the user manually selected the AirPods in Android's output switcher,
                    // respect that and stop treating them as yielded.
                    audioYieldedToOtherDevice.remove(MacAddress(bluetoothDevice.address))
                    speakerVolumeJob?.cancel()
                } else {
                    if (audioYieldedToOtherDevice.isNotEmpty()) {
                        scheduleMuteSpeakerWhileYielded()
                    } else {
                        scheduleRestoreSpeakerVolume()
                    }
                }
                return
            }

            if (bluetoothDevice != null) {
                when (action) {
                    BluetoothDevice.ACTION_ACL_CONNECTED -> {
                        if (bluetoothDevice.uuids == null) {
                            bluetoothDevice.fetchUuidsWithSdp()
                        } else {
                            onDeviceConnected(bluetoothDevice)
                        }
                    }

                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> onDeviceDisconnected(
                        MacAddress(
                            bluetoothDevice.address
                        )
                    )

                    BluetoothDevice.ACTION_UUID -> onDeviceConnected(bluetoothDevice)

                    BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
                    BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> onAudioProfileStateChanged(
                        bluetoothDevice,
                        intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                    )
                }
            }
        }
    }

    private val audioProfileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            when (profile) {
                BluetoothProfile.A2DP -> a2dpProxy = proxy as BluetoothA2dp
                BluetoothProfile.HEADSET -> headsetProxy = proxy as BluetoothHeadset
                else -> return
            }
            // Devices loaded before both proxies were ready weren't attached.
            if (a2dpProxy != null && headsetProxy != null) attachToDevicesWithAudio()
        }

        override fun onServiceDisconnected(profile: Int) {
            when (profile) {
                BluetoothProfile.A2DP -> a2dpProxy = null
                BluetoothProfile.HEADSET -> headsetProxy = null
            }
        }
    }

    private fun registerAudioProfileProxies() {
        val adapter = getSystemService(BluetoothManager::class.java).adapter ?: return
        adapter.getProfileProxy(this, audioProfileListener, BluetoothProfile.A2DP)
        adapter.getProfileProxy(this, audioProfileListener, BluetoothProfile.HEADSET)
    }

    private fun closeAudioProfileProxies() {
        val adapter = getSystemService(BluetoothManager::class.java).adapter ?: return
        a2dpProxy?.let { adapter.closeProfileProxy(BluetoothProfile.A2DP, it) }
        headsetProxy?.let { adapter.closeProfileProxy(BluetoothProfile.HEADSET, it) }
        a2dpProxy = null
        headsetProxy = null
    }

    /**
     * Whether A2DP or HFP to [bluetoothDevice] is connected (or connecting) on
     * this phone, or null while the profile proxies aren't ready yet.
     */
    private fun isAudioConnected(bluetoothDevice: BluetoothDevice): Boolean? {
        val a2dp = a2dpProxy ?: return null
        val headset = headsetProxy ?: return null
        fun BluetoothProfile.hasDevice(): Boolean = try {
            getConnectionState(bluetoothDevice).let {
                it == BluetoothProfile.STATE_CONNECTED || it == BluetoothProfile.STATE_CONNECTING
            }
        } catch (e: Exception) {
            Log.w(TAG, "couldn't read profile state", e)
            false
        }
        return a2dp.hasDevice() || headset.hasDevice()
    }

    private fun attachToDevicesWithAudio() {
        val adapter = getSystemService(BluetoothManager::class.java).adapter ?: return
        val aacpUuid = ParcelUuid.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a")
        adapter.bondedDevices
            .filter { it.uuids?.contains(aacpUuid) == true && isAudioConnected(it) == true }
            .forEach { onDeviceConnected(it) }
    }

    private fun onAudioProfileStateChanged(bluetoothDevice: BluetoothDevice, state: Int) {
        val mac = MacAddress(bluetoothDevice.address)
        when (state) {
            BluetoothProfile.STATE_CONNECTED -> {
                audioDisconnectingDevices.remove(mac)
                pendingAudioReleaseChecks.remove(mac)?.cancel()
                if (mac in audioYieldedToOtherDevice) {
                    Log.i(TAG, "Audio profile connected for ${mac.toRedactedString()} while yielded to another device; clearing active A2DP")
                    removeActiveAudioDevice()
                }
                if (devices.value[mac]?.connectionState?.value == ConnectionState.CONNECTED) return
                if (bluetoothDevice.uuids == null) {
                    bluetoothDevice.fetchUuidsWithSdp()
                } else {
                    onDeviceConnected(bluetoothDevice)
                }
            }

            BluetoothProfile.STATE_DISCONNECTING -> {
                if (mac !in audioReleasedByLibrePods) {
                    audioDisconnectingDevices.add(mac)
                    cancelPendingA2dpReceiver(mac)
                }
            }

            BluetoothProfile.STATE_DISCONNECTED -> {
                val device = devices.value[mac] as? AppleDevice ?: return
                if (mac in audioReleasedByLibrePods) {
                    Log.d(TAG, "Audio for ${mac.toRedactedString()} was released by LibrePods; keeping AACP for ear detection")
                    return
                }
                audioDisconnectingDevices.add(mac)
                cancelPendingA2dpReceiver(mac)
                pendingAudioReleaseChecks.remove(mac)?.cancel()
                pendingAudioReleaseChecks[mac] = serviceCoroutineScope.launch {
                    try {
                        delay(AUDIO_RELEASE_GRACE_MS)
                        pendingAudioReleaseChecks.remove(mac)
                        if (device.connectionState.value != ConnectionState.CONNECTED) return@launch
                        if (mac in audioReleasedByLibrePods) {
                            Log.d(TAG, "Audio for ${mac.toRedactedString()} was released by LibrePods; keeping AACP for ear detection")
                            return@launch
                        }
                        if (isAudioConnected(bluetoothDevice) != false) return@launch
                        // The user (or another app) disconnected the device on this
                        // phone. Close AACP too, or the link stays up and the AirPods
                        // reconnect their audio here, taking it from the device that
                        // is using them.
                        Log.i(TAG, "Audio for ${mac.toRedactedString()} was disconnected outside LibrePods; closing AACP to release the link")
                        device.disconnect()
                        updateDeviceNotification(device = device)
                    } finally {
                        audioDisconnectingDevices.remove(mac)
                    }
                }
            }
        }
    }

    fun registerBluetoothReceivers() {
        val intentFilter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction("android.bluetooth.device.action.ACL_CONNECTED")
            addAction("android.bluetooth.device.action.ACL_DISCONNECTED")
            addAction("android.bluetooth.device.action.UUID")
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction("android.bluetooth.a2dp.profile.action.ACTIVE_DEVICE_CHANGED")
        }

        registerReceiver(bluetoothReceiver, intentFilter, RECEIVER_EXPORTED)
    }

    fun unregisterBluetoothReceivers() {
        unregisterReceiver(bluetoothReceiver)
    }

    private fun createDevice(
        bluetoothDevice: BluetoothDevice
    ): Device<*, *, *>? {

        val aacpUuid = ParcelUuid.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a")

        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        val bluetoothAdapter = bluetoothManager.adapter

        val isConnectedMethod = BluetoothDevice::class.java.getMethod("isConnected")
        isConnectedMethod.isAccessible = true

        val device = when {
            bluetoothDevice.uuids?.contains(aacpUuid) == true -> {
                Log.i(TAG, "Apple device detected: ${bluetoothDevice.address.redactMac()}")
                AppleDevice(
                    context = this,
                    bluetoothAdapter = bluetoothAdapter,
                    bluetoothDevice = bluetoothDevice,
                    // AVAILABLE makes AppleDevice attach AACP right away, so
                    // also require this phone to have the device's audio (see
                    // onDeviceConnected).
                    currentState = if (
                        isConnectedMethod.invoke(bluetoothDevice) as Boolean &&
                        isAudioConnected(bluetoothDevice) == true
                    ) ConnectionState.AVAILABLE else ConnectionState.DISCONNECTED
                )
            }

            else -> null
        }

        when (device) {
            is AppleDevice -> CoroutineScope(Dispatchers.IO).launch {
                Log.i(TAG, "Loading device ${device.macAddress.toRedactedString()} from db")

                appleRepository.load(device.macAddress)?.let { entity ->
                    val cache = entity.cache

                    // load irk at the earliest so we can start parsing
                    val irk = entity.cache.magicKeys[MagicKeyType.IRK]
                    if (irk != null) {
                        irkMap[device.macAddress] = irk
                        bleManager.updateIrkMap(irkMap)
                        Log.d(TAG, "Loaded IRK for device ${device.macAddress.toRedactedString()}")
                    }

                    val encKey = entity.cache.magicKeys[MagicKeyType.ENC_KEY]
                    if (encKey != null) {
                        encKeyMap[device.macAddress] = encKey
                        bleManager.updateEncKeyMap(encKeyMap)
                        Log.d(TAG, "Loaded ENC_KEY for device ${device.macAddress.toRedactedString()}")
                    }

                    Log.i(
                        TAG,
                        "Loaded cached state for device ${device.macAddress.toRedactedString()}: $cache"
                    )
                    val settings = entity.settings
                    Log.i(
                        TAG,
                        "Loaded settings for device ${device.macAddress.toRedactedString()}: $settings"
                    )
                    val metadata = entity.metadata
                    Log.i(
                        TAG,
                        "Loaded metadata for device ${device.macAddress.toRedactedString()}: $metadata"
                    )

                    val currentState = device.state.value
                    device.loadInitialState(
                        state = currentState.copy(
                            capabilities = cache.capabilities,
                            magicKeys = cache.magicKeys,
                            controlStates = cache.controlStates,
                        ),
                        settings = settings,
                        metadata = metadata
                    )
                }

                observeAppleState(device)
                observeAppleEvents(device)
                observeAppleSettings(device)
                observeAppleMetadata(device)
                observeAppleMicrophoneFrames(device)
            }
        }

        if (device != null) observeConnectionState(device)

        return device
    }

    fun observeConnectionState(device: Device<*, *, *>) {
        serviceCoroutineScope.launch {
            var previousConnectionState = ConnectionState.DISCONNECTED
            device.connectionState.collect { state ->
                if (proximityCardWindow.isVisible) proximityCardWindow.updateConnectionState(state == ConnectionState.CONNECTED)

                if (previousConnectionState == ConnectionState.DISCONNECTED && state == ConnectionState.AVAILABLE) {
                    when (device) {
                        is AppleDevice -> {
                            val settings = device.settings.value
                            val appleState = device.state.value
                            if (
                                settings.alwaysConnectWhenAccessoryDisconnected &&
                                appleState.state == AccessoryState.Disconnected &&
                                System.currentTimeMillis() - appleState.lastDisconnected > 5_000
                            ) {
                                Log.i(TAG, "device ${device.macAddress.toRedactedString()} is nearby and disconnected, connecting")
                                device.connect()
                            }
                        }
                    }
                }

                if (previousConnectionState != state) {
                    updateWidgets()
                    if (device is AppleDevice) {
                        if (state == ConnectionState.CONNECTED) {
                            broadcastColorOsBattery(device)
                        } else if (previousConnectionState == ConnectionState.CONNECTED) {
                            broadcastColorOsBattery(device, disconnected = true)
                        }
                    }
                }
                previousConnectionState = state
            }
        }
    }

    fun observeAppSettings(): Job {
        var oldAppSettings: AppSettingsEntity = appDataRepository.settings.value

        return serviceCoroutineScope.launch {
            appDataRepository.settings.collect { settings ->
                if (oldAppSettings.bleReportDelay != settings.bleReportDelay) {
                    bleManager.updateReportDelay(settings.bleReportDelay)
                }
                if (oldAppSettings.bleScanMode != settings.bleScanMode) {
                    bleManager.updateScanMode(settings.bleScanMode)
                }

                islandWindow.updateSettings(settings)
                oldAppSettings = settings
            }
        }
    }

    fun observeAppleState(device: AppleDevice) {
        serviceCoroutineScope.launch {
            var previousState = device.state.value

            device.state.collect { state ->
                val deviceSettings = device.settings.value

                if (state.aacpPackets != previousState.aacpPackets) {
                    if (!appDataRepository.state.value.hasConnectedToAACP) {
                        appDataRepository.updateState { it.copy(hasConnectedToAACP = true) }
                    }
                    if (appDataRepository.state.value.firstSuccessfulConnectionTime == null) {
                        appDataRepository.updateState { it.copy(firstSuccessfulConnectionTime = System.currentTimeMillis()) }
                    }
                }

                if (state.connectedDevices != previousState.connectedDevices) {
                    val localMac = getLocalBluetoothMac()
                    if (localMac != null) {
                        val previousMacs = previousState.connectedDevices.map { it.macAddress.value.uppercase() }.toSet()
                        val newPeers = state.connectedDevices.filter {
                            !it.macAddress.value.equals(localMac, ignoreCase = true) &&
                                it.macAddress.value.uppercase() !in previousMacs
                        }
                        for (peer in newPeers) {
                            Log.i(TAG, "Smart Routing: registering TiPi/MediaInfo with new peer ${peer.macAddress.toRedactedString()}")
                            device.aacp.sendMediaInformationNewDevice(localMac, peer.macAddress.value)
                            device.aacp.sendAddTiPiDevice(localMac, peer.macAddress.value)
                        }
                    }
                }

                if (state.owns != previousState.owns && !state.owns) {
                    yieldOwnershipToOtherDevice(device, "OWNS_CONNECTION -> false")
                }

                if (state.audioSource != previousState.audioSource) {
                    val src = state.audioSource
                    val localMac = getLocalBluetoothMac()
                    if (
                        src != null &&
                        src.type != me.kavishdevar.librepods.core.bluetooth.aacp.types.AudioSourceType.NONE &&
                        src.mac.value != "00:00:00:00:00:00" &&
                        localMac != null &&
                        !src.mac.value.equals(localMac, ignoreCase = true)
                    ) {
                        if (audioYieldedToOtherDevice.add(device.macAddress)) {
                            Log.i(
                                TAG,
                                "Another device (${src.mac.toRedactedString()}, ${src.type}) is using ${device.macAddress.toRedactedString()}; clearing active A2DP device on this phone"
                            )
                            removeActiveAudioDevice()
                            showIsland(
                                device = device,
                                type = IslandType.MOVED_TO_OTHER_DEVICE,
                                otherDeviceName = resolvePeerDeviceName(device, src.mac.value),
                                onReverseAction = { reverseHandoff(device) }
                            )
                        }
                    } else if (
                        src != null &&
                        src.type != me.kavishdevar.librepods.core.bluetooth.aacp.types.AudioSourceType.NONE &&
                        localMac != null &&
                        src.mac.value.equals(localMac, ignoreCase = true)
                    ) {
                        audioYieldedToOtherDevice.remove(device.macAddress)
                    }
                }

                if (!state.battery.hasSameState(previousState.battery) &&
                    device.connectionState.value == ConnectionState.CONNECTED
                ) {
                    broadcastColorOsBattery(device)
                }

                when {
                    state.state != previousState.state -> {
                        if (state.state == AccessoryState.Disconnected) {
                            if (deviceSettings.alwaysConnectWhenAccessoryDisconnected && System.currentTimeMillis() - state.lastDisconnected > 5_000) {
                                Log.d(TAG, "device ${device.macAddress.toRedactedString()} is now disconnected, connecting")
                            }
                        }

                        if (MediaController.getMusicActive()) {
                            attemptConnect("media")
                        }
                    }

                    state.caseLidOpen != previousState.caseLidOpen -> {
                        if (state.caseLidOpen) {
                            if (appDataRepository.settings.value.proximityCardSettings.requireBothBudsInCase) {
                                val leftInCase = state.componentState.find { it.component == DeviceComponent.LEFT }?.status == ComponentStatus.IN_CASE ||
                                    state.battery.find { it.component == BatteryComponent.LEFT }?.status == BatteryStatus.CHARGING ||
                                    state.battery.find { it.component == BatteryComponent.LEFT }?.status == BatteryStatus.OPTIMIZED_CHARGING

                                val rightInCase = state.componentState.find { it.component == DeviceComponent.RIGHT }?.status == ComponentStatus.IN_CASE ||
                                    state.battery.find { it.component == BatteryComponent.RIGHT }?.status == BatteryStatus.CHARGING ||
                                    state.battery.find { it.component == BatteryComponent.RIGHT }?.status == BatteryStatus.OPTIMIZED_CHARGING

                                if (state.componentState.all { it.status == ComponentStatus.IN_CASE } || (leftInCase && rightInCase)) {
                                    proximityCardWindow.show(
                                        name = device.metadata.value.name,
                                        batteries = state.battery,
                                        spec = AirPodsSpecs.getSpec(device.metadata.value.model),
                                        onConnect = {
                                            device.connect()
                                        },
                                        airPodsConnected = device.connectionState.value == ConnectionState.CONNECTED
                                    )
                                } else proximityCardWindow.close()
                            } else {
                                proximityCardWindow.show(
                                    name = device.metadata.value.name,
                                    batteries = state.battery,
                                    spec = AirPodsSpecs.getSpec(device.metadata.value.model),
                                    onConnect = {
                                        device.connect()
                                    },
                                    airPodsConnected = device.connectionState.value == ConnectionState.CONNECTED
                                )
                            }
                        } else proximityCardWindow.close()
                    }
                    state.capabilities != previousState.capabilities -> {
                        Log.i(
                            TAG,
                            "capabilities changed for device ${device.macAddress.toRedactedString()}"
                        )
                        Log.d(TAG, "capabilities: ${state.capabilities}")

                        if (state.capabilities.isNotEmpty()) {
                            appleRepository.saveCacheFromState(device.macAddress, state)
                        }
                    }

                    !state.battery.hasSameState(previousState.battery) -> {
                        Log.i(
                            TAG,
                            "battery state changed for device ${device.macAddress.toRedactedString()}"
                        )
                        Log.d(TAG, "battery state: ${state.battery.format()}. prev: ${previousState.battery.format()}")

                        Log.d(TAG, "updating widgets")
                        updateWidgets()

                        if (islandWindow.isVisible) {
                            Log.d(TAG, "updating island window")
                            computeDisplayBatteryLevel(state.battery)?.let { level ->
                                islandWindow.updateBattery(level)
                            }
                        }

                        if (proximityCardWindow.isVisible) proximityCardWindow.updateBatteries(state.battery)

                        updateDeviceNotification(device = device)

                        // TODO:Shizuku
                        /* Log.d(TAG, "updating bluetooth metadata")
                        shizuku.setMetadata(
                            device.macAddress.value,
                            BluetoothMetadata.METADATA_UNTETHERED_CASE_BATTERY,
                            state.battery.find { it.component == BatteryComponent.CASE }?.level.toString()
                                .toByteArray()
                        )
                        shizuku.setMetadata(
                            device.macAddress.value,
                            BluetoothMetadata.METADATA_UNTETHERED_CASE_CHARGING,
                            (if (state.battery.find { it.component == BatteryComponent.CASE }?.status == BatteryStatus.CHARGING
                                || state.battery.find { it.component == BatteryComponent.CASE }?.status == BatteryStatus.OPTIMIZED_CHARGING
                            ) "1" else "0").toByteArray()
                        )
                        shizuku.setMetadata(
                            device.macAddress.value,
                            BluetoothMetadata.METADATA_UNTETHERED_LEFT_BATTERY,
                            state.battery.find { it.component == BatteryComponent.LEFT }?.level.toString()
                                .toByteArray()
                        )
                        shizuku.setMetadata(
                            device.macAddress.value,
                            BluetoothMetadata.METADATA_UNTETHERED_LEFT_CHARGING,
                            (if (state.battery.find { it.component == BatteryComponent.LEFT }?.status == BatteryStatus.CHARGING
                                || state.battery.find { it.component == BatteryComponent.LEFT }?.status == BatteryStatus.OPTIMIZED_CHARGING
                            ) "1" else "0").toByteArray()
                        )
                        shizuku.setMetadata(
                            device.macAddress.value,
                            BluetoothMetadata.METADATA_UNTETHERED_RIGHT_BATTERY,
                            state.battery.find { it.component == BatteryComponent.RIGHT }?.level.toString()
                                .toByteArray()
                        )
                        shizuku.setMetadata(
                            device.macAddress.value,
                            BluetoothMetadata.METADATA_UNTETHERED_RIGHT_CHARGING,
                            (if (state.battery.find { it.component == BatteryComponent.RIGHT }?.status == BatteryStatus.CHARGING
                                || state.battery.find { it.component == BatteryComponent.RIGHT }?.status == BatteryStatus.OPTIMIZED_CHARGING
                            ) "1" else "0").toByteArray()
                        ) */
                    }

                    state.componentState != previousState.componentState -> {
                        Log.i(
                            TAG,
                            "component state changed for device ${device.macAddress.toRedactedString()}"
                        )
                        Log.d(TAG, "component state: ${state.componentState}")

                        val earDetectionCtrlCmdValue =
                            state.controlStates[ControlCommandIdentifier.EAR_DETECTION_CONFIG]
                                ?: byteArrayOf(0x01.toByte())
                        Log.d(
                            TAG,
                            "ear detection control command value: ${earDetectionCtrlCmdValue.toHexString()}"
                        )
                        val earDetectionEnabled = earDetectionCtrlCmdValue[0] == 0x01.toByte() || deviceSettings.earDetectionEnabled // temporary, cache broken (?)
                        Log.d(TAG, "ear detection enabled: $earDetectionEnabled")
                        if (earDetectionEnabled) {
                            processComponentStateChange(
                                device = device,
                                previousComponentState = previousState.componentState,
                                newComponentState = state.componentState,
                                disconnectWhenNotWearing = deviceSettings.disconnectWhenNotWearing
                            )
                        }

                        Log.d(TAG, "updating notification")
                        updateDeviceNotification(device = device)
                        updateWidgets()
                    }

                    state.controlStates != previousState.controlStates -> {
                        Log.i(
                            TAG,
                            "control states changed for device ${device.macAddress.toRedactedString()}"
                        )
                        Log.d(TAG, "control states: ${state.controlStates}")

                        if (state.controlStates.isNotEmpty()) {
                            appleRepository.saveCacheFromState(device.macAddress, state)
                        }

                        Log.d(TAG, "updating notification")
                        updateDeviceNotification(device = device)
                        updateWidgets()
                    }

                    state.conversationAwarenessState != previousState.conversationAwarenessState -> {
                        Log.i(
                            TAG,
                            "conversation awareness state changed for device ${device.macAddress.toRedactedString()}"
                        )
                        Log.d(
                            TAG,
                            "conversation awareness state: ${state.conversationAwarenessState}"
                        )

                        when (state.conversationAwarenessState) {
                            1 -> {
                                MediaController.startSpeaking()
                                MediaController.duckVolume(
                                    deviceSettings.conversationAwarenessVolume.toInt()
                                )
                            }

                            2 -> {
                                // 1 and 2 both mean "started speaking"; only the first one records
                                // the volume to come back to, so a sequence starting at 2 still restores
                                MediaController.startSpeaking()
                                MediaController.duckVolume(
                                    deviceSettings.conversationAwarenessReducedVolume.toInt()
                                )
                            }

                            3 -> {
                                MediaController.duckVolume(
                                    deviceSettings.conversationAwarenessVolume.toInt()
                                )
                            }

                            6, 7, 8, 9 -> {
                                MediaController.stopSpeaking()
                            }
                        }
                    }

                    state.currentHeartRate?.timestamp != previousState.currentHeartRate?.timestamp -> {
                        state.currentHeartRate?.let { heartRateSample ->
                            Log.i(
                                TAG,
                                "current heart rate changed from device ${device.macAddress.toRedactedString()}"
                            )
                            Log.d(
                                TAG,
                                "current heart rate: ${heartRateSample.bpm} bpm, timestamp: ${heartRateSample.timestamp.toJavaInstant()}. processing."
                            )

                            processHeartRateSample(
                                heartRateSample = heartRateSample,
                                interval = state.heartRateInterval,
                                alertThreshold = deviceSettings.hrmAlertThreshold
                            )
                        }
                    }

                    state.recordingState != previousState.recordingState -> {
                        Log.i(
                            TAG,
                            "recording state changed for device ${device.macAddress.toRedactedString()}"
                        )
                        Log.d(TAG, "recording state: ${state.recordingState}")
                        if (previousState.recordingState.isRecording && !state.recordingState.isRecording) {
                            previousState.recordingState.recording?.let { recording ->
                                wavWriters[recording]?.close()
                            }
                        } else if (!previousState.recordingState.isRecording && state.recordingState.isRecording) {
                            state.recordingState.recording?.let { recording ->
                                try {
                                    val wavWriter = WavWriter(recording.file)
                                    wavWriters[recording] = wavWriter
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to create WavWriter for recording: ${recording.file}", e)
                                }
                            }
                        } else {
                            Log.w(TAG, "Recording state changed but no action taken. previous: ${previousState.recordingState}, current: ${state.recordingState}")
                        }
                    }
                }
                previousState = state
            }
        }
    }

    fun observeAppleSettings(device: AppleDevice) {
        serviceCoroutineScope.launch {
            var previousSettings = device.settings.value

            device.settings.collect { settings ->
                if (settings != previousSettings) {
                    Log.i(TAG, "settings changed for device ${device.macAddress.toRedactedString()}")
                    Log.d(TAG, "settings: $settings")

                    appleRepository.saveSettings(device.macAddress, settings)
                }
                previousSettings = settings
            }
        }
    }

    fun observeAppleMetadata(device: AppleDevice) {
        serviceCoroutineScope.launch {
            var previousMetadata = device.metadata.value

            device.metadata.collect { metadata ->
                if (metadata != previousMetadata) {
                    Log.i(TAG, "metadata changed for device ${device.macAddress.toRedactedString()}")
                    Log.d(TAG, "metadata: $metadata")

                    appleRepository.saveMetadata(device.macAddress, metadata)

                    setAppleBluetoothMetadata(device)

                    val notificationManager = getSystemService(NotificationManager::class.java)
                    val channel =
                        notificationManager.getNotificationChannel("device_${device.macAddress}")
                    channel?.name = "Device ${metadata.name}"
                    notificationManager.createNotificationChannel(channel)
                }
                previousMetadata = metadata
            }
        }
    }

    fun observeAppleMicrophoneFrames(device: AppleDevice) {
        serviceCoroutineScope.launch {
            device.microphoneFrames.collect { frame ->
                val state = device.state.value

                if (state.recordingState.isRecording) {
                    eldDecoder.decode(
                        accessUnit = frame.accessUnit
                    ) { pcm ->
                        wavWriters[state.recordingState.recording]?.write(pcm)

                        device.updateMicrophoneState {
                            it.copy(
                                isActive = true,
                                packetsReceived = it.packetsReceived + 1,
                                durationMs = it.durationMs + 30,
                                level = calculateLevel(pcm)
                            )
                        }
                    }
                }
            }
        }
    }

    private fun computeDisplayBatteryLevel(
        batteries: Set<me.kavishdevar.librepods.core.devices.Battery>
    ): Int? {
        val buds = batteries.filter {
            (it.component == BatteryComponent.LEFT || it.component == BatteryComponent.RIGHT) && it.level > 0
        }
        val activeBuds = buds.filter { it.status != BatteryStatus.DISCONNECTED }
        val candidates = activeBuds.ifEmpty { buds }
        return candidates.minOfOrNull { it.level }
    }

    fun showIsland(
        device: Device<*, *, *>,
        type: IslandType = IslandType.CONNECTED,
        reversed: Boolean = false,
        otherDeviceName: String? = null,
        onReverseAction: () -> Unit = {}
    ) {
        Log.d(TAG, "Showing island window")

        val state = device.state.value
        val settings = device.settings.value

        when (state) {
            is AppleState -> {
                val device = device as AppleDevice
                val state = device.state.value
                val settings = settings as AppleSettings
                val metadata = device.metadata.value

                if (state.componentState.isEmpty()) {
                    Log.w(TAG, "No component state available, can't show island")
                    return
                }

                if (settings.showIslandPopup) {
                    if (!Settings.canDrawOverlays(this)) {
                        Log.w(TAG, "No permission for SYSTEM_ALERT_WINDOW")
                        return
                    }

                    Log.i(TAG, "Showing island for device ${device.macAddress.toRedactedString()}")

                    val batteryPercentage = computeDisplayBatteryLevel(state.battery) ?: 0

                    islandWindow.show(
                        name = metadata.name,
                        batteryLevel = batteryPercentage,
                        type = type,
                        reversed = reversed,
                        otherDeviceName = otherDeviceName,
                        onReverseAction = onReverseAction,

                        openApp = {
                            val intent = Intent(
                                this,
                                MainActivity::class.java,
                            ).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            startActivity(intent)
                        },

                        showOffListeningMode = state.controlStates[ControlCommandIdentifier.ALLOW_OFF_OPTION]?.getOrNull(0)?.toInt() == 1,
                        noiseControlModeValue = state.controlStates[ControlCommandIdentifier.LISTENING_MODE]?.getOrNull(0)?.toInt() ?: 3,
                        onNoiseControlModeChanged = {
                            device.setControlCommand(
                                ControlCommandIdentifier.LISTENING_MODE, it
                            )
                        },
                        adaptiveStrength = state.controlStates[ControlCommandIdentifier.AUTO_ANC_STRENGTH]?.getOrNull(0)?.toInt() ?: 50,
                        onAdaptiveStrengthChanged = {
                            device.setControlCommand(
                                ControlCommandIdentifier.AUTO_ANC_STRENGTH, it
                            )
                        }
                    )
                }
            }

            else -> {
                Log.d(
                    TAG,
                    "Unsupported device state: ${device.state.value.javaClass.simpleName}, not showing island"
                )
                return
            }
        }
    }

    fun refreshDeviceNotifications() {
        devices.value.values.forEach { updateDeviceNotification(it) }
    }

    fun updateDeviceNotification(
        device: Device<*, *, *>
    ) {
        val notificationManager = getSystemService(NotificationManager::class.java)

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationId = device.macAddress.toNotificationId()

        if (device.connectionState.value == ConnectionState.CONNECTED || device.connectionState.value == ConnectionState.AVAILABLE) {
            when (device) {
                is AppleDevice -> {
                    Log.d(
                        TAG,
                        "Updating notification for Apple device ${device.macAddress.toRedactedString()}"
                    )

                    val updatedNotificationBuilder =
                        NotificationCompat.Builder(this, "device_${device.macAddress}")
                            .setSmallIcon(R.drawable.ic_airpods)
                            .setContentTitle(device.metadata.value.name)
                            .setContentText(device.state.value.battery.joinToString(" ") { "${it.component.name[0]}: ${it.level}%" })
                            .setContentIntent(pendingIntent)
                            .setCategory(Notification.CATEGORY_STATUS)
                            .setPriority(NotificationCompat.PRIORITY_LOW)
                            .setOngoing(true)
                    if (device.connectionState.value == ConnectionState.CONNECTED) {
                        val showModeButtons = getSharedPreferences("settings", MODE_PRIVATE)
                            .getBoolean(PREF_NOTIFICATION_MODE_BUTTONS, true)
                        if (showModeButtons) {
                            if (device.state.value.controlStates[ControlCommandIdentifier.ALLOW_OFF_OPTION]?.getOrNull(0) == 0x01.toByte()) {
                                updatedNotificationBuilder.addAction(
                                    R.drawable.ic_noise_cancellation,
                                    getString(R.string.off),
                                    PendingIntent.getService(
                                        this,
                                        notificationId + 1,
                                        Intent(this, LibrePodsService::class.java).apply {
                                            action = LibrePodsIntents.ACTION_SET_ANC_MODE
                                            putExtra(LibrePodsIntents.Extras.MAC_ADDRESS, device.macAddress.toString())
                                            putExtra(LibrePodsIntents.Extras.ANC_MODE, 1)
                                        },
                                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                                    ),
                                )
                            }
                            updatedNotificationBuilder
                                .addAction(
                                    R.drawable.ic_transparency, // icon never shows up?? (tested on AOSP ROMs)
                                    getString(R.string.transparency),
                                    PendingIntent.getService(
                                        this,
                                        notificationId + 3,
                                        Intent(this, LibrePodsService::class.java).apply {
                                            action = LibrePodsIntents.ACTION_SET_ANC_MODE
                                            putExtra(LibrePodsIntents.Extras.MAC_ADDRESS, device.macAddress.toString())
                                            putExtra(LibrePodsIntents.Extras.ANC_MODE, 3)
                                        },
                                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                                    ),
                                )
                                .addAction(
                                    R.drawable.ic_adaptive,
                                    getString(R.string.adaptive),
                                    PendingIntent.getService(
                                        this,
                                        notificationId + 4,
                                        Intent(this, LibrePodsService::class.java).apply {
                                            action = LibrePodsIntents.ACTION_SET_ANC_MODE
                                            putExtra(LibrePodsIntents.Extras.MAC_ADDRESS, device.macAddress.toString())
                                            putExtra(LibrePodsIntents.Extras.ANC_MODE, 4)
                                        },
                                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                                    ),
                                )
                                .addAction(
                                    R.drawable.ic_noise_cancellation,
                                    getString(R.string.noise_cancellation),
                                    PendingIntent.getService(
                                        this,
                                        notificationId + 2,
                                        Intent(this, LibrePodsService::class.java).apply {
                                            action = LibrePodsIntents.ACTION_SET_ANC_MODE
                                            putExtra(LibrePodsIntents.Extras.MAC_ADDRESS, device.macAddress.toString())
                                            putExtra(LibrePodsIntents.Extras.ANC_MODE, 2)
                                        },
                                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                                    ),
                                )
                        }
                    } else {
                        updatedNotificationBuilder.addAction(
                            R.drawable.ic_power,
                            getString(R.string.connect),
                            PendingIntent.getService(
                                this,
                                notificationId + 5,
                                Intent(this, LibrePodsService::class.java).apply {
                                    action = LibrePodsIntents.ACTION_CONNECT
                                    putExtra(LibrePodsIntents.Extras.MAC_ADDRESS, device.macAddress.toString())
                                },
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                            )
                        )
                    }

                    val updatedNotification = updatedNotificationBuilder.build()

                    notificationManager.notify(notificationId, updatedNotification)
                }
            }

        } else {
            notificationManager.cancel(notificationId)
        }
    }

    fun startForegroundNotification() {
        val disconnectedNotificationChannel = NotificationChannel(
            "foreground_service_status",
            getString(R.string.foreground_service_status),
            NotificationManager.IMPORTANCE_NONE
        )

        val hrmAlertChannel = NotificationChannel(
            "hrm_alert",
            getString(R.string.heart_rate_alert),
            NotificationManager.IMPORTANCE_HIGH
        )

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(disconnectedNotificationChannel)
        notificationManager.createNotificationChannel(hrmAlertChannel)

        val notificationSettingsIntent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            putExtra(Settings.EXTRA_CHANNEL_ID, "foreground_service_status")
        }

        val pendingIntentNotifDisable = PendingIntent.getActivity(
            this,
            0,
            notificationSettingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, "foreground_service_status")
            .setSmallIcon(R.drawable.ic_airpods).setContentTitle(getString(R.string.service_running))
            .setContentText(getString(R.string.foreground_notification_description))
            .setContentIntent(pendingIntentNotifDisable).setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW).setOngoing(true).build()

        try {
            startForeground(1, notification)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun updateWidgets() {
        // TODO: Room
        val widgetMobileBatteryEnabled = getSharedPreferences(
            "settings",
            MODE_PRIVATE
        ).getBoolean("show_phone_battery_in_widget", false)

        val appWidgetManager = AppWidgetManager.getInstance(this)
        val componentName = ComponentName(this, BatteryWidget::class.java)
        val widgetIds = appWidgetManager.getAppWidgetIds(componentName)

        val remoteViews = RemoteViews(packageName, R.layout.battery_widget).also { it ->
            val openActivityIntent = PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            it.setOnClickPendingIntent(R.id.battery_widget, openActivityIntent)

            // TODO: device-specific widgets
            val device =
                devices.value.values.firstOrNull { it is AppleDevice && it.state.value.battery.any { it.level > 0 } } as? AppleDevice

            Log.d(TAG, "picked device: ${device?.macAddress?.toRedactedString()} for widget update")

            val leftBattery =
                device?.state?.value?.battery?.find { it.component == BatteryComponent.LEFT }
            val rightBattery =
                device?.state?.value?.battery?.find { it.component == BatteryComponent.RIGHT }
            val caseBattery =
                device?.state?.value?.battery?.find { it.component == BatteryComponent.CASE }

            it.setTextViewText(R.id.left_battery_widget, leftBattery?.let {
                "${it.level}%"
            } ?: "")
            it.setProgressBar(
                R.id.left_battery_progress, 100, leftBattery?.level ?: 0, false
            )
            it.setViewVisibility(
                R.id.left_charging_icon,
                if (leftBattery?.status == BatteryStatus.CHARGING || leftBattery?.status == BatteryStatus.OPTIMIZED_CHARGING) View.VISIBLE else View.GONE
            )

            it.setTextViewText(R.id.right_battery_widget, rightBattery?.let {
                "${it.level}%"
            } ?: "")
            it.setProgressBar(
                R.id.right_battery_progress, 100, rightBattery?.level ?: 0, false
            )
            it.setViewVisibility(
                R.id.right_charging_icon,
                if (rightBattery?.status == BatteryStatus.CHARGING || rightBattery?.status == BatteryStatus.OPTIMIZED_CHARGING) View.VISIBLE else View.GONE
            )

            it.setTextViewText(R.id.case_battery_widget, caseBattery?.let {
                "${it.level}%"
            } ?: "")
            it.setProgressBar(
                R.id.case_battery_progress, 100, caseBattery?.level ?: 0, false
            )
            it.setViewVisibility(
                R.id.case_charging_icon,
                if (caseBattery?.status == BatteryStatus.CHARGING || caseBattery?.status == BatteryStatus.OPTIMIZED_CHARGING) View.VISIBLE else View.GONE
            )

            it.setViewVisibility(
                R.id.phone_battery_widget_container,
                if (widgetMobileBatteryEnabled) View.VISIBLE else View.GONE
            )
            if (widgetMobileBatteryEnabled) {
                val batteryManager = getSystemService(BatteryManager::class.java)
                val batteryLevel =
                    batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val charging =
                    batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) == BatteryManager.BATTERY_STATUS_CHARGING
                it.setTextViewText(
                    R.id.phone_battery_widget, "$batteryLevel%"
                )
                it.setViewVisibility(
                    R.id.phone_charging_icon, if (charging) View.VISIBLE else View.GONE
                )
                it.setProgressBar(
                    R.id.phone_battery_progress, 100, batteryLevel, false
                )
            }
        }
        appWidgetManager.updateAppWidget(widgetIds, remoteViews)

        val noiseControlIds = appWidgetManager.getAppWidgetIds(
            ComponentName(this, NoiseControlWidget::class.java)
        )
        if (noiseControlIds.isNotEmpty()) {
            NoiseControlWidget.updateAppWidgets(this, appWidgetManager, noiseControlIds)
        }
    }

    private fun processComponentStateChange(
        device: Device<*, *, *>,
        previousComponentState: Set<DeviceComponentState>,
        newComponentState: Set<DeviceComponentState>,
        disconnectWhenNotWearing: Boolean
    ) {
        val old = earPresenceOf(previousComponentState)
        val new = earPresenceOf(newComponentState)
        if (old == new) return

        Log.d(TAG, "earPresence: $old -> $new")

        // new != NONE because old!=new
        if (old == EarPresence.NONE) {
            showIsland(
                device = device,
                type = IslandType.CONNECTED
            )
            Log.i(TAG, "User put in at least one component, showing island.")
        }

        if (new == EarPresence.NONE && appDataRepository.settings.value.islandSettings.keepCompactVisible) {
            islandWindow.close()
        }

        var justEnabledA2dp = false

        when {
            old == EarPresence.NONE -> {
                if (device.macAddress !in audioReleasedByLibrePods &&
                    (device.macAddress in audioDisconnectingDevices || pendingAudioReleaseChecks.containsKey(device.macAddress))
                ) {
                    Log.d(
                        TAG,
                        "Audio for ${device.macAddress.toRedactedString()} is disconnecting externally; skipping ear-detection audio reconnect"
                    )
                } else {
                    Log.d(
                        TAG,
                        "User put in at least one component, enabling audio for device ${device.macAddress.toRedactedString()}"
                    )
                    // If A2DP isn't up yet (e.g. we released it when the buds came out),
                    // playing now would start on the phone speaker until A2DP connects.
                    // In that case only the waitForA2dpConnection callback below sends play.
                    val a2dpAlreadyConnected = try {
                        a2dpProxy?.getConnectionState(device.bluetoothDevice) == BluetoothProfile.STATE_CONNECTED
                    } catch (_: Exception) {
                        false
                    }
                    audioReleasedByLibrePods.remove(device.macAddress)
                    device.enableAudio()
                    device.connectA2dp()
                    justEnabledA2dp = true

                    cancelPendingA2dpReceiver(device.macAddress)
                    pendingA2dpReceivers[device.macAddress] = device.waitForA2dpConnection(this) {
                        pendingA2dpReceivers.remove(device.macAddress)
                        // A2DP CONNECTED arrives ~250-400 ms before AudioPolicy moves media
                        // off the speaker; wait for the route (max 1.5 s) so play doesn't blip.
                        serviceCoroutineScope.launch(Dispatchers.Main) {
                            var waitedMs = 0
                            while (isMediaRoutedToSpeaker() && waitedMs < 1_500) {
                                delay(50)
                                waitedMs += 50
                            }
                            Log.d(TAG, "A2DP connected; sending deferred play after ${waitedMs}ms route wait")
                            MediaController.sendPlay()
                            MediaController.iPausedTheMedia = false
                        }
                    }

                    if (MediaController.getMusicActive()) {
                        MediaController.userPlayedTheMedia = true
                    }
                    if (new == EarPresence.PARTIAL && a2dpAlreadyConnected) {
                        MediaController.sendPlay()
                        MediaController.iPausedTheMedia = false
                    } else if (new == EarPresence.PARTIAL) {
                        Log.d(TAG, "A2DP not connected yet; deferring play until it connects")
                    }
                }
            }

            new == EarPresence.NONE -> {
                cancelPendingA2dpReceiver(device.macAddress)
                MediaController.sendPause(force = true)
                if (disconnectWhenNotWearing) {
                    Log.d(
                        TAG,
                        "Disconnecting audio for device ${device.macAddress.toRedactedString()} because user took out all components and disconnectWhenNotWearing is true"
                    )
                    audioYieldedToOtherDevice.remove(device.macAddress)
                    audioReleasedByLibrePods.add(device.macAddress)
                    clearYieldedAudioStrategies()
                    device.disableAudio()
                    device.disconnectAudio()
                    scheduleRestoreSpeakerVolume()
                }
            }
        }

        when {
            new == EarPresence.FULL -> {
                Log.d("AirPodsParser", "User put in all components.")
                MediaController.userPlayedTheMedia = false
                if (!justEnabledA2dp) {
                    MediaController.sendPlay()
                    MediaController.iPausedTheMedia = false
                }
            }

            old == EarPresence.FULL -> {
                Log.d("AirPodsParser", "User took one out.")
                MediaController.userPlayedTheMedia = false
                if (new == EarPresence.PARTIAL) {
                    MediaController.sendPause()
                }
            }
        }
    }

    private fun processHeartRateSample(heartRateSample: HeartRateSample, interval: Duration, alertThreshold: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            Log.d(TAG, "inserting to local db")
            heartRateRepository.insert(heartRateSample)
        }

        CoroutineScope(Dispatchers.Default).launch {
            val notificationManager = getSystemService(NotificationManager::class.java)

            if (heartRateSample.bpm > alertThreshold) {
                val notification = NotificationCompat.Builder(this@LibrePodsService, "hrm_alert")
                    .setSmallIcon(R.drawable.ic_pulse_alert)
                    .setContentTitle(getString(R.string.high_heart_rate))
                    .setContentText(
                        getString(
                            R.string.high_heart_rate_notification_text,
                            heartRateSample.bpm
                        )
                    )
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setOngoing(true)
                    .build()

                notificationManager.notify(999, notification)
            } else {
                if (notificationManager.activeNotifications.any { it.id == 999 }) {
                    notificationManager.cancel(999)
                }
            }
        }

        CoroutineScope(Dispatchers.IO).launch {
            if (SdkExtensions.getExtensionVersion(Build.VERSION_CODES.UPSIDE_DOWN_CAKE) >= 7) {
                if (checkSelfPermission(HealthPermission.getWritePermission(HeartRateRecord::class)) != PackageManager.PERMISSION_GRANTED) return@launch
                val healthConnectHeartRateSample = HeartRateRecord.Sample(
                    time = heartRateSample.timestamp.toJavaInstant(),
                    beatsPerMinute = heartRateSample.bpm.toLong()
                )

                val zoneOffset =
                    ZoneOffset.systemDefault().rules.getOffset(heartRateSample.timestamp.toJavaInstant())

                val heartRateRecord = HeartRateRecord(
                    startTime = (heartRateSample.timestamp - interval).toJavaInstant(),
                    endTime = heartRateSample.timestamp.toJavaInstant(),
                    startZoneOffset = zoneOffset,
                    endZoneOffset = zoneOffset,
                    samples = listOf(healthConnectHeartRateSample),
                    metadata = androidx.health.connect.client.records.metadata.Metadata.autoRecorded(
                        device = androidx.health.connect.client.records.metadata.Device(type = androidx.health.connect.client.records.metadata.Device.TYPE_HEARABLE)
                    )
                )

                if (healthConnectClient != null) {
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            healthConnectClient!!.insertRecords(listOf(heartRateRecord))
                        } catch (e: Exception) {
                            Log.e(TAG, "Error inserting heart rate record", e)
                        }
                    }
                } else {
                    Log.w(TAG, "Health Connect client not available")
                }
            } else {
                Log.d(TAG, "U SDK Extension <7")
            }
        }
    }

    private fun broadcastColorOsBattery(device: AppleDevice, disconnected: Boolean = false) {
        if (checkSelfPermission("android.permission.BLUETOOTH_PRIVILEGED") != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val payload = if (disconnected) {
            intArrayOf(-1, -1, -1, 4, 4, 4)
        } else {
            val batteries = device.state.value.battery
            if (batteries.isEmpty()) return
            val left = batteries.find { it.component == BatteryComponent.LEFT }
            val right = batteries.find { it.component == BatteryComponent.RIGHT }
            val caseBat = batteries.find { it.component == BatteryComponent.CASE }

            fun level(b: me.kavishdevar.librepods.core.devices.Battery?): Int =
                if (b != null && b.status != BatteryStatus.DISCONNECTED && b.level in 0..100) b.level else -1

            fun status(b: me.kavishdevar.librepods.core.devices.Battery?): Int = when (b?.status) {
                BatteryStatus.CHARGING, BatteryStatus.OPTIMIZED_CHARGING -> 1
                BatteryStatus.NOT_CHARGING -> 2
                else -> 4
            }

            intArrayOf(
                level(left),
                level(right),
                level(caseBat),
                status(left),
                status(right),
                status(caseBat)
            )
        }

        try {
            sendBroadcast(
                Intent(ACTION_INJECT_COLOROS_BATTERY).apply {
                    setPackage("com.android.bluetooth")
                    putExtra(EXTRA_COLOROS_BATTERY_MAC, device.macAddress.value)
                    putExtra(EXTRA_COLOROS_BATTERY_ARRAY, payload)
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to broadcast ColorOS battery update", e)
        }
    }

    // TODO: Shizuku
    private fun setAppleBluetoothMetadata(
        @Suppress("unused") device: AppleDevice
    ) {
        /* val macAddress = device.macAddress.value
        val metadata = device.metadata.value
        val spec = AirPodsSpecs.getSpec(metadata.model)

        shizuku.setMetadata(
            macAddress,
            BluetoothMetadata.METADATA_MAIN_ICON,
            resToUri(spec.primaryImageRes).toString().toByteArray()
        )
        shizuku.setMetadata(
            macAddress, BluetoothMetadata.METADATA_MODEL_NAME, metadata.modelNumber.toByteArray()
        )
        shizuku.setMetadata(
            macAddress,
            BluetoothMetadata.METADATA_DEVICE_TYPE,
            BluetoothMetadata.DEVICE_TYPE_UNTETHERED_HEADSET.toByteArray()
        )
        spec.caseImageRes?.let {
            shizuku.setMetadata(
                macAddress,
                BluetoothMetadata.METADATA_UNTETHERED_CASE_ICON,
                resToUri(it).toString().toByteArray()
            )
        }
//        shizuku.setMetadata(
//            macAddress,
//            BluetoothMetadata.METADATA_UNTETHERED_RIGHT_ICON,
//            resToUri(spec.components.find { it.type == DeviceComponent.RIGHT }.imageRes).toString().toByteArray()
//        )
//        shizuku.setMetadata(
//            macAddress,
//            BluetoothMetadata.METADATA_UNTETHERED_LEFT_ICON,
//            resToUri(spec.components.find { it.type == DeviceComponent.LEFT }.imageRes).toString().toByteArray()
//        )
        shizuku.setMetadata(
            macAddress,
            BluetoothMetadata.METADATA_MANUFACTURER_NAME,
            metadata.manufacturer.toByteArray()
        )
        shizuku.setMetadata(
            macAddress, BluetoothMetadata.METADATA_COMPANION_APP, "me.kavishdevar.librepods".toByteArray()
        )
        shizuku.setMetadata(
            macAddress,
            BluetoothMetadata.METADATA_UNTETHERED_CASE_LOW_BATTERY_THRESHOLD,
            "20".toByteArray()
        )
        shizuku.setMetadata(
            macAddress,
            BluetoothMetadata.METADATA_UNTETHERED_LEFT_LOW_BATTERY_THRESHOLD,
            "20".toByteArray()
        )
        shizuku.setMetadata(
            macAddress,
            BluetoothMetadata.METADATA_UNTETHERED_RIGHT_LOW_BATTERY_THRESHOLD,
            "20".toByteArray()
        )
        Log.d(TAG, "Metadata set for apple device ${device.macAddress.toRedactedString()}")
     */
    }
}

// TODO: move out of this file
private enum class EarPresence { NONE, PARTIAL, FULL }

private fun earPresenceOf(components: Set<DeviceComponentState>): EarPresence {
    if (components.isEmpty()) return EarPresence.NONE
    val inEarCount = components.count { it.status == ComponentStatus.IN_EAR }
    return when (inEarCount) {
        0 -> EarPresence.NONE
        components.size -> EarPresence.FULL
        else -> EarPresence.PARTIAL
    }
}
