package com.nospoon.vpn

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class VpnConfigAdapter(
    private var configs: List<VpnConfig>,
    private val listener: OnConfigClickListener
) : RecyclerView.Adapter<VpnConfigAdapter.ViewHolder>() {

    interface OnConfigClickListener {
        fun onConfigClick(config: VpnConfig)
        fun onConfigEdit(config: VpnConfig)
    }

    private var connectedConfigId: String? = null
    private var connectionStatus: String = "disconnected"

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val statusDot: View = view.findViewById(R.id.configStatusDot)
        val name: TextView = view.findViewById(R.id.configName)
        val serverKey: TextView = view.findViewById(R.id.configServerKey)
        val ip: TextView = view.findViewById(R.id.configIp)
        val connectionStatusText: TextView = view.findViewById(R.id.configConnectionStatus)
        val btnEdit: ImageButton = view.findViewById(R.id.btnEdit)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_vpn_config, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val config = configs[position]
        holder.name.text = config.displayName()
        holder.serverKey.text = config.displayServerKey()
        holder.ip.text = config.ip

        val isConnected = config.id == connectedConfigId
        when {
            isConnected && connectionStatus == "connected" -> {
                holder.statusDot.setBackgroundResource(R.drawable.circle_status_connected)
                holder.connectionStatusText.text = "Connected"
                holder.connectionStatusText.setTextColor(
                    holder.itemView.context.getColor(R.color.status_connected)
                )
            }
            isConnected && connectionStatus == "connecting" -> {
                holder.statusDot.setBackgroundResource(R.drawable.circle_status_connecting)
                holder.connectionStatusText.text = "Connecting..."
                holder.connectionStatusText.setTextColor(
                    holder.itemView.context.getColor(R.color.status_connecting)
                )
            }
            else -> {
                holder.statusDot.setBackgroundResource(R.drawable.circle_status_disconnected)
                holder.connectionStatusText.text = ""
            }
        }

        holder.itemView.setOnClickListener { listener.onConfigClick(config) }
        holder.itemView.setOnLongClickListener {
            listener.onConfigEdit(config)
            true
        }
        holder.btnEdit.setOnClickListener { listener.onConfigEdit(config) }
    }

    override fun getItemCount() = configs.size

    fun updateData(newConfigs: List<VpnConfig>) {
        configs = newConfigs
        notifyDataSetChanged()
    }

    fun setConnectionState(configId: String?, status: String) {
        connectedConfigId = configId
        connectionStatus = status
        notifyDataSetChanged()
    }
}
