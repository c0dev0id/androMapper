package com.andromapper.ui.layers

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.andromapper.data.local.entity.LayerEntity
import com.andromapper.databinding.ActivityLayerManagerBinding
import com.andromapper.databinding.ItemLayerBinding

class LayerManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLayerManagerBinding
    private val viewModel: LayerManagerViewModel by viewModels()
    private lateinit var adapter: LayerAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLayerManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        adapter = LayerAdapter { layer, enabled ->
            viewModel.setLayerEnabled(layer.id, enabled)
        }

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        viewModel.layers.observe(this) { adapter.submitList(it) }
        viewModel.refresh()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

private val DIFF = object : DiffUtil.ItemCallback<LayerEntity>() {
    override fun areItemsTheSame(a: LayerEntity, b: LayerEntity) = a.id == b.id
    override fun areContentsTheSame(a: LayerEntity, b: LayerEntity) = a == b
}

class LayerAdapter(
    private val onToggle: (LayerEntity, Boolean) -> Unit
) : ListAdapter<LayerEntity, LayerAdapter.VH>(DIFF) {

    inner class VH(val binding: ItemLayerBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemLayerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val layer = getItem(position)
        holder.binding.tvLayerName.text = layer.name
        holder.binding.tvLayerType.text = layer.type.name.lowercase()
        holder.binding.switchEnabled.isChecked = layer.isEnabled
        holder.binding.switchEnabled.setOnCheckedChangeListener { _, checked ->
            onToggle(layer, checked)
        }
        holder.binding.tvOfflineBadge.visibility =
            if (layer.isOfflineAvailable) android.view.View.VISIBLE else android.view.View.GONE
    }
}
