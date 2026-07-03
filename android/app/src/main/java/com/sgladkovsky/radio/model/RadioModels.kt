package com.sgladkovsky.radio.model

enum class RadioBand(val code: Int) {
    AM(0),
    FM(1),
    DAB(2);

    companion object {
        fun fromCode(code: Int): RadioBand = entries.firstOrNull { it.code == code } ?: FM
    }
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
    val frequency: Int = 0,
    val stationName: String = "",
    val rdsText: String = "",
    val statusMessage: String = "",
    val stations: List<RadioStation> = emptyList()
)
