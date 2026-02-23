package com.andromapper.data.repository

import android.content.Context
import android.util.Log
import com.andromapper.data.local.AppDatabase
import com.andromapper.data.local.entity.OfflinePackageEntity
import com.andromapper.data.local.entity.OfflinePackageStatus
import com.andromapper.data.remote.NetworkClient
import com.andromapper.data.remote.model.CreateOfflinePackageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import java.io.File

private const val MAX_POLL_ATTEMPTS = 120 // 10 minutes at 5-second intervals

class OfflinePackageRepository(
    private val db: AppDatabase,
    private val context: Context
) {

    fun observePackages(): Flow<List<OfflinePackageEntity>> =
        db.offlinePackageDao().observeAll()

    /**
     * Request a new offline package from the server.
     * Polls for completion and downloads the MBTiles file.
     */
    suspend fun requestOfflinePackage(
        layerId: Int,
        minZoom: Int,
        maxZoom: Int,
        bbox: String
    ): Result<OfflinePackageEntity> = runCatching {
        val request = CreateOfflinePackageRequest(layerId, minZoom, maxZoom, bbox)
        val response = NetworkClient.apiService.createOfflinePackage(request)

        // Create local record with PENDING status
        val entity = OfflinePackageEntity(
            id = response.packageId,
            layerId = layerId,
            minZoom = minZoom,
            maxZoom = maxZoom,
            bbox = bbox,
            status = OfflinePackageStatus.PENDING
        )
        db.offlinePackageDao().insert(entity)

        // Poll until ready
        val downloaded = pollAndDownload(response.packageId, layerId)
        downloaded
    }.onFailure { Log.e("OfflinePackageRepository", "Failed to request package", it) }

    /**
     * Poll server until package is ready, then download the MBTiles file.
     */
    private suspend fun pollAndDownload(packageId: Int, layerId: Int): OfflinePackageEntity {
        // Update to DOWNLOADING status
        db.offlinePackageDao().getById(packageId)?.let { pkg ->
            db.offlinePackageDao().update(pkg.copy(status = OfflinePackageStatus.DOWNLOADING))
        }

        // Poll every 5 seconds, up to 10 minutes
        repeat(MAX_POLL_ATTEMPTS) {
            val status = NetworkClient.apiService.getOfflinePackageStatus(packageId)
            if (status.status == "ready") {
                return downloadMbTiles(packageId, layerId)
            }
            delay(5_000)
        }
        throw IllegalStateException("Timed out waiting for offline package $packageId")
    }

    private suspend fun downloadMbTiles(packageId: Int, layerId: Int): OfflinePackageEntity {
        val offlineDir = File(context.getExternalFilesDir(null), "offline").apply { mkdirs() }
        val destFile = File(offlineDir, "layer_${layerId}_pkg_${packageId}.mbtiles")

        val response = NetworkClient.apiService.downloadOfflinePackage(packageId)
        val body = response.body() ?: throw IllegalStateException("Empty response body")

        body.byteStream().use { input ->
            destFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        val pkg = db.offlinePackageDao().getById(packageId)
            ?: throw IllegalStateException("Package record not found")

        val updated = pkg.copy(
            localPath = destFile.absolutePath,
            status = OfflinePackageStatus.READY
        )
        db.offlinePackageDao().update(updated)
        return updated
    }

    suspend fun deletePackage(packageId: Int) {
        db.offlinePackageDao().getById(packageId)?.let { pkg ->
            if (pkg.localPath.isNotBlank()) {
                File(pkg.localPath).delete()
            }
            db.offlinePackageDao().deleteById(packageId)
        }
    }
}
