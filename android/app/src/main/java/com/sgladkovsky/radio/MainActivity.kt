package com.sgladkovsky.radio

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.sgladkovsky.radio.databinding.ActivityMainBinding
import com.sgladkovsky.radio.model.RadioBand
import com.sgladkovsky.radio.model.RadioState
import com.sgladkovsky.radio.model.RadioStation
import com.sgladkovsky.radio.protocol.RadioCommand
import com.sgladkovsky.radio.protocol.RadioProtocol
import com.sgladkovsky.radio.service.RadioService
import com.sgladkovsky.radio.ui.StationAdapter
import com.sgladkovsky.radio.usb.UsbPermissionHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var usbPermissionHelper: UsbPermissionHelper
    private lateinit var stationAdapter: StationAdapter

    private var radioService: RadioService? = null
    private var serviceBound = false
    private var stateObserverJob: Job? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            radioService = (service as RadioService.RadioBinder).getService()
            serviceBound = true
            startObservingServiceState()
            connectUsbIfPossible()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            stopObservingServiceState()
            radioService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        usbPermissionHelper = UsbPermissionHelper(this) { device ->
            if (serviceBound) {
                radioService?.connectDevice(device)
            }
        }

        stationAdapter = StationAdapter { station ->
            radioService?.playStation(station)
        }
        binding.stationsList.layoutManager = LinearLayoutManager(this)
        binding.stationsList.adapter = stationAdapter

        setupControls()
        startAndBindService()
        handleUsbIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        if (serviceBound && stateObserverJob?.isActive != true) {
            startObservingServiceState()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUsbIntent(intent)
    }

    override fun onDestroy() {
        stopObservingServiceState()
        usbPermissionHelper.unregister()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        super.onDestroy()
    }

    private fun startAndBindService() {
        ContextCompat.startForegroundService(this, Intent(this, RadioService::class.java))
        bindService(
            Intent(this, RadioService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    private fun startObservingServiceState() {
        val service = radioService ?: return
        stopObservingServiceState()
        stateObserverJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                service.state.collect { state ->
                    renderState(state)
                }
            }
        }
    }

    private fun stopObservingServiceState() {
        stateObserverJob?.cancel()
        stateObserverJob = null
    }

    private fun setupControls() {
        binding.btnAm.setOnClickListener { switchBand(RadioBand.AM) }
        binding.btnFm.setOnClickListener { switchBand(RadioBand.FM) }
        binding.btnDab.setOnClickListener { switchBand(RadioBand.DAB) }

        binding.btnTuneDown.setOnClickListener { radioService?.sendCommand(RadioCommand.TUNE_DOWN) }
        binding.btnTuneUp.setOnClickListener { radioService?.sendCommand(RadioCommand.TUNE_UP) }
        binding.btnSeekDown.setOnClickListener { radioService?.sendCommand(RadioCommand.SEEK_DOWN) }
        binding.btnSeekUp.setOnClickListener { radioService?.sendCommand(RadioCommand.SEEK_UP) }

        binding.btnScan.setOnClickListener {
            val service = radioService ?: return@setOnClickListener
            if (service.state.value.scanning) {
                service.stopScan()
            } else {
                service.startScan(longScan = true)
            }
        }

        binding.btnPlayPause.setOnClickListener {
            val service = radioService ?: return@setOnClickListener
            if (service.state.value.playing) {
                service.pauseAudioPlayback()
            } else {
                service.startAudioPlayback()
            }
        }

        binding.btnRefresh.setOnClickListener {
            radioService?.requestStatus()
            radioService?.sendCommand(RadioCommand.REQUEST_STATION_LIST)
        }
    }

    private fun renderState(state: RadioState) {
        binding.statusText.text = state.statusMessage
        binding.bandText.text = when (state.band) {
            RadioBand.AM -> getString(R.string.band_am)
            RadioBand.FM -> getString(R.string.band_fm)
            RadioBand.DAB -> getString(R.string.band_dab)
        }
        binding.frequencyText.text = if (state.frequency > 0) {
            RadioProtocol.formatFrequency(state.frequency, state.band)
        } else {
            "---"
        }
        binding.stationNameText.text = state.stationName.ifEmpty { "—" }
        binding.rdsText.text = state.rdsText
        binding.btnScan.text = if (state.scanning) "Стоп" else "Скан"
        binding.btnPlayPause.text = if (state.playing) "⏸ Pause" else "▶ Play"

        val enabled = state.connected
        listOf(
            binding.btnAm, binding.btnFm, binding.btnDab,
            binding.btnTuneDown, binding.btnTuneUp,
            binding.btnSeekDown, binding.btnSeekUp,
            binding.btnScan, binding.btnPlayPause, binding.btnRefresh
        ).forEach { it.isEnabled = enabled }

        stationAdapter.submitList(state.stations.toList())
        binding.stationsLabel.visibility = if (state.stations.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun switchBand(band: RadioBand) {
        radioService?.sendCommand(RadioCommand.BAND)
        when (band) {
            RadioBand.FM -> radioService?.setFmArea(1)
            RadioBand.AM -> radioService?.setFmArea(0)
            RadioBand.DAB -> radioService?.sendCommand(RadioCommand.REQUEST_STATION_LIST)
        }
        radioService?.requestStatus()
    }

    private fun connectUsbIfPossible() {
        usbPermissionHelper.findSupportedDevice()?.let { device ->
            if (usbPermissionHelper.hasPermission(device)) {
                radioService?.connectDevice(device)
            }
        }
    }

    private fun handleUsbIntent(intent: Intent?) {
        val device = readUsbDevice(intent)
        if (device != null && RadioProtocol.isSupportedDevice(device.vendorId, device.productId)) {
            usbPermissionHelper.requestPermission(device)
            return
        }

        usbPermissionHelper.findSupportedDevice()?.let { supported ->
            if (!usbPermissionHelper.hasPermission(supported)) {
                usbPermissionHelper.requestPermission(supported)
            } else if (serviceBound) {
                radioService?.connectDevice(supported)
            }
        } ?: run {
            Toast.makeText(this, R.string.status_disconnected, Toast.LENGTH_SHORT).show()
        }
    }

    private fun readUsbDevice(intent: Intent?): UsbDevice? {
        intent ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }
}
