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
import com.sgladkovsky.radio.protocol.RadioCommand
import com.sgladkovsky.radio.protocol.RadioProtocol
import com.sgladkovsky.radio.service.RadioService
import com.sgladkovsky.radio.ui.StationAdapter
import com.sgladkovsky.radio.usb.UsbPermissionHelper
import com.sgladkovsky.radio.util.RadioLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private companion object {
        const val LOG_TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var usbPermissionHelper: UsbPermissionHelper
    private lateinit var stationAdapter: StationAdapter

    private var radioService: RadioService? = null
    private var serviceBound = false
    private var stateObserverJob: Job? = null
    private var stateEmissionCount = 0

    private val stateObserverExceptionHandler = CoroutineExceptionHandler { _, error ->
        RadioLog.e(LOG_TAG, "observeServiceState coroutine failed: ${error.message}", error)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            RadioLog.i(LOG_TAG, "onServiceConnected: name=$name binder=$service thread=${Thread.currentThread().name}")
            try {
                val binder = service as? RadioService.RadioBinder
                if (binder == null) {
                    RadioLog.e(LOG_TAG, "onServiceConnected: unexpected binder type=${service?.javaClass?.name}")
                    return
                }
                radioService = binder.getService()
                serviceBound = true
                RadioLog.i(LOG_TAG, "onServiceConnected: radioService=$radioService")
                observeServiceState()
                connectUsbIfPossible()
            } catch (error: Exception) {
                RadioLog.e(LOG_TAG, "onServiceConnected failed", error)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            RadioLog.w(LOG_TAG, "onServiceDisconnected: name=$name")
            stopObservingServiceState()
            radioService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RadioLog.i(LOG_TAG, "onCreate: savedInstanceState=${savedInstanceState != null}")
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        usbPermissionHelper = UsbPermissionHelper(this) { device ->
            RadioLog.d(LOG_TAG, "USB permission granted: vid=${device.vendorId} pid=${device.productId}")
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
        RadioLog.d(LOG_TAG, "onCreate complete: lifecycle=${lifecycle.currentState}")
    }

    override fun onStart() {
        super.onStart()
        RadioLog.d(
            LOG_TAG,
            "onStart: serviceBound=$serviceBound jobActive=${stateObserverJob?.isActive} lifecycle=${lifecycle.currentState}"
        )
        if (serviceBound && stateObserverJob?.isActive != true) {
            observeServiceState()
        }
    }

    override fun onStop() {
        RadioLog.d(LOG_TAG, "onStop")
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUsbIntent(intent)
    }

    override fun onDestroy() {
        RadioLog.d(LOG_TAG, "onDestroy")
        stopObservingServiceState()
        usbPermissionHelper.unregister()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        super.onDestroy()
    }

    private fun startAndBindService() {
        RadioLog.d(LOG_TAG, "startAndBindService")
        try {
            ContextCompat.startForegroundService(this, Intent(this, RadioService::class.java))
            val bound = bindService(
                Intent(this, RadioService::class.java),
                serviceConnection,
                Context.BIND_AUTO_CREATE
            )
            RadioLog.d(LOG_TAG, "bindService returned bound=$bound")
        } catch (error: Exception) {
            RadioLog.e(LOG_TAG, "startAndBindService failed", error)
        }
    }

    private fun observeServiceState() {
        val service = radioService
        RadioLog.i(
            LOG_TAG,
            "observeServiceState enter: service=$service serviceBound=$serviceBound " +
                "lifecycle=${lifecycle.currentState} thread=${Thread.currentThread().name} " +
                "prevJob=${stateObserverJob?.isActive}"
        )

        if (service == null) {
            RadioLog.w(LOG_TAG, "observeServiceState: radioService is null, skip")
            return
        }

        stopObservingServiceState()
        stateEmissionCount = 0

        stateObserverJob = lifecycleScope.launch(stateObserverExceptionHandler) {
            RadioLog.d(LOG_TAG, "observeServiceState: coroutine started job=${coroutineContext[Job]}")
            try {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    RadioLog.i(
                        LOG_TAG,
                        "observeServiceState: repeatOnLifecycle STARTED block entered, " +
                            "lifecycle=${lifecycle.currentState} service=$service"
                    )
                    try {
                        service.state.collect { state ->
                            stateEmissionCount++
                            RadioLog.d(
                                LOG_TAG,
                                "observeServiceState: emission #$stateEmissionCount " +
                                    "connected=${state.connected} band=${state.band} " +
                                    "freq=${state.frequency} stations=${state.stations.size} " +
                                    "msg='${state.statusMessage}' thread=${Thread.currentThread().name}"
                            )
                            renderState(state)
                        }
                    } catch (error: CancellationException) {
                        RadioLog.d(LOG_TAG, "observeServiceState: collect cancelled")
                        throw error
                    } catch (error: Exception) {
                        RadioLog.e(LOG_TAG, "observeServiceState: collect/render failed", error)
                        throw error
                    } finally {
                        RadioLog.d(LOG_TAG, "observeServiceState: collect finished, emissions=$stateEmissionCount")
                    }
                }
            } catch (error: CancellationException) {
                RadioLog.d(LOG_TAG, "observeServiceState: repeatOnLifecycle cancelled")
                throw error
            } catch (error: Exception) {
                RadioLog.e(LOG_TAG, "observeServiceState: repeatOnLifecycle failed", error)
                throw error
            } finally {
                RadioLog.d(LOG_TAG, "observeServiceState: coroutine finished")
            }
        }

        RadioLog.d(LOG_TAG, "observeServiceState: launched job=${stateObserverJob?.isActive}")
    }

    private fun stopObservingServiceState() {
        if (stateObserverJob != null) {
            RadioLog.d(
                LOG_TAG,
                "stopObservingServiceState: cancelling job active=${stateObserverJob?.isActive} " +
                    "emissions=$stateEmissionCount"
            )
        }
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
        try {
            RadioLog.d(LOG_TAG, "renderState begin: $state")
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
            RadioLog.d(LOG_TAG, "renderState complete")
        } catch (error: Exception) {
            RadioLog.e(LOG_TAG, "renderState failed for state=$state", error)
            throw error
        }
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
            RadioLog.d(LOG_TAG, "connectUsbIfPossible: found vid=${device.vendorId} pid=${device.productId}")
            if (usbPermissionHelper.hasPermission(device)) {
                radioService?.connectDevice(device)
            } else {
                RadioLog.d(LOG_TAG, "connectUsbIfPossible: no USB permission yet")
            }
        } ?: RadioLog.d(LOG_TAG, "connectUsbIfPossible: no supported device")
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
