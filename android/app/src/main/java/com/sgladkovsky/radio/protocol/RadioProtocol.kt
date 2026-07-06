package com.sgladkovsky.radio.protocol

import com.sgladkovsky.radio.model.RadioBand
import com.sgladkovsky.radio.model.RadioStation
import java.nio.charset.Charset
import java.util.Locale

/**
 * Serial protocol extracted from dab2_V3.12 (com.hyinfo.dab).
 * Frame layout: FA 55, sequence, length, payload, checksum.
 */
object RadioProtocol {
    const val VENDOR_ID_PRIMARY = 0x2E88
    const val PRODUCT_ID_PRIMARY = 0x4605
    const val VENDOR_ID_ALT = 0x0483
    const val PRODUCT_ID_ALT = 0x5740
    const val BAUD_RATE = 115200

    private val COMMAND_TEMPLATES = mapOf(
        RadioCommand.AUTO_SCAN to byteArrayOf(0xFA.toByte(), 0x55, 0x00, 0x00, 0x03, 0x80.toByte(), 0x83.toByte(), 0x00),
        RadioCommand.AUTO_SCAN_LONG to byteArrayOf(0xFA.toByte(), 0x55, 0x00, 0x00, 0x03, 0x80.toByte(), 0xA3.toByte(), 0x00),
        RadioCommand.CANCEL_SCAN to byteArrayOf(0xFA.toByte(), 0x55, 0x00, 0x00, 0x03, 0x80.toByte(), 0x03, 0x00),
        RadioCommand.BAND_AM to byteArrayOf(0xFA.toByte(), 0x55, 0x00, 0x00, 0x03, 0x80.toByte(), 0x3D, 0x00),
        RadioCommand.BAND_FM to byteArrayOf(0xFA.toByte(), 0x55, 0x00, 0x00, 0x03, 0x80.toByte(), 0x3E, 0x00),
        RadioCommand.BAND_DAB to byteArrayOf(0xFA.toByte(), 0x55, 0x00, 0x00, 0x03, 0x80.toByte(), 0x90.toByte(), 0x00),
        RadioCommand.SEEK_UP to byteArrayOf(0xFA.toByte(), 0x55, 0x00, 0x00, 0x03, 0x80.toByte(), 0x84.toByte(), 0x00),
        RadioCommand.SEEK_DOWN to byteArrayOf(0xFA.toByte(), 0x55, 0x00, 0x00, 0x03, 0x80.toByte(), 0x86.toByte(), 0x00),
        RadioCommand.TUNE_UP to byteArrayOf(0xFA.toByte(), 0x55, 0x00, 0x00, 0x03, 0x80.toByte(), 0x87.toByte(), 0x00),
        RadioCommand.TUNE_DOWN to byteArrayOf(0xFA.toByte(), 0x55, 0x00, 0x00, 0x03, 0x80.toByte(), 0x88.toByte(), 0x00),
        RadioCommand.REQUEST_PLAY_INFO to byteArrayOf(0xFA.toByte(), 0x55, 0x00, 0x00, 0x04, 0x80.toByte(), 0x85.toByte(), 0x01, 0x00),
        RadioCommand.REQUEST_STATION_LIST to byteArrayOf(0xFA.toByte(), 0x55, 0x00, 0x00, 0x04, 0x80.toByte(), 0x85.toByte(), 0x02, 0x00),
        RadioCommand.REQUEST_TEXT to byteArrayOf(0xFA.toByte(), 0x55, 0x00, 0x00, 0x04, 0x80.toByte(), 0x85.toByte(), 0x04, 0x00),
        RadioCommand.REQUEST_ALL_INFO to byteArrayOf(0xFA.toByte(), 0x55, 0x00, 0x00, 0x04, 0x80.toByte(), 0x85.toByte(), 0x07, 0x00)
    )

