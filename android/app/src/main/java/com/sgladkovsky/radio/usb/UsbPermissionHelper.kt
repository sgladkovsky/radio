package com.sgladkovsky.radio.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import com.sgladkovsky.radio.protocol.RadioProtocol

class UsbPermissionHelper(
    private val activity: ComponentActivity,
    private val onPermissionGranted: (UsbDevice) -> Unit
) {
    private val usbManager = activity.getSystemService(Context.USB_SERVICE) as UsbManager
    private var registered = false

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_USB_PERMISSION) return
            val device = readDeviceExtra(intent) ?: return
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                onPermissionGranted(device)
            }
        }
    }

    init {
        register()
    }

    private fun register() {
        if (registered) return
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        ContextCompat.registerReceiver(
            activity,
            permissionReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        registered = true
    }

    fun unregister() {
        if (!registered) return
        runCatching { activity.unregisterReceiver(permissionReceiver) }
        registered = false
    }

    fun findSupportedDevice(): UsbDevice? {
        return usbManager.deviceList.values.firstOrNull { device ->
            RadioProtocol.isSupportedDevice(device.vendorId, device.productId)
        }
    }

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    fun requestPermission(device: UsbDevice) {
        if (hasPermission(device)) {
            onPermissionGranted(device)
            return
        }

        val pendingIntent = PendingIntent.getBroadcast(
            activity,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(activity.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        usbManager.requestPermission(device, pendingIntent)
    }

    private fun readDeviceExtra(intent: Intent): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.sgladkovsky.radio.USB_PERMISSION"
    }
}
