package com.andromapper.map

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.util.Log
import java.io.File

/**
 * Reads tile data from an MBTiles SQLite database.
 *
 * MBTiles uses TMS tile_row convention (y=0 at bottom).
 * XYZ convention (y=0 at top) must be converted:
 *     tms_row = (2^zoom - 1) - xyz_y
 */
class MbTilesTileSource(private val mbTilesPath: String) : AutoCloseable {

    private var database: SQLiteDatabase? = null

    fun open(): Boolean {
        if (!File(mbTilesPath).exists()) return false
        return try {
            database = SQLiteDatabase.openDatabase(
                mbTilesPath,
                null,
                SQLiteDatabase.OPEN_READONLY
            )
            true
        } catch (e: SQLiteException) {
            Log.e("MbTilesTileSource", "Failed to open MBTiles: $mbTilesPath", e)
            false
        }
    }

    /**
     * Returns raw tile bytes for the given XYZ coordinates, or null if not found.
     * Handles TMS y-axis flip internally.
     */
    fun getTileData(zoom: Int, x: Int, y: Int): ByteArray? {
        val db = database ?: return null

        // Convert XYZ y to TMS y
        val tmsY = (1 shl zoom) - 1 - y

        return try {
            val cursor = db.rawQuery(
                "SELECT tile_data FROM tiles WHERE zoom_level = ? AND tile_column = ? AND tile_row = ?",
                arrayOf(zoom.toString(), x.toString(), tmsY.toString())
            )
            cursor.use {
                if (it.moveToFirst()) it.getBlob(0) else null
            }
        } catch (e: SQLiteException) {
            Log.e("MbTilesTileSource", "Query failed z=$zoom x=$x y=$y", e)
            null
        }
    }

    fun isOpen(): Boolean = database?.isOpen == true

    override fun close() {
        database?.close()
        database = null
    }
}