    private val PLAY_SELECT_TEMPLATE = byteArrayOf(
        0xFA.toByte(), 0x55, 0x00, 0x00, 0x07, 0x80.toByte(), 0x01, 0x00, 0x00, 0x00, 0x00, 0x00
    )

    private val AREA_SELECT_TEMPLATE = byteArrayOf(
        0xFA.toByte(), 0x55, 0x00, 0x00, 0x04, 0x80.toByte(), 0x3F, 0x00, 0x00
    )

    fun buildCommand(command: RadioCommand, packetNumber: Int): ByteArray {
        val template = COMMAND_TEMPLATES[command]
            ?: throw IllegalArgumentException("Unknown command: $command")
        return finalizePacket(template.copyOf(), packetNumber)
    }

    fun buildPlayStation(station: RadioStation, packetNumber: Int): ByteArray {
        val packet = PLAY_SELECT_TEMPLATE.copyOf()
        val modeFlag = station.band.code
        packet[7] = (((station.cid / 256) and 0xFF) or (modeFlag * 0x20 + 0x0F)).toByte()
        packet[8] = (station.cid and 0xFF).toByte()
        packet[9] = ((station.sid / 256) and 0xFF).toByte()
        packet[10] = (station.sid and 0xFF).toByte()
        return finalizePacket(packet, packetNumber)
    }

    fun buildTuneFrequency(band: RadioBand, frequency: Int, packetNumber: Int): ByteArray {
        return buildPlayStation(
            RadioStation(
                name = "",
                frequency = frequency,
                cid = frequency,
                sid = 0,
                band = band
            ),
            packetNumber
        )
    }

    fun buildAreaSelect(areaIndex: Int, packetNumber: Int): ByteArray {
        val packet = AREA_SELECT_TEMPLATE.copyOf()
        packet[7] = areaIndex.toByte()
        return finalizePacket(packet, packetNumber)
    }

    fun isSupportedDevice(vendorId: Int, productId: Int): Boolean {
        return (vendorId == VENDOR_ID_PRIMARY && productId == PRODUCT_ID_PRIMARY) ||
            (vendorId == VENDOR_ID_ALT && productId == PRODUCT_ID_ALT)
    }

    fun extractPackets(buffer: ByteArray, size: Int): Pair<List<ByteArray>, Int> {
        val packets = mutableListOf<ByteArray>()
        var offset = 0

        while (offset + 5 < size) {
            if (buffer[offset].toInt() and 0xFF != 0xFA ||
                buffer[offset + 1].toInt() and 0xFF != 0x55
            ) {
                offset++
                continue
            }

            val packetLength = ((buffer[offset + 3].toInt() and 0xFF) shl 8) +
                (buffer[offset + 4].toInt() and 0xFF) + 5

            if (offset + packetLength > size) {
                break
            }

            packets.add(buffer.copyOfRange(offset, offset + packetLength))
            offset += packetLength
        }

        return packets to offset
    }

    fun verifyChecksum(packet: ByteArray): Boolean {
        if (packet.size < 6) return false
        return packet.last() == checksum(packet)
    }

    fun parsePlayInfo(packet: ByteArray): PlayInfo? {
        if (packet.size < 12 || (packet[5].toInt() and 0xFF) != 0x22) return null

        val band = RadioBand.fromCode((packet[6].toInt() shr 3) and 0x03)
        val frequency = ((packet[6].toInt() and 0x0F) shl 8) or (packet[7].toInt() and 0xFF)
        val sid = ((packet[9].toInt() and 0xFF) shl 8) or (packet[10].toInt() and 0xFF)
        val isFavorite = ((packet[6].toInt() shr 7) and 0x01) == 1

        val ensemble = if (packet.size > 0x1A) {
            decodeText(packet[12], packet.copyOfRange(14, minOf(14 + 16, packet.size - 1)))
        } else {
            ""
        }

        val serviceName = if (packet.size > 0x36) {
            decodeText(packet[0x34], packet.copyOfRange(0x36, minOf(0x36 + 16, packet.size - 1)))
        } else {
            ""
        }

        val rds = if (packet.size > 0x12) {
            decodeText(packet[0x20], packet.copyOfRange(8, minOf(8 + 16, packet.size - 1)))
        } else {
            ""
        }

        return PlayInfo(
            band = band,
            frequency = frequency,
            sid = sid,
            ensemble = ensemble.trim(),
            serviceName = serviceName.trim(),
            rdsText = rds.trim(),
            isFavorite = isFavorite
        )
    }

