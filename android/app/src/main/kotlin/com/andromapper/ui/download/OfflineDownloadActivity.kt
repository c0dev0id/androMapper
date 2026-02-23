package com.andromapper.ui.download

import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.andromapper.databinding.ActivityOfflineDownloadBinding

class OfflineDownloadActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOfflineDownloadBinding
    private val viewModel: OfflineDownloadViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOfflineDownloadBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.btnDownload.setOnClickListener {
            val layerIdText = binding.etLayerId.text.toString().trim()
            val minZoom = binding.etMinZoom.text.toString().toIntOrNull() ?: 8
            val maxZoom = binding.etMaxZoom.text.toString().toIntOrNull() ?: 14
            val bbox    = binding.etBbox.text.toString().trim()

            val layerId = layerIdText.toIntOrNull()
            if (layerId == null || bbox.isBlank()) {
                binding.tvStatus.text = "Please enter a valid layer ID and bounding box."
                return@setOnClickListener
            }

            viewModel.requestDownload(layerId, minZoom, maxZoom, bbox)
        }

        viewModel.status.observe(this) { msg ->
            if (!msg.isNullOrBlank()) {
                binding.tvStatus.text = msg
            }
        }

        viewModel.isLoading.observe(this) { loading ->
            binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
