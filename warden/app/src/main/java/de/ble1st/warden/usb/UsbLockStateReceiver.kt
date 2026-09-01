package de.ble1st.warden.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log

/**
 * Dynamic receiver for lock/unlock edges so [UsbAutoLockController] does not wait for the
 * 15-minute [UsbAutoLockWorker] poll. Implicit broadcasts like [Intent.ACTION_SCREEN_OFF]
 * cannot be registered in the manifest; this receiver is registered/unregistered from
 * [syncRegistration] whenever the USB-auto-lock preference changes.
 *
 * WorkManager remains scheduled as a backup when the process is not alive at the lock edge.
 */
class UsbLockStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val lockedOverride = when (intent?.action) {
            Intent.ACTION_SCREEN_OFF -> true
            Intent.ACTION_USER_PRESENT -> false
            Intent.ACTION_SCREEN_ON -> null
            else -> return
        }
        UsbAutoLockController(context.applicationContext).checkAndSync(lockedOverride)
    }

    companion object {
        private const val TAG = "UsbLockStateReceiver"

        @Volatile
        private var registered: UsbLockStateReceiver? = null

        fun syncRegistration(context: Context) {
            val app = context.applicationContext
            val want = UsbAutoLockStorage.isEnabled(app)
            synchronized(this) {
                val current = registered
                if (want && current == null) {
                    val receiver = UsbLockStateReceiver()
                    val filter = IntentFilter().apply {
                        addAction(Intent.ACTION_SCREEN_OFF)
                        addAction(Intent.ACTION_SCREEN_ON)
                        addAction(Intent.ACTION_USER_PRESENT)
                    }
                    app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                    registered = receiver
                } else if (!want && current != null) {
                    try {
                        app.unregisterReceiver(current)
                    } catch (e: IllegalArgumentException) {
                        Log.w(TAG, "USB-Lock-Receiver war nicht registriert", e)
                    }
                    registered = null
                }
            }
        }
    }
}
