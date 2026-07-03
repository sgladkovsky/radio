package com.sgladkovsky.radio.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import au.id.jms.usbaudio.USBAudio
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.util.SerialInputOutputManager
import com.hyinfo.util.USBMonitor
import com.sgladkovsky.radio.MainActivity
import com.sgladkovsky.radio.R
import com.sgladkovsky.radio.model.RadioBand
import com.sgladkovsky.radio.model.RadioState
import com.sgladkovsky.radio.model.RadioStation
import com.sgladkovsky.radio.protocol.RadioCommand
import com.sgladkovsky.radio.protocol.RadioProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.Executors

class RadioService : Service(), SerialInputOutputManager.Listener {

    private val binder = RadioBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val executor = Executors.newSingleThreadExecutor()

    private var usbSerialPort: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private var usbAudio: USBAudio? = null
    private var usbControlBlock: USBMonitor.UsbControlBlock? = null
    private var packetNumber = 0
    private val receiveBuffer = ByteArray(8192)
    private var receiveBufferSize = 0

    private val _state = MutableStateFlow(RadioState())
    val state: StateFlow<RadioState> = _state.asStateFlow()

    inner class RadioBinder : Binder() {
        fun getService(): RadioService = this@RadioService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.status_disconnected)))
        try {
            System.loadLibrary("usb100")
            usbAudio = USBAudio()
        } catch (error: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load USB audio native libraries", error)
            usbAudio = null
            _state.update {
                it.copy(statusMessage = "Ошибка загрузки аудио-библиотек")
            }
        } catch (error: Exception) {
            Log.e(TAG, "Failed to initialize USB audio", error)
            usbAudio = null
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> connect(intent)
            ACTION_DISCONNECT -> disconnect()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        disconnect()
        usbAudio?.closeAudio()
        usbAudio = null
        executor.shutdownNow()
        super.onDestroy()
    }

    fun connectDevice(device: UsbDevice) {
        val intent = Intent(this, RadioService::class.java).apply {
            action = ACTION_CONNECT
            putExtra(EXTRA_DEVICE, device)
        }
        startForegroundServiceCompat(intent)
    }

    fun disconnect() {
        ioManager?.stop()
        ioManager = null

        runCatching { usbSerialPort?.close() }
        usbSerialPort = null

        usbAudio?.stopCapture()
        usbAudio?.closeAudio()

        receiveBufferSize = 0
        usbControlBlock = null

        _state.update {
            it.copy(
                connected = false,
                playing = false,
                scanning = false,
                statusMessage = getString(R.string.status_disconnected)
            )
        }
        updateNotification(getString(R.string.status_disconnected))
    }

    fun sendCommand(command: RadioCommand) {
        if (!_state.value.connected) return
        val packet = RadioProtocol.buildCommand(command, nextPacketNumber())
        writePacket(packet)
    }

    fun playStation(station: RadioStation) {
        if (!_state.value.connected) return
        val packet = RadioProtocol.buildPlayStation(station, nextPacketNumber())
        writePacket(packet)
        startAudioPlayback()
    }

    fun setFmArea(areaIndex: Int) {
        if (!_state.value.connected) return
        val packet = RadioProtocol.buildAreaSelect(areaIndex, nextPacketNumber())
        writePacket(packet)
    }

    fun startAudioPlayback() {
        if (usbAudio?.isInitUSBAudio == true) {
            usbAudio?.play()
            usbAudio?.startCapture()
            _state.update { it.copy(playing = true) }
        }
    }

    fun pauseAudioPlayback() {
        usbAudio?.pause()
        usbAudio?.stopCapture()
        _state.update { it.copy(playing = false) }
    }

    fun requestStatus() {
        sendCommand(RadioCommand.REQUEST_ALL_INFO)
    }

    fun startScan(longScan: Boolean = false) {
        _state.update { it.copy(scanning = true, statusMessage = getString(R.string.status_scanning)) }
        sendCommand(if (longScan) RadioCommand.AUTO_SCAN_LONG else RadioCommand.AUTO_SCAN)
        sendCommand(RadioCommand.REQUEST_STATION_LIST)
    }

    fun stopScan() {
        sendCommand(RadioCommand.CANCEL_SCAN)
        _state.update { it.copy(scanning = false) }
    }

    private fun connect(intent: Intent) {
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DEVICE)
        } ?: return

        if (!RadioProtocol.isSupportedDevice(device.vendorId, device.productId)) {
            _state.update { it.copy(statusMessage = "Неподдерживаемое USB-устройство") }
            return
        }

        _state.update { it.copy(statusMessage = getString(R.string.status_connecting)) }

        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        if (!usbManager.hasPermission(device)) {
            _state.update { it.copy(statusMessage = getString(R.string.permission_usb_required)) }
            return
        }

        serviceScope.launch(Dispatchers.IO) {
            runCatching {
                connectSerial(usbManager, device)
                connectAudio(usbManager, device)
                packetNumber = 0
                receiveBufferSize = 0

                _state.update {
                    it.copy(
                        connected = true,
                        statusMessage = getString(R.string.status_connected)
                    )
                }
                updateNotification(getString(R.string.status_connected))
                requestStatus()
            }.onFailure { error ->
                Log.e(TAG, "Connection failed", error)
                disconnect()
                _state.update {
                    it.copy(statusMessage = "Ошибка подключения: ${error.message}")
                }
            }
        }
    }

    private fun connectSerial(usbManager: UsbManager, device: UsbDevice) {
        val driver = CdcAcmSerialDriver(device)
        val port = driver.ports.firstOrNull()
            ?: throw IOException("CDC ACM port not found")

        val connection = usbManager.openDevice(device)
            ?: throw IOException("Cannot open USB device")

        port.open(connection)
        port.setParameters(RadioProtocol.BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

        usbSerialPort = port
        ioManager = SerialInputOutputManager(port, this).also { manager ->
            executor.submit(manager)
        }
    }

    private fun connectAudio(usbManager: UsbManager, device: UsbDevice) {
        val controlBlock = USBMonitor.UsbControlBlock(usbManager, device)
        usbControlBlock = controlBlock
        usbAudio?.initAudio(controlBlock)
    }

    private fun writePacket(packet: ByteArray) {
        val port = usbSerialPort ?: return
        try {
            port.write(packet, WRITE_TIMEOUT_MS)
            Log.d(TAG, "TX: ${packet.joinToString(" ") { "%02X".format(it) }}")
        } catch (error: Exception) {
            Log.e(TAG, "Write failed", error)
            serviceScope.launch { disconnect() }
        }
    }

    private fun nextPacketNumber(): Int {
        packetNumber = (packetNumber + 1) and 0xFF
        return packetNumber
    }

    override fun onNewData(data: ByteArray) {
        if (receiveBufferSize + data.size > receiveBuffer.size) {
            receiveBufferSize = 0
        }
        System.arraycopy(data, 0, receiveBuffer, receiveBufferSize, data.size)
        receiveBufferSize += data.size

        val (packets, consumed) = RadioProtocol.extractPackets(receiveBuffer, receiveBufferSize)
        if (consumed > 0 && consumed < receiveBufferSize) {
            System.arraycopy(receiveBuffer, consumed, receiveBuffer, 0, receiveBufferSize - consumed)
            receiveBufferSize -= consumed
        } else if (consumed == receiveBufferSize) {
            receiveBufferSize = 0
        }

        packets.forEach { handlePacket(it) }
    }

    override fun onRunError(e: Exception) {
        Log.e(TAG, "Serial IO error", e)
        serviceScope.launch { disconnect() }
    }

    private fun handlePacket(packet: ByteArray) {
        if (!RadioProtocol.verifyChecksum(packet)) {
            Log.w(TAG, "Checksum error: ${packet.joinToString(" ") { "%02X".format(it) }}")
        }

        Log.d(TAG, "RX: ${packet.joinToString(" ") { "%02X".format(it) }}")

        when (packet.getOrNull(5)?.toInt()?.and(0xFF)) {
            0x22 -> RadioProtocol.parsePlayInfo(packet)?.let { info ->
                _state.update {
                    it.copy(
                        band = info.band,
                        frequency = info.frequency,
                        stationName = info.serviceName.ifEmpty { info.ensemble },
                        rdsText = info.rdsText,
                        scanning = false,
                        statusMessage = getString(R.string.status_connected)
                    )
                }
            }

            0x21 -> {
                val stations = mutableListOf<RadioStation>()
                var offset = 7
                while (offset + 23 <= packet.size) {
                    RadioProtocol.parseStationListItem(packet, offset)?.let { stations.add(it) }
                    offset += 23
                }
                if (stations.isNotEmpty()) {
                    _state.update {
                        it.copy(
                            stations = stations.distinctBy { station -> station.cid },
                            scanning = false
                        )
                    }
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_radio)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun startForegroundServiceCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    companion object {
        private const val TAG = "RadioService"
        private const val CHANNEL_ID = "usb_radio"
        private const val NOTIFICATION_ID = 1
        private const val WRITE_TIMEOUT_MS = 2000

        const val ACTION_CONNECT = "com.sgladkovsky.radio.CONNECT"
        const val ACTION_DISCONNECT = "com.sgladkovsky.radio.DISCONNECT"
        const val EXTRA_DEVICE = "device"
    }
}
