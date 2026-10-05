package com.btmicfix.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.btmicfix.BuildConfig
import com.btmicfix.service.RoutingKeepAliveService
import com.btmicfix.util.Logger
import com.btmicfix.util.Preferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Core audio routing manager.
 *
 * This is the primary mechanism for fixing Bluetooth mic routing.
 * It uses [AudioManager.setCommunicationDevice] (API 31+) to force the system
 * to use a Bluetooth device for communication audio input.
 *
 * The key insight: AI voice apps fail to trigger the A2DP → SCO/HFP profile
 * switch that enables the Bluetooth microphone. By calling setCommunicationDevice()
 * before the AI app starts recording, we force the system to make the switch.
 *
 * Self-healing: once the user enables routing ([routingWanted] = true), the route is
 * re-applied automatically whenever the system drops it — the audio mode is reset,
 * the communication device changes, or the earbuds' audio device is re-created
 * (which can happen when unrelated Bluetooth/Wi-Fi activity occurs on some
 * Samsung devices). Only the user's own "Disable" clears that intent.
 *
 * A single process-wide instance is shared by the Activity and the services
 * (see [getInstance]) so they never disagree about the routing state.
 */
class AudioRoutingManager private constructor(context: Context) {

    private val appContext: Context = context.applicationContext

    private val audioManager: AudioManager =
        appContext.getSystemService<AudioManager>()
            ?: throw IllegalStateException("AudioManager not available")

