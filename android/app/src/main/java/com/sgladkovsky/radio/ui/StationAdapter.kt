package com.sgladkovsky.radio.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.sgladkovsky.radio.databinding.ItemStationBinding
import com.sgladkovsky.radio.model.RadioStation
import com.sgladkovsky.radio.protocol.RadioProtocol

class StationAdapter(
    private val onStationClick: (RadioStation) -> Unit
) : ListAdapter<RadioStation, StationAdapter.StationViewHolder>(DiffCallback) {

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

        fun bind(station: RadioStation) {
            binding.stationName.text = station.name
            binding.stationFreq.text = RadioProtocol.formatFrequency(station.frequency, station.band)
            binding.root.setOnClickListener { onStationClick(station) }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<RadioStation>() {
        override fun areItemsTheSame(oldItem: RadioStation, newItem: RadioStation): Boolean {
            return oldItem.cid == newItem.cid && oldItem.sid == newItem.sid
        }

        override fun areContentsTheSame(oldItem: RadioStation, newItem: RadioStation): Boolean {
            return oldItem == newItem
        }
    }
}
