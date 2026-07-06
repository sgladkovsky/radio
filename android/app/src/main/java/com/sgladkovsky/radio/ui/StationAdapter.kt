package com.sgladkovsky.radio.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.sgladkovsky.radio.R
import com.sgladkovsky.radio.databinding.ItemStationBinding
import com.sgladkovsky.radio.model.RadioStation
import com.sgladkovsky.radio.protocol.RadioProtocol

class StationAdapter(
    private val onStationClick: (RadioStation) -> Unit
) : ListAdapter<StationAdapter.StationItem, StationAdapter.StationViewHolder>(DiffCallback) {

    data class StationItem(
        val index: Int,
        val station: RadioStation,
        val selected: Boolean
    )

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StationViewHolder {
        val binding = ItemStationBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return StationViewHolder(binding, onStationClick)
    }

    override fun onBindViewHolder(holder: StationViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class StationViewHolder(
        private val binding: ItemStationBinding,
        private val onStationClick: (RadioStation) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: StationItem) {
            val station = item.station
            val context = binding.root.context
            binding.stationNumber.text = context.getString(R.string.station_number_format, item.index)
            binding.stationFreq.text = RadioProtocol.formatFrequency(station.frequency, station.band)

            if (station.name.isNotBlank() &&
                station.name != RadioProtocol.formatFrequency(station.frequency, station.band)
            ) {
                binding.stationName.visibility = View.VISIBLE
                binding.stationName.text = station.name
            } else {
                binding.stationName.visibility = View.GONE
            }

            val cardColor = if (item.selected) {
                ContextCompat.getColor(context, R.color.selected_station)
            } else {
                ContextCompat.getColor(context, R.color.surface)
            }
            binding.stationCard.setCardBackgroundColor(cardColor)
            binding.root.setOnClickListener { onStationClick(station) }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<StationItem>() {
        override fun areItemsTheSame(oldItem: StationItem, newItem: StationItem): Boolean {
            return oldItem.station.cid == newItem.station.cid &&
                oldItem.station.sid == newItem.station.sid &&
                oldItem.station.band == newItem.station.band
        }

        override fun areContentsTheSame(oldItem: StationItem, newItem: StationItem): Boolean {
            return oldItem == newItem
        }
    }
}
