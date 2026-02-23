package com.andromapper.ui.map

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.andromapper.R
import com.andromapper.data.local.entity.LayerType
import com.andromapper.data.remote.NetworkClient
import com.andromapper.databinding.ActivityMapBinding
import com.andromapper.map.GeoJsonOverlayManager
import com.andromapper.map.MbTilesTileSource
import com.andromapper.map.ServerTileSource
import com.andromapper.ui.download.OfflineDownloadActivity
import com.andromapper.ui.layers.LayerManagerActivity
import com.andromapper.ui.settings.SettingsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.MapPosition
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.layer.renderer.TileRendererLayer
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.InternalRenderTheme
import java.io.File

class MapActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMapBinding
    private val viewModel: MapViewModel by viewModels()
    private val activityScope = CoroutineScope(Dispatchers.Main)

    private val geoJsonManager = GeoJsonOverlayManager()
    private val openMbTilesSources = mutableListOf<MbTilesTileSource>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMapBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        setupMap()
        observeViewModel()

        // Load server URL from prefs and refresh layers
        loadServerConfig()
        viewModel.refreshLayers()
    }

    private fun setupMap() {
        AndroidGraphicFactory.createInstance(application)

        val mapView = binding.mapView
        mapView.isClickable = true
        mapView.mapScaleBar.isVisible = true
        mapView.setBuiltInZoomControls(true)
        mapView.mapZoomControls.isShowMapZoomControls = true
        mapView.model.mapViewPosition.mapPosition =
            MapPosition(LatLong(37.7749, -122.4194), 10.toByte()) // Default: San Francisco

        // Base map (offline .map file)
        val mapFile = File(getExternalFilesDir(null), "maps/base.map")
        if (mapFile.exists()) {
            val tileCache = AndroidUtil.createTileCache(
                this,
                "base_tiles",
                mapView.model.displayModel.tileSize,
                1f,
                mapView.model.frameBufferModel.overdrawFactor
            )
            val tileRendererLayer = TileRendererLayer(
                tileCache,
                MapFile(mapFile),
                mapView.model.mapViewPosition,
                AndroidGraphicFactory.INSTANCE
            )
            tileRendererLayer.setXmlRenderTheme(InternalRenderTheme.DEFAULT)
            mapView.layerManager.layers.add(tileRendererLayer)
        } else {
            binding.tvNoBaseMap.visibility = View.VISIBLE
        }
    }

    private fun observeViewModel() {
        viewModel.isOnline.observe(this) { online ->
            binding.tvOfflineIndicator.visibility = if (online) View.GONE else View.VISIBLE
        }

        viewModel.layers.observe(this) { layers ->
            // For raster layers, add server tile overlays
            // For vector layers, load GeoJSON overlays
            layers.filter { it.isEnabled }.forEach { layer ->
                when (layer.type) {
                    LayerType.RASTER -> addRasterOverlay(layer.id)
                    LayerType.VECTOR -> loadVectorOverlay(layer.id)
                }
            }
        }

        viewModel.errorMessage.observe(this) { msg ->
            if (!msg.isNullOrBlank()) {
                com.google.android.material.snackbar.Snackbar
                    .make(binding.root, msg, com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                    .show()
                viewModel.clearError()
            }
        }
    }

    private fun addRasterOverlay(layerId: Int) {
        val prefs = getSharedPreferences("andromapper_prefs", MODE_PRIVATE)
        val baseUrl = prefs.getString("server_url", "") ?: return
        if (baseUrl.isBlank()) return

        val serverSource = ServerTileSource(
            layerId = layerId,
            serverBaseUrl = baseUrl,
            context = this,
            httpClient = NetworkClient.getOkHttpClient()
        )

        // The actual tile layer rendering is wired via the map view's tile request mechanism.
        // For a production app, integrate ServerTileSource into a custom TileLayer.
        // Here we log readiness for the overlay.
        android.util.Log.d("MapActivity", "Raster overlay ready for layer $layerId")
    }

    private fun loadVectorOverlay(layerId: Int) {
        activityScope.launch(Dispatchers.IO) {
            try {
                val body = NetworkClient.apiService.getGeoJson(layerId)
                val geojson = body.string()
                val overlayLayers = geoJsonManager.parse(geojson)
                withContext(Dispatchers.Main) {
                    overlayLayers.forEach { binding.mapView.layerManager.layers.add(it) }
                    binding.mapView.layerManager.redrawLayers()
                }
            } catch (e: Exception) {
                android.util.Log.w("MapActivity", "Failed to load GeoJSON for layer $layerId", e)
            }
        }
    }

    private fun loadServerConfig() {
        val prefs = getSharedPreferences("andromapper_prefs", MODE_PRIVATE)
        val url = prefs.getString("server_url", "") ?: ""
        if (url.isNotBlank()) {
            NetworkClient.configure(url)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.map_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_layers   -> { startActivity(Intent(this, LayerManagerActivity::class.java)); true }
            R.id.action_download -> { startActivity(Intent(this, OfflineDownloadActivity::class.java)); true }
            R.id.action_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        openMbTilesSources.forEach { it.close() }
        binding.mapView.destroyAll()
        AndroidGraphicFactory.clearResourceMemoryCache()
    }
}
