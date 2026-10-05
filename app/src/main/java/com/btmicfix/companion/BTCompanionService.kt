package com.btmicfix.companion

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import com.btmicfix.audio.AudioRoutingManager
import com.btmicfix.util.Logger

/**
 * Background service that is automatically bound by the system when the
 * associated Bluetooth device connects or disconnects.
 *
 * This is the core of the "zero-touch" experience: the user pairs their
 * earbuds once, and from then on, the audio routing fix is applied
 * automatically whenever the earbuds connect.
 *
 * Routing state lives in the shared [AudioRoutingManager]; keeping the process
 * alive (with its notification) is the job of RoutingKeepAliveService, which the
 * manager starts as soon as routing is wanted.
 */
class BTCompanionService : CompanionDeviceService() {

    private lateinit var audioRoutingManager: AudioRoutingManager

    override fun onCreate() {
        super.onCreate()
        Logger.i("BTCompanionService created")
        audioRoutingManager = AudioRoutingManager.getInstance(applicationContext)
    }

    /**
     * Called when the associated device appears (connects).
     * This is our trigger to apply the audio routing fix.
     */
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        super.onDeviceAppeared(associationInfo)
        Logger.i("🎧 Device appeared: association=${associationInfo.id}")

        audioRoutingManager.startMonitoring()
        when (val result = audioRoutingManager.routeToFirstAvailableBluetooth()) {
            is AudioRoutingManager.RoutingState.Active ->
                Logger.i("✓ Background routing activated: ${result.deviceName}")
            is AudioRoutingManager.RoutingState.Failed ->
                Logger.w("Background routing not active yet: ${result.reason}")
            else ->
                Logger.w("Unexpected routing state: $result")
        }
    }

    /**
     * Called when the associated device disappears (disconnects).
     * The shared manager notices the earbuds are gone, releases the route and
     * re-applies it when they come back — the user's choice is kept.
     */
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        super.onDeviceDisappeared(associationInfo)
        Logger.i("🎧 Device disappeared: association=${associationInfo.id}")
        audioRoutingManager.onBluetoothConnectionChanged()
    }

    override fun onDestroy() {
        super.onDestroy()
        // BUGFIX: this used to clear routing whenever the system unbound the
        // service, even while the earbuds were still in use.
        Logger.i("BTCompanionService destroyed")
    }
}
