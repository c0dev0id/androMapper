package com.andromapper.ui.map

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.andromapper.data.local.AppDatabase
import com.andromapper.data.local.entity.LayerEntity
import com.andromapper.data.repository.LayerRepository
import com.andromapper.util.ConnectivityObserver
import kotlinx.coroutines.launch

class MapViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val layerRepository = LayerRepository(db)
    private val connectivityObserver = ConnectivityObserver(application)

    val layers: LiveData<List<LayerEntity>> =
        layerRepository.observeLayers().asLiveData()

    val isOnline: LiveData<Boolean> =
        connectivityObserver.isOnline.asLiveData()

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    // Last known map center / zoom for persistence
    private val _zoomLevel = MutableLiveData(10)
    val zoomLevel: LiveData<Int> = _zoomLevel

    fun refreshLayers() {
        viewModelScope.launch {
            layerRepository.refreshLayers()
                .onFailure { _errorMessage.value = "Could not refresh layers: ${it.message}" }
        }
    }

    fun setZoomLevel(zoom: Int) {
        _zoomLevel.value = zoom
    }

    fun clearError() {
        _errorMessage.value = null
    }
}
