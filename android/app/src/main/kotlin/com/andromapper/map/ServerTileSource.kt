package com.andromapper.map

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Provides tile data from the server with local file cache fallback.
 *
 * Priority:
 * 1. Local tile file cache
 * 2. Network request (if online)
 * 3. Returns null (transparent tile shown by layer)
 */
class ServerTileSource(
    private val layerId: Int,
    private val serverBaseUrl: String,
    private val context: Context,
    private val httpClient: OkHttpClient
) {

    private val cacheDir: File by lazy {
        File(context.getExternalFilesDir(null), "tiles/$layerId").apply { mkdirs() }
    }

    /**
     * Fetches tile bytes. Returns null if unavailable.
     */
    fun getTileBytes(zoom: Int, x: Int, y: Int): ByteArray? {
        // 1. Check local cache
        val cachedFile = getTileCacheFile(zoom, x, y)
        if (cachedFile.exists()) {
            return cachedFile.readBytes()
        }

        // 2. Fetch from network
        return fetchFromNetwork(zoom, x, y, cachedFile)
    }

    private fun getTileCacheFile(zoom: Int, x: Int, y: Int): File {
        val dir = File(cacheDir, "$zoom/$x")
        dir.mkdirs()
        return File(dir, "$y.png")
    }

    private fun fetchFromNetwork(zoom: Int, x: Int, y: Int, cacheFile: File): ByteArray? {
        // Sanitize inputs to prevent path traversal
        if (zoom < 0 || zoom > 22 || x < 0 || y < 0) return null
        val maxTile = (1 shl zoom) - 1
        if (x > maxTile || y > maxTile) return null

        val url = buildTileUrl(zoom, x, y)
        val request = Request.Builder().url(url).build()

        return try {
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                return null
            }
            val bytes = response.body?.bytes() ?: return null
            // Validate it's a PNG
            if (!isPng(bytes)) return null
            // Cache to disk
            cacheFile.writeBytes(bytes)
            bytes
        } catch (e: IOException) {
            Log.w("ServerTileSource", "Tile fetch failed z=$zoom x=$x y=$y", e)
            null
        }
    }

    private fun buildTileUrl(zoom: Int, x: Int, y: Int): String {
        val base = serverBaseUrl.trimEnd('/')
        return "$base/tiles/$layerId/$zoom/$x/$y.png"
    }

    private fun isPng(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        val signature = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
        return bytes.take(8).toByteArray().contentEquals(signature)
    }
}
