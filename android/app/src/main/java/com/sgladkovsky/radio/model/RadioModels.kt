package com.sgladkovsky.radio.model

enum class RadioBand(val code: Int) {
    AM(0),
    FM(1),
    DAB(2);

    companion object {
        fun fromCode(code: Int): RadioBand = entries.firstOrNull { it.code == code } ?: FM
    }
}

object BandRanges {
    const val AM_START_KHZ = 530
    const val AM_END_KHZ = 1710
    const val FM_START_TENTH_MHZ = 875   // 87.5 MHz
    const val FM_END_TENTH_MHZ = 1079  // 107.9 MHz

    fun startFrequency(band: RadioBand): Int = when (band) {
        RadioBand.AM -> AM_START_KHZ
        RadioBand.FM -> FM_START_TENTH_MHZ
        RadioBand.DAB -> 0
    }

    fun tuneStep(band: RadioBand): Int = when (band) {
        RadioBand.AM -> 9
        RadioBand.FM -> 1
        RadioBand.DAB -> 0
    }

    fun stepFrequency(band: RadioBand, frequency: Int, direction: Int): Int {
        if (direction == 0 || band == RadioBand.DAB) return frequency
        val step = tuneStep(band) * direction
        val next = frequency + step
        return when (band) {
            RadioBand.AM -> next.coerceIn(AM_START_KHZ, AM_END_KHZ)
            RadioBand.FM -> next.coerceIn(FM_START_TENTH_MHZ, FM_END_TENTH_MHZ)
            RadioBand.DAB -> frequency
        }
    }

    fun defaultFrequencies(): Map<RadioBand, Int> =
        RadioBand.entries.associateWith { startFrequency(it) }
}

data class RadioStation(
    val name: String,
    val frequency: Int,
    val cid: Int = frequency,
    val sid: Int = 0,
    val band: RadioBand = RadioBand.FM,
    val ensemble: String = "",
    val isFavorite: Boolean = false
)

data class RadioState(
    val connected: Boolean = false,
    val scanning: Boolean = false,
    val playing: Boolean = false,
    val band: RadioBand = RadioBand.FM,
    val frequency: Int = BandRanges.startFrequency(RadioBand.FM),
    val frequenciesByBand: Map<RadioBand, Int> = BandRanges.defaultFrequencies(),
    val stationName: String = "",
    val rdsText: String = "",
    val statusMessage: String = "",
    val stations: List<RadioStation> = emptyList(),
    val currentSid: Int = 0
) {
    fun stationsForBand(band: RadioBand = this.band): List<RadioStation> =
        stations.filter { it.band == band }

    fun isStationSelected(station: RadioStation): Boolean =
        station.band == band &&
            station.frequency == frequency &&
            (band != RadioBand.DAB || station.sid == currentSid)
}
