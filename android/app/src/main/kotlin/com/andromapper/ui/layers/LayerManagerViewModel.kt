package com.andromapper.ui.layers

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.andromapper.data.local.AppDatabase
import com.andromapper.data.repository.LayerRepository
import kotlinx.coroutines.launch

class LayerManagerViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val repository = LayerRepository(db)

    val layers = repository.observeLayers().asLiveData()

    fun setLayerEnabled(layerId: Int, enabled: Boolean) {
        viewModelScope.launch {
            repository.setLayerEnabled(layerId, enabled)
        }
    }

    fun refresh() {
        viewModelScope.launch {
            repository.refreshLayers()
        }
    }
}