    fun parseStationListItem(packet: ByteArray, offset: Int): RadioStation? {
        if (packet.size < offset + 23 || (packet[5].toInt() and 0xFF) != 0x21) return null

        return try {
            val cid = ((packet[offset].toInt() and 0xFF) shl 8) or (packet[offset + 1].toInt() and 0xFF)
            val modeFlag = (packet[offset + 2].toInt() shr 3) and 0x03
            val frequency = ((packet[offset + 2].toInt() and 0x0F) shl 8) or (packet[offset + 3].toInt() and 0xFF)
            val sid = ((packet[offset + 4].toInt() and 0xFF) shl 8) or (packet[offset + 5].toInt() and 0xFF)
            val isFavorite = ((packet[offset + 2].toInt() shr 6) and 0x01) == 1
            val name = decodeText(packet[offset + 5], packet.copyOfRange(offset + 6, offset + 22))

            RadioStation(
                name = name.trim().ifEmpty { formatFrequency(frequency, RadioBand.fromCode(modeFlag)) },
                frequency = frequency,
                cid = cid,
                sid = sid,
                band = RadioBand.fromCode(modeFlag),
                isFavorite = isFavorite
            )
        } catch (_: Exception) {
            null
        }
    }

    fun formatFrequency(frequency: Int, band: RadioBand): String {
        return when (band) {
            RadioBand.AM -> "$frequency kHz"
            RadioBand.FM -> String.format(Locale.US, "%.1f MHz", frequency / 10.0)
            RadioBand.DAB -> "SID %04X".format(Locale.US, frequency)
        }
    }

    private fun finalizePacket(packet: ByteArray, packetNumber: Int): ByteArray {
        packet[2] = (packetNumber and 0xFF).toByte()
        packet[packet.lastIndex] = checksum(packet)
        return packet
    }

    private fun checksum(packet: ByteArray): Byte {
        var sum = 0
        for (index in 5 until packet.size - 1) {
            sum += packet[index].toInt() and 0xFF
        }
        return ((-sum) and 0xFF).toByte()
    }

    private fun decodeText(typeByte: Byte, data: ByteArray): String {
        val encoding = (typeByte.toInt() shr 4) and 0x0F
        return when (encoding) {
            0x0F -> runCatching { String(data, Charset.forName("UTF-16BE")) }.getOrDefault("")
            0x06 -> runCatching { String(data, Charsets.US_ASCII) }.getOrDefault("")
            else -> data.joinToString("") { byte ->
                val code = byte.toInt() and 0xFF
                if (code == 0) "" else code.toChar().toString()
            }
        }.trim { it <= ' ' || it == '\u0000' }
    }
}

enum class RadioCommand {
    AUTO_SCAN,
    AUTO_SCAN_LONG,
    CANCEL_SCAN,
    BAND_AM,
    BAND_FM,
    BAND_DAB,
    SEEK_UP,
    SEEK_DOWN,
    TUNE_UP,
    TUNE_DOWN,
    REQUEST_PLAY_INFO,
    REQUEST_STATION_LIST,
    REQUEST_TEXT,
    REQUEST_ALL_INFO
}

data class PlayInfo(
    val band: RadioBand,
    val frequency: Int,
    val sid: Int,
    val ensemble: String,
    val serviceName: String,
    val rdsText: String,
    val isFavorite: Boolean
)
