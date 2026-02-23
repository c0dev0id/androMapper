package com.andromapper.map

import org.mapsforge.core.model.Tile
import org.mapsforge.map.layer.download.tilesource.AbstractTileSource
import java.net.MalformedURLException
import java.net.URL

/**
 * Mapsforge [AbstractTileSource] that serves tiles from the geospatial normalization server.
 *
 * Used with Mapsforge's [org.mapsforge.map.layer.download.TileDownloadLayer].
 * Tile URL pattern: {baseUrl}/tiles/{layerId}/{z}/{x}/{y}.png
 *
 * Mapsforge's built-in [org.mapsforge.map.layer.cache.FileSystemTileCache] provides
 * on-disk caching; no additional caching is needed at this layer.
 */
class MapforgeTileSource(
    private val layerId: Int,
    private val serverBaseUrl: String
) : AbstractTileSource(extractHosts(serverBaseUrl), extractPort(serverBaseUrl)) {

    override fun getTileUrl(tile: Tile): URL {
        val base = serverBaseUrl.trimEnd('/')
        return URL("$base/tiles/$layerId/${tile.zoomLevel}/${tile.tileX}/${tile.tileY}.png")
    }

    override fun getDefaultTimeToLive(): Int = 86_400   // 24 hours

    override fun getMaximumCachedTiles(): Int = 2_000

    override fun hasAlpha(): Boolean = true

    /** Override to ensure HTTPS is used. */
    override fun getProtocol(): String = if (serverBaseUrl.startsWith("https")) "https" else "http"

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
