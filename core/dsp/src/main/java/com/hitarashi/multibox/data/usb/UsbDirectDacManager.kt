package com.hitarashi.multibox.data.usb

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import com.hitarashi.multibox.core.model.di.ApplicationScope
import com.hitarashi.multibox.core.model.dsp.DspLogger
import com.hitarashi.multibox.core.model.dsp.UsbDacPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow

data class UsbStreamingAltSetting(
    val iface: UsbInterface,
    val endpoint: UsbEndpoint,
    val altSetting: Int,
    val subframeSize: Int, // 2 = 16-bit, 3 = 24-bit packed, 4 = 32-bit slot
    val bitResolution: Int, // 16, 24, 32
    val maxPacketSize: Int,
    val interval: Int,
    val isPcm: Boolean = true,
    val supportedRates: Set<Int> = emptySet()
)

sealed interface UsbDacState {
    data object Idle : UsbDacState
    data object PermissionRequested : UsbDacState
    data class Connected(
        val deviceName: String,
        val sampleRate: Int,
        val bitDepth: Int,
        val channels: Int
    ) : UsbDacState
    data class Error(val message: String) : UsbDacState
}

@Singleton
class UsbDirectDacManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: UsbDacPreferences,
    @ApplicationScope private val scope: CoroutineScope,
    private val logBuffer: DspLogger
) {
    private val usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _dacState = MutableStateFlow<UsbDacState>(UsbDacState.Idle)
    val dacState: StateFlow<UsbDacState> = _dacState.asStateFlow()

    private val _dacVolumePercent = MutableStateFlow(100)
    val dacVolumePercent: StateFlow<Int> = _dacVolumePercent.asStateFlow()

    private val _isHardwareUacMode = MutableStateFlow(true)
    val isHardwareUacMode: StateFlow<Boolean> = _isHardwareUacMode.asStateFlow()

    private val _isHardwareVolumeControlActive = MutableStateFlow(true)
    val isHardwareVolumeControlActive: StateFlow<Boolean> = _isHardwareVolumeControlActive.asStateFlow()

    private val dacLock = Any()
    private var activeDevice: UsbDevice? = null
    private var activeConnection: UsbDeviceConnection? = null
    private var activeAudioInterface: UsbInterface? = null
    private var activeEndpoint: UsbEndpoint? = null
    private val claimedAudioInterfaces = mutableListOf<UsbInterface>()
    private val availableAltSettings = mutableListOf<UsbStreamingAltSetting>()
    private var _activeSubframeSize: Int = 2
    val activeSubframeSize: Int
        get() = _activeSubframeSize

    private val _supportedSampleRates = mutableSetOf<Int>()
    val supportedSampleRates: Set<Int>
        get() = _supportedSampleRates

    private var _activeTargetSampleRate: Int = 48000
    val activeTargetSampleRate: Int
        get() = _activeTargetSampleRate

    private var isUac2Device: Boolean = false
    private var lastHardwareSampleRate: Int = -1
    private var lastHardwareSubframeSize: Int = -1

    private var pendingPermissionDeviceName: String? = null
    private var lastPermissionRequestTimeMs: Long = 0L

    // Dual-stream slot allocation for seamless playerA / playerB crossfade
    private val slotLock = Any()
    private val slotOwners = arrayOfNulls<Any>(2)
    private val streamPlaying = BooleanArray(2) { false }

    fun acquireStreamSlot(owner: Any): Int = synchronized(slotLock) {
        if (slotOwners[0] === owner) return 0
        if (slotOwners[1] === owner) return 1
        if (slotOwners[0] == null || !streamPlaying[0]) {
            slotOwners[0] = owner
            return 0
        }
        if (slotOwners[1] == null || !streamPlaying[1]) {
            slotOwners[1] = owner
            return 1
        }
        slotOwners[0] = owner
        return 0
    }

    fun releaseStreamSlot(owner: Any) = synchronized(slotLock) {
        for (i in 0..1) {
            if (slotOwners[i] === owner) {
                streamPlaying[i] = false
                setNativeStreamPaused(i, true)
                safeClearNativeRingBuffer(i)
                slotOwners[i] = null
            }
        }
    }

    fun isOtherStreamActive(streamId: Int): Boolean = synchronized(slotLock) {
        val other = if (streamId == 1) 0 else 1
        return streamPlaying[other] && getNativeRingBufferAvailable(other) > 0
    }

    fun resolveTargetSampleRate(requestedRate: Int, allowedRates: Set<Int> = _supportedSampleRates): Int {
        val rates = if (allowedRates.isNotEmpty()) allowedRates else _supportedSampleRates
        val isFamily44 = (requestedRate > 0 && requestedRate % 11025 == 0)
        if (!isUac2Device) {
            val preferred = if (isFamily44) 44100 else 48000
            return if (rates.isEmpty() || rates.contains(preferred)) {
                preferred
            } else if (rates.contains(48000)) {
                48000
            } else if (rates.contains(44100)) {
                44100
            } else {
                rates.firstOrNull() ?: preferred
            }
        }

        if (rates.isNotEmpty()) {
            if (rates.contains(requestedRate)) {
                return requestedRate
            }
            if (requestedRate % 2 == 0 && rates.contains(requestedRate / 2)) {
                return requestedRate / 2
            }
            if (requestedRate % 4 == 0 && rates.contains(requestedRate / 4)) {
                return requestedRate / 4
            }
            if (requestedRate % 8 == 0 && rates.contains(requestedRate / 8)) {
                return requestedRate / 8
            }
            if (isFamily44) {
                val family44 = rates.filter { it % 44100 == 0 }
                val best = family44.filter { it >= requestedRate }.minOrNull() ?: family44.maxOrNull()
                if (best != null) return best
            } else {
                val family48 = rates.filter { it % 48000 == 0 }
                val best = family48.filter { it >= requestedRate }.minOrNull() ?: family48.maxOrNull()
                if (best != null) return best
            }
            val lowerOrEqual = rates.filter { it <= requestedRate }.maxOrNull()
            if (lowerOrEqual != null) return lowerOrEqual
            return rates.maxOrNull() ?: 48000
        }

        val ep = activeEndpoint
        val maxPkt = ep?.maxPacketSize ?: 1024
        if (maxPkt < 768 && requestedRate > 48000) {
            return if (isFamily44) 44100 else 48000
        }

        return if (requestedRate < 32000) {
            if (isFamily44) 44100 else 48000
        } else {
            requestedRate
        }
    }

    private fun selectBestAltSetting(targetRate: Int): UsbStreamingAltSetting? {
        if (availableAltSettings.isEmpty()) return null
        val pcmAlts = availableAltSettings.filter { it.isPcm }.ifEmpty { availableAltSettings }
        val matchingRate = pcmAlts.filter {
            it.supportedRates.isEmpty() || it.supportedRates.contains(targetRate)
        }
        val candidates = matchingRate.ifEmpty { pcmAlts }
        return candidates.firstOrNull { it.bitResolution == 24 }
            ?: candidates.firstOrNull { it.subframeSize == 3 }
            ?: candidates.firstOrNull { it.bitResolution == 16 || it.subframeSize == 2 }
            ?: candidates.firstOrNull()
    }

    private var activeFeatureUnitId: Int = -1
    private var activeAcInterfaceId: Int = 0
    private var activeClockSourceId: Int = -1

    private var isReceiverRegistered = false
    private var isNativeLibLoaded = false
    private var onPausePlayerCallback: (() -> Unit)? = null

    fun setVolumeMode(isHardwareUac: Boolean) {
        _isHardwareUacMode.value = isHardwareUac
        setHardwareVolume(_dacVolumePercent.value)
    }

    val dacVolumeHudVisible = MutableStateFlow(false)
    private var volumeHudJob: kotlinx.coroutines.Job? = null

    fun adjustVolume(deltaPercent: Int): Int {
        val current = _dacVolumePercent.value
        val newVol = (current + deltaPercent).coerceIn(0, 100)
        setHardwareVolume(newVol)
        showVolumeHud()
        return newVol
    }

    fun showVolumeHud() {
        dacVolumeHudVisible.value = true
        volumeHudJob?.cancel()
        volumeHudJob = scope.launch {
            kotlinx.coroutines.delay(1800)
            dacVolumeHudVisible.value = false
        }
    }

    fun setHardwareVolume(volumePercent: Int) {
        val clamped = volumePercent.coerceIn(0, 100)
        _dacVolumePercent.value = clamped
        val scale = when {
            clamped == 0 -> 0.0f
            clamped == 100 -> 1.0f
            _isHardwareUacMode.value -> {
                val db = -48.0 * (1.0 - clamped / 100.0)
                10.0.pow(db / 20.0).toFloat().coerceIn(0.0f, 1.0f)
            }
            else -> (clamped / 100.0f).coerceIn(0.0f, 1.0f)
        }
        _isHardwareVolumeControlActive.value = _isHardwareUacMode.value
        setNativeVolumeScale(scale)
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.hitarashi.multibox.USB_PERMISSION"
        private const val USB_CLASS_AUDIO = UsbConstants.USB_CLASS_AUDIO
        private const val UAC_SET_CUR = 0x01
        private const val UAC_GET_CUR = 0x81
        private const val UAC2_CS_CUR = 0x01
        private const val UAC2_CS_RANGE = 0x02
        private const val SAMPLING_FREQ_CONTROL = 0x01
        private val STANDARD_AUDIOPHILE_RATES = intArrayOf(
            44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000, 705600, 768000
        )
    }

    private data class ParsedAltFormat(
        val subframeSize: Int,
        val bitResolution: Int,
        val isPcm: Boolean,
        val supportedRates: Set<Int>
    )

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.action?.let { action ->
                when (action) {
                    ACTION_USB_PERMISSION -> {
                        synchronized(dacLock) {
                            pendingPermissionDeviceName = null
                            val intentDevice: UsbDevice? = getUsbDeviceFromIntent(intent)
                            val device: UsbDevice? = intentDevice ?: usbManager.deviceList.values.find { isAudioDevice(it) }
                            val granted = intent.getBooleanExtra(
                                UsbManager.EXTRA_PERMISSION_GRANTED,
                                false
                            )
                            if (granted && device != null) {
                                claimAndInitializeDac(device)
                            } else if (device != null && usbManager.hasPermission(device)) {
                                claimAndInitializeDac(device)
                            } else {
                                Timber.w("USB permission denied for device: ${device?.deviceName}")
                                logBuffer.logDac("USB permission denied: ${device?.deviceName}")
                                _dacState.value = UsbDacState.Error("USB permission denied")
                            }
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        val intentDevice: UsbDevice? = getUsbDeviceFromIntent(intent)
                        val device: UsbDevice? = intentDevice ?: usbManager.deviceList.values.find { isAudioDevice(it) }
                        if (device != null && isAudioDevice(device)) {
                            checkAndConnectDac(device)
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        val device: UsbDevice? = getUsbDeviceFromIntent(intent)
                        val shouldDisconnect = synchronized(dacLock) {
                            device == null || activeDevice == null || device.deviceName == activeDevice?.deviceName
                        }
                        if (shouldDisconnect) {
                            handleDisconnect()
                        }
                    }
                }
            }
        }
    }

    init {
        try {
            System.loadLibrary("usb_dac_native")
            isNativeLibLoaded = true
        } catch (e: Throwable) {
            Timber.w(e, "Could not load usb_dac_native library")
            isNativeLibLoaded = false
        }

        scope.launch {
            preferences.exclusiveUsbAudioEnabledFlow.collect { enabled ->
                if (enabled) {
                    registerReceiverIfNeeded()
                    scanAndConnectAttachedDac()
                } else {
                    handleDisconnect()
                    unregisterReceiverIfNeeded()
                }
            }
        }
    }

    fun setOnPausePlayerCallback(callback: () -> Unit) {
        onPausePlayerCallback = callback
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerReceiverIfNeeded() {
        if (!isReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(ACTION_USB_PERMISSION)
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(usbReceiver, filter)
            }
            isReceiverRegistered = true
        }
    }

    private fun unregisterReceiverIfNeeded() {
        if (isReceiverRegistered) {
            runCatching { context.unregisterReceiver(usbReceiver) }
            isReceiverRegistered = false
        }
    }

    fun scanAndConnectAttachedDac() {
        val deviceList = usbManager.deviceList
        val audioDevice = deviceList.values.firstOrNull { isAudioDevice(it) }
        if (audioDevice != null) {
            checkAndConnectDac(audioDevice)
        }
    }

    private fun isAlreadyConnectedLocked(device: UsbDevice): Boolean {
        return activeConnection != null &&
            activeDevice?.deviceName == device.deviceName &&
            _dacState.value is UsbDacState.Connected
    }

    private fun checkAndConnectDac(device: UsbDevice) {
        synchronized(dacLock) {
            if (isAlreadyConnectedLocked(device)) {
                return
            }
            if (usbManager.hasPermission(device)) {
                pendingPermissionDeviceName = null
                claimAndInitializeDac(device)
            } else {
                requestUsbPermissionLocked(device)
            }
        }
    }

    private fun requestUsbPermissionLocked(device: UsbDevice) {
        val now = SystemClock.elapsedRealtime()
        if (pendingPermissionDeviceName == device.deviceName && (now - lastPermissionRequestTimeMs) < 15_000L) {
            return
        }
        pendingPermissionDeviceName = device.deviceName
        lastPermissionRequestTimeMs = now
        _dacState.value = UsbDacState.PermissionRequested
        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val intent = Intent(ACTION_USB_PERMISSION).apply {
            setPackage(context.packageName)
        }
        val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, flag)
        usbManager.requestPermission(device, pendingIntent)
    }

    private fun isAudioDevice(device: UsbDevice): Boolean {
        if (device.deviceClass == USB_CLASS_AUDIO) return true
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == USB_CLASS_AUDIO) return true
        }
        return false
    }

    private fun queryUac2ClockSampleRates(
        connection: UsbDeviceConnection,
        clockId: Int,
        acInterfaceId: Int
    ): Set<Int> {
        if (clockId < 0) return emptySet()
        val rates = mutableSetOf<Int>()
        try {
            val buf = ByteArray(256)
            val wValue = (SAMPLING_FREQ_CONTROL shl 8) or 0x00
            val wIndex = (clockId shl 8) or (acInterfaceId and 0xFF)
            val len = connection.controlTransfer(
                0xA1, UAC2_CS_RANGE, wValue, wIndex, buf, buf.size, 300
            )
            if (len >= 14) {
                val numSubRanges = (buf[0].toInt() and 0xFF) or ((buf[1].toInt() and 0xFF) shl 8)
                var off = 2
                for (i in 0 until numSubRanges) {
                    if (off + 12 > len) break
                    val dMin = (buf[off].toInt() and 0xFF) or
                        ((buf[off + 1].toInt() and 0xFF) shl 8) or
                        ((buf[off + 2].toInt() and 0xFF) shl 16) or
                        ((buf[off + 3].toInt() and 0xFF) shl 24)
                    val dMax = (buf[off + 4].toInt() and 0xFF) or
                        ((buf[off + 5].toInt() and 0xFF) shl 8) or
                        ((buf[off + 6].toInt() and 0xFF) shl 16) or
                        ((buf[off + 7].toInt() and 0xFF) shl 24)
                    if (dMin in 8000..768000 && dMax >= dMin) {
                        if (dMin == dMax) {
                            rates.add(dMin)
                        } else {
                            for (sr in STANDARD_AUDIOPHILE_RATES) {
                                if (sr in dMin..dMax) rates.add(sr)
                            }
                        }
                    }
                    off += 12
                }
            }
        } catch (t: Throwable) {
            Timber.w(t, "Failed to query UAC2 clock range")
        }
        return rates
    }

    private fun negotiateHardwareSampleRateLocked(
        connection: UsbDeviceConnection,
        selectedAlt: UsbStreamingAltSetting,
        targetRate: Int
    ): Int {
        var verifiedRate = targetRate
        try {
            val epAddress = selectedAlt.endpoint.address
            val targetIface = selectedAlt.iface
            if (!isUac2Device) {
                val data3 = byteArrayOf(
                    (targetRate and 0xFF).toByte(),
                    ((targetRate shr 8) and 0xFF).toByte(),
                    ((targetRate shr 16) and 0xFF).toByte()
                )
                var res = connection.controlTransfer(
                    0x22,
                    UAC_SET_CUR,
                    (SAMPLING_FREQ_CONTROL shl 8) or 0x00,
                    epAddress,
                    data3,
                    data3.size,
                    150
                )
                if (res < 0) {
                    res = connection.controlTransfer(
                        0x21,
                        UAC_SET_CUR,
                        (SAMPLING_FREQ_CONTROL shl 8) or 0x00,
                        targetIface.id,
                        data3,
                        data3.size,
                        150
                    )
                }
                if (res < 0) {
                    val readBuf = ByteArray(3)
                    val getRes = connection.controlTransfer(
                        0xA2,
                        UAC_GET_CUR,
                        (SAMPLING_FREQ_CONTROL shl 8) or 0x00,
                        epAddress,
                        readBuf,
                        readBuf.size,
                        100
                    )
                    if (getRes == 3) {
                        val actual = (readBuf[0].toInt() and 0xFF) or
                            ((readBuf[1].toInt() and 0xFF) shl 8) or
                            ((readBuf[2].toInt() and 0xFF) shl 16)
                        if (actual in 8000..384000) {
                            verifiedRate = actual
                        }
                    }
                }
            } else {
                val data4 = byteArrayOf(
                    (targetRate and 0xFF).toByte(),
                    ((targetRate shr 8) and 0xFF).toByte(),
                    ((targetRate shr 16) and 0xFF).toByte(),
                    ((targetRate shr 24) and 0xFF).toByte()
                )
                val wIndexUac2 = if (activeClockSourceId >= 0) {
                    (activeClockSourceId shl 8) or (activeAcInterfaceId and 0xFF)
                } else {
                    targetIface.id
                }
                var res = connection.controlTransfer(
                    0x21,
                    UAC2_CS_CUR,
                    (SAMPLING_FREQ_CONTROL shl 8) or 0x00,
                    wIndexUac2,
                    data4,
                    data4.size,
                    150
                )
                if (res < 0) {
                    val data3 = byteArrayOf(
                        (targetRate and 0xFF).toByte(),
                        ((targetRate shr 8) and 0xFF).toByte(),
                        ((targetRate shr 16) and 0xFF).toByte()
                    )
                    res = connection.controlTransfer(
                        0x22,
                        UAC_SET_CUR,
                        (SAMPLING_FREQ_CONTROL shl 8) or 0x00,
                        epAddress,
                        data3,
                        data3.size,
                        150
                    )
                }
                if (res < 0) {
                    val readBuf = ByteArray(4)
                    val getRes = connection.controlTransfer(
                        0xA1,
                        UAC2_CS_CUR,
                        (SAMPLING_FREQ_CONTROL shl 8) or 0x00,
                        wIndexUac2,
                        readBuf,
                        readBuf.size,
                        100
                    )
                    if (getRes == 4) {
                        val actual = (readBuf[0].toInt() and 0xFF) or
                            ((readBuf[1].toInt() and 0xFF) shl 8) or
                            ((readBuf[2].toInt() and 0xFF) shl 16) or
                            ((readBuf[3].toInt() and 0xFF) shl 24)
                        if (actual in 8000..768000) {
                            verifiedRate = actual
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Timber.w(e, "Control transfer sample rate error")
        }
        return verifiedRate
    }

    private fun releaseActiveConnectionLocked() {
        if (isNativeLibLoaded) {
            runCatching { stopNativeDac() }
        }
        for (iface in claimedAudioInterfaces) {
            runCatching { activeConnection?.releaseInterface(iface) }
        }
        claimedAudioInterfaces.clear()
        activeAudioInterface?.let { runCatching { activeConnection?.releaseInterface(it) } }
        runCatching { activeConnection?.close() }
        activeDevice = null
        activeConnection = null
        activeAudioInterface = null
        activeEndpoint = null
        availableAltSettings.clear()
        _supportedSampleRates.clear()
        lastHardwareSampleRate = -1
        lastHardwareSubframeSize = -1
    }

    private fun claimAndInitializeDac(device: UsbDevice) {
        synchronized(dacLock) {
            if (isAlreadyConnectedLocked(device)) {
                return
            }
            if (activeConnection != null) {
                releaseActiveConnectionLocked()
            }

            try {
                val connection = usbManager.openDevice(device)
                    ?: throw IllegalStateException("Could not open USB connection")

                var parsedFeatureUnitId = -1
                var parsedAcInterfaceId = 0
                var parsedClockSourceId = -1
                var parsedAcInterfaceProtocol = 0
                var hasUac2Streaming = false

                val altFormatMap = mutableMapOf<Pair<Int, Int>, ParsedAltFormat>()
                val altIsPcmMap = mutableMapOf<Pair<Int, Int>, Boolean>()
                availableAltSettings.clear()
                _supportedSampleRates.clear()

                val raw = runCatching { connection.rawDescriptors }.getOrNull()
                if (raw != null && raw.size >= 4) {
                    var pos = 0
                    var curIfaceNum = -1
                    var curAltSetting = 0
                    var curIfaceClass = -1
                    var curIfaceSubclass = -1
                    var curIfaceProtocol = 0

                    while (pos + 2 <= raw.size) {
                        val len = raw[pos].toInt() and 0xFF
                        if (len < 2 || pos + len > raw.size) break
                        val dType = raw[pos + 1].toInt() and 0xFF

                        if (dType == 0x04 && len >= 9) {
                            curIfaceNum = raw[pos + 2].toInt() and 0xFF
                            curAltSetting = raw[pos + 3].toInt() and 0xFF
                            curIfaceClass = raw[pos + 5].toInt() and 0xFF
                            curIfaceSubclass = raw[pos + 6].toInt() and 0xFF
                            curIfaceProtocol = raw[pos + 7].toInt() and 0xFF

                            if (curIfaceClass == USB_CLASS_AUDIO && curIfaceSubclass == 1) {
                                parsedAcInterfaceId = curIfaceNum
                                parsedAcInterfaceProtocol = curIfaceProtocol
                            } else if (curIfaceClass == USB_CLASS_AUDIO && curIfaceSubclass == 2 && curIfaceProtocol == 0x20) {
                                hasUac2Streaming = true
                            }
                        } else if (dType == 0x24 && curIfaceClass == USB_CLASS_AUDIO && len >= 4) {
                            val dSubtype = raw[pos + 2].toInt() and 0xFF
                            if (curIfaceSubclass == 1) {
                                if (dSubtype == 0x06 && len >= 5) {
                                    val unitId = raw[pos + 3].toInt() and 0xFF
                                    val masterControls = if (pos + 5 < pos + len) raw[pos + 5].toInt() and 0xFF else 0
                                    val hasVolume = (masterControls and 0x02) != 0 || (masterControls and 0x0C) != 0
                                    if (parsedFeatureUnitId < 0 || hasVolume) {
                                        parsedFeatureUnitId = unitId
                                    }
                                } else if (dSubtype == 0x0A && len >= 5) {
                                    val clockId = raw[pos + 3].toInt() and 0xFF
                                    if (parsedClockSourceId < 0) {
                                        parsedClockSourceId = clockId
                                    }
                                }
                            } else if (curIfaceSubclass == 2) {
                                if (dSubtype == 0x01) {
                                    var isPcmAlt = true
                                    if (curIfaceProtocol == 0x20 && len >= 10) {
                                        val bFormatType = raw[pos + 5].toInt() and 0xFF
                                        val bmFormats = (raw[pos + 6].toInt() and 0xFF) or
                                            ((raw[pos + 7].toInt() and 0xFF) shl 8) or
                                            ((raw[pos + 8].toInt() and 0xFF) shl 16) or
                                            ((raw[pos + 9].toInt() and 0xFF) shl 24)
                                        isPcmAlt = (bFormatType == 1) && ((bmFormats and 0x01) != 0)
                                    } else if (curIfaceProtocol != 0x20 && len >= 7) {
                                        val wFormatTag = (raw[pos + 5].toInt() and 0xFF) or
                                            ((raw[pos + 6].toInt() and 0xFF) shl 8)
                                        isPcmAlt = (wFormatTag == 0x0001)
                                    }
                                    altIsPcmMap[curIfaceNum to curAltSetting] = isPcmAlt
                                    val existing = altFormatMap[curIfaceNum to curAltSetting]
                                    if (existing != null) {
                                        altFormatMap[curIfaceNum to curAltSetting] = existing.copy(isPcm = isPcmAlt)
                                    }
                                } else if (dSubtype == 0x02 && len >= 6) {
                                    val formatType = raw[pos + 3].toInt() and 0xFF
                                    if (formatType == 1) {
                                        var sfSize = 2
                                        var bitRes = 16
                                        val altRates = mutableSetOf<Int>()

                                        if (curIfaceProtocol == 0x20 || len == 6) {
                                            sfSize = (raw[pos + 4].toInt() and 0xFF).coerceIn(1, 4)
                                            bitRes = (raw[pos + 5].toInt() and 0xFF)
                                        } else if (len >= 8) {
                                            sfSize = (raw[pos + 5].toInt() and 0xFF).coerceIn(1, 4)
                                            bitRes = (raw[pos + 6].toInt() and 0xFF)
                                            val bSamFreqType = raw[pos + 7].toInt() and 0xFF
                                            if (bSamFreqType == 0 && pos + 13 < pos + len) {
                                                val minRate = (raw[pos + 8].toInt() and 0xFF) or
                                                    ((raw[pos + 9].toInt() and 0xFF) shl 8) or
                                                    ((raw[pos + 10].toInt() and 0xFF) shl 16)
                                                val maxRate = (raw[pos + 11].toInt() and 0xFF) or
                                                    ((raw[pos + 12].toInt() and 0xFF) shl 8) or
                                                    ((raw[pos + 13].toInt() and 0xFF) shl 16)
                                                for (sr in STANDARD_AUDIOPHILE_RATES) {
                                                    if (sr in minRate..maxRate) {
                                                        altRates.add(sr)
                                                        _supportedSampleRates.add(sr)
                                                    }
                                                }
                                            } else if (bSamFreqType > 0) {
                                                for (f in 0 until bSamFreqType) {
                                                    val off = pos + 8 + f * 3
                                                    if (off + 2 < pos + len) {
                                                        val rate = (raw[off].toInt() and 0xFF) or
                                                            ((raw[off + 1].toInt() and 0xFF) shl 8) or
                                                            ((raw[off + 2].toInt() and 0xFF) shl 16)
                                                        if (rate in 8000..768000) {
                                                            altRates.add(rate)
                                                            _supportedSampleRates.add(rate)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        if (sfSize < 2) sfSize = 2
                                        if (bitRes <= 0) bitRes = sfSize * 8
                                        val isPcmAlt = altIsPcmMap[curIfaceNum to curAltSetting] ?: true
                                        altFormatMap[curIfaceNum to curAltSetting] = ParsedAltFormat(
                                            subframeSize = sfSize,
                                            bitResolution = bitRes,
                                            isPcm = isPcmAlt,
                                            supportedRates = altRates
                                        )
                                    }
                                }
                            }
                        }
                        pos += len
                    }
                }

                claimedAudioInterfaces.clear()
                val claimedInterfaceIds = mutableSetOf<Int>()
                for (i in 0 until device.interfaceCount) {
                    val iface = device.getInterface(i)
                    if (iface.interfaceClass == USB_CLASS_AUDIO && claimedInterfaceIds.add(iface.id)) {
                        try {
                            val claimed = connection.claimInterface(iface, true)
                            if (claimed) {
                                claimedAudioInterfaces.add(iface)
                            }
                        } catch (t: Throwable) {
                            Timber.w(t, "Could not force claim audio interface %d", iface.id)
                        }
                    }
                }

                for (i in 0 until device.interfaceCount) {
                    val iface = device.getInterface(i)
                    if (iface.interfaceClass == USB_CLASS_AUDIO) {
                        if (iface.interfaceSubclass == 2 && iface.interfaceProtocol == 0x20) {
                            hasUac2Streaming = true
                        }
                        val parsedFmt = altFormatMap[iface.id to iface.alternateSetting]
                        val sfSize = parsedFmt?.subframeSize ?: 2
                        val bitRes = parsedFmt?.bitResolution ?: 16
                        val isPcm = parsedFmt?.isPcm ?: (altIsPcmMap[iface.id to iface.alternateSetting] ?: true)
                        val altRates = parsedFmt?.supportedRates ?: emptySet()

                        for (e in 0 until iface.endpointCount) {
                            val ep = iface.getEndpoint(e)
                            val isIsoOut = ep.direction == UsbConstants.USB_DIR_OUT &&
                                (ep.attributes and UsbConstants.USB_ENDPOINT_XFERTYPE_MASK) == UsbConstants.USB_ENDPOINT_XFER_ISOC
                            val isExplicitFeedback = (ep.attributes and 0x30) == 0x10
                            if (isIsoOut && !isExplicitFeedback && ep.maxPacketSize > 0) {
                                availableAltSettings.add(
                                    UsbStreamingAltSetting(
                                        iface = iface,
                                        endpoint = ep,
                                        altSetting = iface.alternateSetting,
                                        subframeSize = sfSize,
                                        bitResolution = bitRes,
                                        maxPacketSize = ep.maxPacketSize,
                                        interval = ep.interval,
                                        isPcm = isPcm,
                                        supportedRates = altRates
                                    )
                                )
                            }
                        }
                    }
                }

                val isUac2 = (parsedClockSourceId >= 0) || (parsedAcInterfaceProtocol == 0x20) || hasUac2Streaming
                isUac2Device = isUac2
                lastHardwareSampleRate = -1
                lastHardwareSubframeSize = -1

                if (isUac2) {
                    val clockRates = queryUac2ClockSampleRates(connection, parsedClockSourceId, parsedAcInterfaceId)
                    if (clockRates.isNotEmpty()) {
                        _supportedSampleRates.addAll(clockRates)
                    }
                    if (_supportedSampleRates.isEmpty()) {
                        _supportedSampleRates.addAll(listOf(44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000))
                    }
                } else {
                    if (_supportedSampleRates.isEmpty()) {
                        _supportedSampleRates.addAll(listOf(44100, 48000))
                    }
                }

                val preferredInitialRate = if (_supportedSampleRates.contains(48000)) 48000 else (_supportedSampleRates.firstOrNull() ?: 44100)
                val initialAlt = selectBestAltSetting(preferredInitialRate)

                if (initialAlt != null) {
                    val outInterface = initialAlt.iface
                    val outEndpoint = initialAlt.endpoint
                    _activeSubframeSize = initialAlt.subframeSize.coerceIn(2, 4)

                    activeDevice = device
                    activeConnection = connection
                    activeAudioInterface = outInterface
                    activeEndpoint = outEndpoint
                    activeFeatureUnitId = parsedFeatureUnitId
                    activeAcInterfaceId = parsedAcInterfaceId
                    activeClockSourceId = parsedClockSourceId

                    try {
                        connection.setInterface(outInterface)
                    } catch (t: Throwable) {
                        Timber.w(t, "Failed to set initial alternate setting ${outInterface.alternateSetting}")
                    }

                    val verifiedRate = negotiateHardwareSampleRateLocked(connection, initialAlt, preferredInitialRate)
                    _activeTargetSampleRate = verifiedRate
                    lastHardwareSampleRate = verifiedRate
                    lastHardwareSubframeSize = _activeSubframeSize

                    val nativeFd = connection.fileDescriptor
                    if (isNativeLibLoaded) {
                        try {
                            setNativeFormat(verifiedRate, verifiedRate, _activeSubframeSize, 2)
                            initNativeDac(
                                nativeFd,
                                outEndpoint.address,
                                outEndpoint.maxPacketSize,
                                outEndpoint.interval
                            )
                            setHardwareVolume(_dacVolumePercent.value)
                        } catch (t: Throwable) {
                            Timber.w(t, "Native DAC init skipped")
                        }
                    }

                    val dacName = device.productName ?: "USB Audio DAC"
                    _dacState.value = UsbDacState.Connected(
                        deviceName = dacName,
                        sampleRate = verifiedRate,
                        bitDepth = initialAlt.bitResolution,
                        channels = 2
                    )
                    logBuffer.logDac("DAC connected: $dacName (FD=$nativeFd, EP=0x${Integer.toHexString(outEndpoint.address)}, alt=${initialAlt.altSetting}, ${initialAlt.bitResolution}-bit, ${verifiedRate}Hz)")
                } else {
                    connection.close()
                    _dacState.value = UsbDacState.Error("Failed to claim USB audio interface")
                }
            } catch (e: Throwable) {
                Timber.e(e, "Error initializing USB DAC")
                _dacState.value = UsbDacState.Error(e.message ?: "DAC init error")
            }
        }
    }

    val isExclusiveActive: Boolean
        get() = _dacState.value is UsbDacState.Connected

    fun setStreamFormatOnly(streamId: Int, sampleRate: Int, channels: Int) {
        if (isNativeLibLoaded) {
            try {
                nativeSetStreamFormat(streamId, sampleRate, channels)
            } catch (_: Throwable) {
            }
        }
    }

    fun configureSampleRateAndFormat(
        sampleRate: Int,
        bitDepth: Int,
        channels: Int,
        streamId: Int = 0
    ) {
        synchronized(dacLock) {
            setStreamFormatOnly(streamId, sampleRate, channels)

            if (isOtherStreamActive(streamId) && lastHardwareSampleRate > 0) {
                return
            }

            val connection = activeConnection ?: return
            val dev = activeDevice ?: return

            var targetRate = resolveTargetSampleRate(sampleRate)
            val selectedAlt = selectBestAltSetting(targetRate)

            if (selectedAlt != null) {
                if (selectedAlt.supportedRates.isNotEmpty() && !selectedAlt.supportedRates.contains(targetRate)) {
                    targetRate = resolveTargetSampleRate(sampleRate, selectedAlt.supportedRates)
                }

                _activeSubframeSize = selectedAlt.subframeSize.coerceIn(2, 4)
                val targetIface = selectedAlt.iface

                val rateChanged = (lastHardwareSampleRate != targetRate)
                val sfChanged = (lastHardwareSubframeSize != _activeSubframeSize)
                val ifaceChanged = (activeAudioInterface?.id != targetIface.id ||
                    activeAudioInterface?.alternateSetting != targetIface.alternateSetting)

                if (rateChanged || sfChanged || ifaceChanged) {
                    if (isNativeLibLoaded) {
                        runCatching {
                            suspendNativeUrbThread()
                            clearNativeRingBuffer(streamId)
                        }
                    }

                    var alt0: UsbInterface? = null
                    for (i in 0 until dev.interfaceCount) {
                        val candidate = dev.getInterface(i)
                        if (candidate.id == targetIface.id && candidate.alternateSetting == 0) {
                            alt0 = candidate
                            break
                        }
                    }
                    if (alt0 != null) {
                        runCatching { connection.setInterface(alt0) }
                    }

                    try {
                        connection.setInterface(targetIface)
                        activeAudioInterface = targetIface
                    } catch (t: Throwable) {
                        Timber.w(t, "Failed to switch alternate setting to %d", selectedAlt.altSetting)
                    }

                    val verifiedRate = negotiateHardwareSampleRateLocked(connection, selectedAlt, targetRate)
                    targetRate = verifiedRate
                    _activeTargetSampleRate = targetRate
                    activeEndpoint = selectedAlt.endpoint
                    lastHardwareSampleRate = targetRate
                    lastHardwareSubframeSize = _activeSubframeSize

                    if (isNativeLibLoaded) {
                        val ep = selectedAlt.endpoint
                        runCatching {
                            setNativeFormat(sampleRate, targetRate, _activeSubframeSize, channels)
                            nativeSetStreamFormat(streamId, sampleRate, channels)
                            initNativeDac(
                                connection.fileDescriptor,
                                ep.address,
                                ep.maxPacketSize,
                                ep.interval
                            )
                            val shouldBePlaying = synchronized(slotLock) { streamPlaying[streamId] }
                            nativeSetPaused(streamId, !shouldBePlaying)
                        }
                    }
                } else {
                    _activeTargetSampleRate = targetRate
                    if (isNativeLibLoaded) {
                        runCatching {
                            setNativeFormat(sampleRate, targetRate, _activeSubframeSize, channels)
                            nativeSetStreamFormat(streamId, sampleRate, channels)
                        }
                    }
                }
            } else {
                _activeTargetSampleRate = targetRate
                if (isNativeLibLoaded) {
                    runCatching {
                        setNativeFormat(sampleRate, targetRate, _activeSubframeSize, channels)
                        nativeSetStreamFormat(streamId, sampleRate, channels)
                    }
                }
            }

            val dacName = dev.productName ?: "USB Audio DAC"
            _dacState.value = UsbDacState.Connected(
                deviceName = dacName,
                sampleRate = targetRate,
                bitDepth = bitDepth,
                channels = channels
            )
            if (sampleRate != targetRate) {
                logBuffer.logDac("DAC configure: $dacName -> ${sampleRate}Hz adapted to ${targetRate}Hz, ${bitDepth}-bit (slot ${_activeSubframeSize * 8}-bit), ${channels}ch")
            } else {
                logBuffer.logDac("DAC configure: $dacName -> ${targetRate}Hz direct 1:1, ${bitDepth}-bit (slot ${_activeSubframeSize * 8}-bit), ${channels}ch")
            }
        }
    }

    fun handleDisconnect() {
        synchronized(slotLock) {
            streamPlaying[0] = false
            streamPlaying[1] = false
            slotOwners[0] = null
            slotOwners[1] = null
        }
        scope.launch(Dispatchers.Main) {
            onPausePlayerCallback?.invoke()
        }

        synchronized(dacLock) {
            pendingPermissionDeviceName = null
            try {
                releaseActiveConnectionLocked()
            } catch (e: Throwable) {
                Timber.e(e, "Error releasing USB resources")
            } finally {
                logBuffer.logDac("DAC disconnected")
                _dacState.value = UsbDacState.Idle
            }
        }
    }

    fun safeClearNativeRingBuffer(streamId: Int = -1) {
        if (!isNativeLibLoaded) return
        try {
            clearNativeRingBuffer(streamId)
        } catch (_: Throwable) {
        }
    }

    fun getNativePlayedFrames(streamId: Int = 0): Long {
        if (!isNativeLibLoaded) return 0L
        return try {
            nativeGetPlayedFrames(streamId)
        } catch (_: Throwable) {
            0L
        }
    }

    fun getNativeRingBufferAvailable(streamId: Int = 0): Int {
        if (!isNativeLibLoaded) return 0
        return try {
            nativeGetRingBufferAvailable(streamId)
        } catch (_: Throwable) {
            0
        }
    }

    fun setNativeStreamPaused(streamId: Int, paused: Boolean) {
        synchronized(slotLock) {
            if (streamId in 0..1) {
                streamPlaying[streamId] = !paused
            } else {
                streamPlaying[0] = !paused
                streamPlaying[1] = !paused
            }
        }
        if (!isNativeLibLoaded) return
        try {
            nativeSetPaused(streamId, paused)
        } catch (_: Throwable) {
        }
    }

    fun setNativePaused(paused: Boolean) {
        setNativeStreamPaused(-1, paused)
    }

    fun setNativeVolumeScale(scale: Float) {
        if (!isNativeLibLoaded) return
        try {
            nativeSetVolumeScale(scale)
        } catch (_: Throwable) {
        }
    }

    fun setNativeStreamVolume(streamId: Int, scale: Float) {
        if (!isNativeLibLoaded) return
        try {
            nativeSetStreamVolume(streamId, scale)
        } catch (_: Throwable) {
        }
    }

    fun writeBuffer(
        streamId: Int,
        buffer: ByteBuffer,
        pcmEncoding: Int,
        targetBitDepth: Int
    ): Int {
        if (!isNativeLibLoaded || !buffer.hasRemaining()) return 0
        val pos = buffer.position()
        val remaining = buffer.remaining()
        val consumed = if (buffer.isDirect) {
            try {
                nativeWriteDirectBuffer(streamId, buffer, pos, remaining, pcmEncoding, targetBitDepth)
            } catch (_: Throwable) {
                0
            }
        } else if (buffer.hasArray()) {
            try {
                nativeWriteByteArrayFormat(streamId, buffer.array(), buffer.arrayOffset() + pos, remaining, pcmEncoding, targetBitDepth)
            } catch (_: Throwable) {
                0
            }
        } else {
            val chunk = minOf(remaining, 16384)
            val temp = ByteArray(chunk)
            val mark = buffer.position()
            buffer.get(temp, 0, chunk)
            buffer.position(mark)
            try {
                nativeWriteByteArrayFormat(streamId, temp, 0, chunk, pcmEncoding, targetBitDepth)
            } catch (_: Throwable) {
                0
            }
        }
        if (consumed > 0) {
            buffer.position(pos + consumed)
        }
        return consumed
    }

    private fun getUsbDeviceFromIntent(intent: Intent): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

    private external fun suspendNativeUrbThread()
    private external fun initNativeDac(fd: Int, epOut: Int, maxPacketSize: Int, epInterval: Int): Boolean
    private external fun setNativeFormat(srcSampleRate: Int, targetSampleRate: Int, bitDepthOrSubframeSize: Int, channels: Int): Boolean
    private external fun nativeSetStreamFormat(streamId: Int, srcSampleRate: Int, srcChannels: Int)
    private external fun stopNativeDac(): Boolean
    private external fun clearNativeRingBuffer(streamId: Int)
    private external fun nativeGetPlayedFrames(streamId: Int): Long
    private external fun nativeGetRingBufferAvailable(streamId: Int): Int
    private external fun nativeSetPaused(streamId: Int, paused: Boolean)
    private external fun nativeSetVolumeScale(scale: Float)
    private external fun nativeSetStreamVolume(streamId: Int, scale: Float)
    private external fun nativeWriteDirectBuffer(
        streamId: Int,
        buffer: ByteBuffer,
        pos: Int,
        len: Int,
        pcmEncoding: Int,
        targetBitDepth: Int
    ): Int
    private external fun nativeWriteByteArrayFormat(
        streamId: Int,
        data: ByteArray,
        offset: Int,
        length: Int,
        pcmEncoding: Int,
        targetBitDepth: Int
    ): Int
}
