package com.andromapper.ui.download

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.andromapper.data.local.AppDatabase
import com.andromapper.data.local.entity.OfflinePackageEntity
import com.andromapper.data.repository.LayerRepository
import com.andromapper.data.repository.OfflinePackageRepository
import kotlinx.coroutines.launch

class OfflineDownloadViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val pkgRepo  = OfflinePackageRepository(db, application)
    private val layerRepo = LayerRepository(db)

    val packages: LiveData<List<OfflinePackageEntity>> =
        pkgRepo.observePackages().asLiveData()

    val layers = layerRepo.observeLayers().asLiveData()

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _status = MutableLiveData<String?>()
    val status: LiveData<String?> = _status

    fun requestDownload(layerId: Int, minZoom: Int, maxZoom: Int, bbox: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _status.value = "Requesting package…"
            pkgRepo.requestOfflinePackage(layerId, minZoom, maxZoom, bbox)
                .onSuccess {
                    _status.value = "Download complete: ${it.localPath}"
                    _isLoading.value = false
                }
                .onFailure {
                    _status.value = "Failed: ${it.message}"
                    _isLoading.value = false
                }
        }
    }

    fun deletePackage(packageId: Int) {
        viewModelScope.launch {
            pkgRepo.deletePackage(packageId)
        }
    }
}