    private val preferences = Preferences(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = ContextCompat.getMainExecutor(appContext)

    private val _routingState = MutableStateFlow<RoutingState>(RoutingState.Idle)
    val routingState: StateFlow<RoutingState> = _routingState.asStateFlow()

    private val _availableDevices = MutableStateFlow<List<BluetoothAudioDevice>>(emptyList())
    val availableDevices: StateFlow<List<BluetoothAudioDevice>> = _availableDevices.asStateFlow()

    private var currentRoutedDevice: AudioDeviceInfo? = null
    private var monitoring = false

    /** True while the phone's audio mode is in communication because of us. */
    private var routeApplied = false

    /**
     * The user's intent. True after the user enables routing, false only after
     * the user disables it. Persisted so a restarted process resumes routing.
     */
    @Volatile
    var routingWanted: Boolean = preferences.routingWanted
        private set

    // Guard against fighting the system forever if it keeps resetting the route.
    private val reapplyTimes = ArrayDeque<Long>()
    private var reapplyPausedUntil = 0L

    private val ensureRunnable = Runnable { ensureRouting() }

    // Listen for device additions/removals
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            Logger.i("Audio devices added: ${addedDevices.map { deviceTypeToString(it.type) }}")
            refreshAvailableDevices()
            scheduleEnsure("devices added")
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            Logger.i("Audio devices removed: ${removedDevices.map { deviceTypeToString(it.type) }}")
            currentRoutedDevice?.let { routed ->
                if (removedDevices.any { it.id == routed.id }) {
                    // Do not drop the user's intent here: the same earbuds are often
                    // re-added a moment later under a new device id.
                    Logger.w("Routed device was removed, will re-check routing")
                    currentRoutedDevice = null
                }
            }
            refreshAvailableDevices()
            scheduleEnsure("devices removed")
        }
    }

    private val communicationDeviceListener =
        AudioManager.OnCommunicationDeviceChangedListener { device ->
            Logger.i(
                "Communication device changed: " +
                    (device?.let { deviceTypeToString(it.type) } ?: "none")
            )
            if (routingWanted && !isBluetooth(device)) {
                scheduleEnsure("communication device changed")
            }
        }

    private val modeListener = AudioManager.OnModeChangedListener { mode ->
        Logger.i("Audio mode changed: $mode")
        if (routingWanted && mode == AudioManager.MODE_NORMAL) {
            scheduleEnsure("audio mode reset")
        }
    }

    /**
     * Sealed class representing the current routing state.
     */
    sealed class RoutingState {
        data object Idle : RoutingState()
        data class Routing(val deviceName: String) : RoutingState()
        data class Active(val deviceName: String) : RoutingState()
        data class Failed(val reason: String) : RoutingState()
    }

    /**
     * Represents a Bluetooth audio device that can be used for communication.
     */
    data class BluetoothAudioDevice(
        val deviceInfo: AudioDeviceInfo,
        val name: String,
        val type: Int,
        val typeLabel: String,
    )

    /**
     * Start monitoring for audio device changes. Safe to call more than once.
     * Monitoring then lasts for the life of the process, so the route can be
     * repaired even while no screen is open.
     */
    fun startMonitoring() {
        registerListenersOnce()
        if (routingWanted) {
            RoutingKeepAliveService.start(appContext)
            scheduleEnsure("monitoring started")
        }
    }

    /**
     * Called by [RoutingKeepAliveService] when it (re)starts. Must not start the
     * service again, or the two would keep restarting each other.
     */
    fun onKeepAliveServiceStarted() {
        registerListenersOnce()
        scheduleEnsure("keep-alive service started")
    }

    private fun registerListenersOnce() {
        if (!monitoring) {
            Logger.i("Starting audio device monitoring")
            audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
            audioManager.addOnCommunicationDeviceChangedListener(
                mainExecutor, communicationDeviceListener
            )
            audioManager.addOnModeChangedListener(mainExecutor, modeListener)
            monitoring = true
        }
        refreshAvailableDevices()
    }

    /**
     * Kept for API compatibility. Monitoring is process-wide now, so a screen or
     * service going away must not stop the self-healing of an active route.
     */
    fun stopMonitoring() {
        Logger.d("stopMonitoring() ignored — monitoring is process-wide")
    }

    /**
     * USER ACTION: route communication audio to the specified Bluetooth device
     * and keep it routed until the user disables routing.
     *
     * @return The resulting [RoutingState] after the attempt.
     */
    fun routeToBluetooth(device: AudioDeviceInfo): RoutingState {
        setRoutingWanted(true)
        resetReapplyGuard()
        return applyRoute(device)
    }

    /**
     * USER ACTION: route to the first available Bluetooth communication device.
     * If none is connected yet, routing turns on as soon as one appears.
     */
    fun routeToFirstAvailableBluetooth(): RoutingState {
        setRoutingWanted(true)
        resetReapplyGuard()
        val btDevice = findFirstBluetoothCommunicationDevice()
        if (btDevice == null) {
            val state = RoutingState.Failed(WAITING_FOR_DEVICE)
            _routingState.value = state
            Logger.w("No BT communication devices available, waiting for one")
            return state
        }
        return applyRoute(btDevice)
    }

    /**
     * USER ACTION: route to a specific device by matching its address from preferences.
     */
    fun routeToDeviceByAddress(address: String): RoutingState {
        val targetDevice = getAvailableCommunicationDevices().find { deviceInfo ->
            deviceInfo.address == address
        }
        if (targetDevice == null) {
            val state = RoutingState.Failed("Paired device not found in available devices")
            _routingState.value = state
            Logger.w("Device with address ${if (BuildConfig.DEBUG) address else "REDACTED"} not found")
            return state
        }
        return routeToBluetooth(targetDevice)
    }

    /**
     * USER ACTION: clear the communication device routing and stop keeping it alive.
     */
    fun clearRouting() {
        Logger.i("Routing disabled by user")
        setRoutingWanted(false)
        mainHandler.removeCallbacks(ensureRunnable)
        releaseRoute()
        _routingState.value = RoutingState.Idle
    }

    /**
     * A Bluetooth device somewhere disconnected. This fires for ANY device —
     * a watch, a TV, a BLE beacon — so it must not clear our route by itself.
     * We only re-check whether our earbuds are still there.
     */
    fun onBluetoothConnectionChanged() {
        scheduleEnsure("bluetooth connection changed")
    }

    /**
     * Get all available communication devices (these are devices the system
     * can use for two-way audio — specifically, devices that support SCO/HFP).
     */
    fun getAvailableCommunicationDevices(): List<AudioDeviceInfo> {
        return audioManager.availableCommunicationDevices
    }

    /**
     * Find the first Bluetooth device in the available communication devices.
     */
    fun findFirstBluetoothCommunicationDevice(): AudioDeviceInfo? {
        return audioManager.availableCommunicationDevices.firstOrNull { isBluetooth(it) }
    }

    /**
     * Check if the currently set communication device is a Bluetooth device.
     */
    fun isBluetoothRouted(): Boolean {
        return currentRoutedDevice != null && _routingState.value is RoutingState.Active
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private fun setRoutingWanted(wanted: Boolean) {
        if (routingWanted == wanted) return
        routingWanted = wanted
        preferences.routingWanted = wanted
        if (wanted) {
            RoutingKeepAliveService.start(appContext)
        } else {
            RoutingKeepAliveService.stop(appContext)
        }
    }

    private fun resetReapplyGuard() {
        reapplyTimes.clear()
        reapplyPausedUntil = 0L
    }

    /** Coalesce bursts of system events into one check shortly afterwards. */
    private fun scheduleEnsure(reason: String) {
        if (!routingWanted) return
        Logger.d("Scheduling routing check: $reason")
        mainHandler.removeCallbacks(ensureRunnable)
        mainHandler.postDelayed(ensureRunnable, ENSURE_DELAY_MS)
    }

    /**
     * Make reality match the user's intent: if routing is wanted and the earbuds
     * are present but the route was lost, put it back.
     */
    private fun ensureRouting() {
        if (!routingWanted) return

        // A real phone call owns the audio — don't interfere. The mode listener
        // brings us back here when the call ends and the mode returns to normal.
        when (audioManager.mode) {
            AudioManager.MODE_IN_CALL,
            AudioManager.MODE_RINGTONE,
            AudioManager.MODE_CALL_SCREENING -> return
        }

        val btDevice = findFirstBluetoothCommunicationDevice()
        if (btDevice == null) {
            // Earbuds gone: release the phone's call mode, keep the intent,
            // and route again automatically when they come back.
            // Only undo what we set — another app may be in a call of its own.
            if (routeApplied) {
                releaseRoute()
            }
            _routingState.value = RoutingState.Failed(WAITING_FOR_DEVICE)
            return
        }

        val current = audioManager.communicationDevice
        val routeIntact = isBluetooth(current) &&
            audioManager.mode == AudioManager.MODE_IN_COMMUNICATION
        if (routeIntact) {
            if (_routingState.value !is RoutingState.Active) {
                currentRoutedDevice = current
                _routingState.value = RoutingState.Active(nameOf(current!!))
            }
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (now < reapplyPausedUntil) return
        while (reapplyTimes.isNotEmpty() && now - reapplyTimes.first() > REAPPLY_WINDOW_MS) {
            reapplyTimes.removeFirst()
        }
        if (reapplyTimes.size >= MAX_REAPPLIES_PER_WINDOW) {
            reapplyPausedUntil = now + REAPPLY_WINDOW_MS
            reapplyTimes.clear()
            Logger.w("System keeps resetting the route; pausing re-apply for a minute")
            _routingState.value = RoutingState.Failed("System keeps resetting the route — retrying in a minute")
            mainHandler.removeCallbacks(ensureRunnable)
            mainHandler.postDelayed(ensureRunnable, REAPPLY_WINDOW_MS)
            return
        }
        reapplyTimes.addLast(now)
        Logger.w("Route was lost — re-applying")
        applyRoute(btDevice)
    }

    /**
     * Perform the actual routing call, without touching the user's intent.
     */
    private fun applyRoute(device: AudioDeviceInfo): RoutingState {
        val deviceName = nameOf(device)
        Logger.i("Attempting to route to: $deviceName (type=${deviceTypeToString(device.type)})")

        _routingState.value = RoutingState.Routing(deviceName)

        return try {
            // Set audio mode to communication — this is critical.
            // It tells the system we want two-way audio, not just media playback.
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

            // This is the magic call — it forces the system to select this device
            // for communication audio, which triggers the SCO/HFP profile switch.
            val success = audioManager.setCommunicationDevice(device)

            if (success) {
                currentRoutedDevice = device
                routeApplied = true
                val state = RoutingState.Active(deviceName)
                _routingState.value = state
                Logger.i("✓ Routing active: $deviceName")
                state
            } else {
                val state = RoutingState.Failed("setCommunicationDevice returned false")
                _routingState.value = state
                Logger.e("✗ setCommunicationDevice failed for $deviceName")
                audioManager.mode = AudioManager.MODE_NORMAL
                state
            }
        } catch (e: Exception) {
            val state = RoutingState.Failed(e.message ?: "Unknown error")
            _routingState.value = state
            Logger.e("✗ Exception during routing", e)
            try {
                audioManager.mode = AudioManager.MODE_NORMAL
            } catch (resetError: Exception) {
                Logger.e("Could not reset audio mode", resetError)
            }
            state
        }
    }

    /** Undo the route at the system level (does not change the user's intent). */
    private fun releaseRoute() {
        try {
            audioManager.clearCommunicationDevice()
            audioManager.mode = AudioManager.MODE_NORMAL
            currentRoutedDevice = null
            routeApplied = false
            Logger.i("Route released, back to system defaults")
        } catch (e: Exception) {
            Logger.e("Error releasing route", e)
        }
    }

    private fun isBluetooth(device: AudioDeviceInfo?): Boolean =
        device != null && (
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                device.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            )

    private fun nameOf(device: AudioDeviceInfo): String =
        device.productName?.toString() ?: "Bluetooth Device"

    /**
     * Refresh the list of available Bluetooth audio devices.
     */
    private fun refreshAvailableDevices() {
        val commDevices = audioManager.availableCommunicationDevices
        val btDevices = commDevices
            .filter { device ->
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            }
            .map { device ->
                BluetoothAudioDevice(
                    deviceInfo = device,
                    name = device.productName?.toString() ?: "Unknown BT Device",
                    type = device.type,
                    typeLabel = deviceTypeToString(device.type),
                )
            }

        _availableDevices.value = btDevices
        Logger.d("Available BT devices: ${btDevices.map { "${it.name} (${it.typeLabel})" }}")
    }

    companion object {
        private const val ENSURE_DELAY_MS = 1_000L
        private const val REAPPLY_WINDOW_MS = 60_000L
        private const val MAX_REAPPLIES_PER_WINDOW = 6
        const val WAITING_FOR_DEVICE = "Waiting for Bluetooth earbuds to connect"

        @Volatile
        private var instance: AudioRoutingManager? = null

        /** The single process-wide instance. */
        fun getInstance(context: Context): AudioRoutingManager =
            instance ?: synchronized(this) {
                instance ?: AudioRoutingManager(context.applicationContext).also { instance = it }
            }

        /**
         * Convert AudioDeviceInfo type constant to a human-readable string.
         */
        fun deviceTypeToString(type: Int): String = when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT SCO"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BT A2DP"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE Headset"
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE Speaker"
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in Mic"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Built-in Speaker"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Device"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset"
            else -> "Type $type"
        }
    }
}
