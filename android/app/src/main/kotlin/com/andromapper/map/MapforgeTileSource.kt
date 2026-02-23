package com.andromapper.map

import org.mapsforge.map.layer.download.tilesource.OnlineTileSource
import java.net.MalformedURLException
import java.net.URL

/**
 * Mapsforge tile source that serves tiles from the geospatial normalization server.
 *
 * Used with Mapsforge's [org.mapsforge.map.layer.download.TileDownloadLayer].
 * Tile URL pattern: {baseUrl}/tiles/{layerId}/{z}/{x}/{y}.png
 */
class MapforgeTileSource(
    private val layerId: Int,
    private val serverBaseUrl: String
) : OnlineTileSource(extractHosts(serverBaseUrl), extractPort(serverBaseUrl)) {

    init {
        setName("Layer$layerId")
        setBaseUrl("/tiles/$layerId/")
        setExtension(".png")
        setParallelRequestsLimit(8)
        setProtocol(if (serverBaseUrl.startsWith("https")) "https" else "http")
        setTileSize(256)
        setZoomLevelMin(0.toByte())
        setZoomLevelMax(20.toByte())
        setAlpha(true)
    }

    companion object {
        private fun extractHosts(url: String): Array<String> {
            return try {
                arrayOf(URL(url).host)
            } catch (e: MalformedURLException) {
                arrayOf("localhost")
            }
        }

        private fun extractPort(url: String): Int {
            return try {
                val parsed = URL(url)
                when {
                    parsed.port != -1  -> parsed.port
                    parsed.protocol == "https" -> 443
                    else -> 80
                }
            } catch (e: MalformedURLException) {
                443
            }
        }
    }
}
