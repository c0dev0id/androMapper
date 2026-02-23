package com.andromapper.map

import android.graphics.Color
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.layer.Layer
import org.mapsforge.map.layer.overlay.Marker
import org.mapsforge.map.layer.overlay.Polygon
import org.mapsforge.map.layer.overlay.Polyline

/**
 * Parses GeoJSON and creates Mapsforge overlay layers.
 *
 * Supports:
 * - Point → Marker
 * - LineString / MultiLineString → Polyline
 * - Polygon / MultiPolygon → Polygon
 * - GeometryCollection / Feature / FeatureCollection
 *
 * Call [parse] on a background thread; it returns a list of Mapsforge
 * [Layer] objects that can be added to the map on the main thread.
 */
class GeoJsonOverlayManager(
    private val lineColor: Int = Color.argb(200, 0, 120, 255),
    private val lineWidth: Float = 3f,
    private val fillColor: Int = Color.argb(80, 0, 120, 255),
    private val maxFeatures: Int = 10_000
) {

    private val gson = Gson()

    /**
     * Parse GeoJSON string and return Mapsforge layers.
     * Must be called off the main thread.
     *
     * @param geojson Raw GeoJSON string
     * @param viewportBbox Optional [minLon, minLat, maxLon, maxLat] to clip features
     */
    fun parse(geojson: String, viewportBbox: DoubleArray? = null): List<Layer> {
        return try {
            val root = gson.fromJson(geojson, JsonObject::class.java)
            val layers = mutableListOf<Layer>()
            parseJsonObject(root, layers, viewportBbox, 0)
            layers
        } catch (e: JsonSyntaxException) {
            Log.e("GeoJsonOverlayManager", "Malformed GeoJSON, skipping", e)
            emptyList()
        }
    }

    private fun parseJsonObject(
        obj: JsonObject,
        out: MutableList<Layer>,
        bbox: DoubleArray?,
        depth: Int
    ) {
        if (out.size >= maxFeatures) return

        when (obj.get("type")?.asString) {
            "FeatureCollection" -> {
                val features = obj.getAsJsonArray("features") ?: return
                for (feature in features) {
                    if (feature.isJsonObject) {
                        parseJsonObject(feature.asJsonObject, out, bbox, depth + 1)
                    }
                    if (out.size >= maxFeatures) break
                }
            }
            "Feature" -> {
                val geometry = obj.getAsJsonObject("geometry") ?: return
                parseGeometry(geometry, out, bbox)
            }
            else -> parseGeometry(obj, out, bbox)
        }
    }

    private fun parseGeometry(
        geometry: JsonObject,
        out: MutableList<Layer>,
        bbox: DoubleArray?
    ) {
        val type = geometry.get("type")?.asString ?: return
        val coords = geometry.get("coordinates")

        when (type) {
            "Point" -> {
                if (coords?.isJsonArray == true) {
                    val ll = coordsToLatLong(coords.asJsonArray) ?: return
                    if (bbox != null && !inBbox(ll, bbox)) return
                    if (out.size >= maxFeatures) return
                    out.add(createMarker(ll))
                }
            }
            "LineString" -> {
                if (coords?.isJsonArray == true) {
                    val points = ringToLatLongs(coords.asJsonArray)
                    if (points.size < 2) return
                    if (bbox != null && !anyInBbox(points, bbox)) return
                    if (out.size >= maxFeatures) return
                    out.add(createPolyline(points))
                }
            }
            "MultiLineString" -> {
                if (coords?.isJsonArray == true) {
                    for (ring in coords.asJsonArray) {
                        if (out.size >= maxFeatures) return
                        if (ring.isJsonArray) {
                            val points = ringToLatLongs(ring.asJsonArray)
                            if (points.size < 2) continue
                            if (bbox != null && !anyInBbox(points, bbox)) continue
                            out.add(createPolyline(points))
                        }
                    }
                }
            }
            "Polygon" -> {
                if (coords?.isJsonArray == true) {
                    val outer = coords.asJsonArray.firstOrNull()?.asJsonArray ?: return
                    val points = ringToLatLongs(outer)
                    if (points.size < 3) return
                    if (bbox != null && !anyInBbox(points, bbox)) return
                    if (out.size >= maxFeatures) return
                    out.add(createPolygon(points))
                }
            }
            "MultiPolygon" -> {
                if (coords?.isJsonArray == true) {
                    for (polygon in coords.asJsonArray) {
                        if (out.size >= maxFeatures) return
                        if (polygon.isJsonArray) {
                            val outer = polygon.asJsonArray.firstOrNull()?.asJsonArray ?: continue
                            val points = ringToLatLongs(outer)
                            if (points.size < 3) continue
                            if (bbox != null && !anyInBbox(points, bbox)) continue
                            out.add(createPolygon(points))
                        }
                    }
                }
            }
            "GeometryCollection" -> {
                val geometries = geometry.getAsJsonArray("geometries") ?: return
                for (g in geometries) {
                    if (g.isJsonObject) parseGeometry(g.asJsonObject, out, bbox)
                    if (out.size >= maxFeatures) return
                }
            }
        }
    }

    // ---- Geometry helpers ----

    private fun coordsToLatLong(arr: JsonArray): LatLong? {
        return try {
            val lon = arr[0].asDouble
            val lat = arr[1].asDouble
            LatLong(lat, lon)
        } catch (e: Exception) { null }
    }

    private fun ringToLatLongs(arr: JsonArray): List<LatLong> {
        val result = mutableListOf<LatLong>()
        for (pt in arr) {
            if (pt.isJsonArray) {
                coordsToLatLong(pt.asJsonArray)?.let { result.add(it) }
            }
        }
        return result
    }

    private fun inBbox(ll: LatLong, bbox: DoubleArray): Boolean =
        ll.longitude >= bbox[0] && ll.longitude <= bbox[2] &&
        ll.latitude  >= bbox[1] && ll.latitude  <= bbox[3]

    private fun anyInBbox(points: List<LatLong>, bbox: DoubleArray): Boolean =
        points.any { inBbox(it, bbox) }

    // ---- Mapsforge overlay creators ----

    private fun createMarker(ll: LatLong): Marker {
        // Create a small colored circle bitmap to use as the marker icon
        val size = 16
        val bitmap = AndroidGraphicFactory.INSTANCE.createBitmap(size, size, true)
        val canvas = AndroidGraphicFactory.INSTANCE.createCanvas()
        canvas.setBitmap(bitmap)
        val paint = AndroidGraphicFactory.INSTANCE.createPaint().apply {
            color = lineColor
        }
        canvas.drawCircle(size / 2, size / 2, (size / 2 - 1).toFloat(), paint)
        return Marker(ll, bitmap, 0, 0)
    }

    private fun createPolyline(points: List<LatLong>): Polyline {
        val paint = AndroidGraphicFactory.INSTANCE.createPaint().apply {
            color = lineColor
            strokeWidth = lineWidth
        }
        val polyline = Polyline(paint, AndroidGraphicFactory.INSTANCE)
        polyline.latLongs.addAll(points)
        return polyline
    }

    private fun createPolygon(points: List<LatLong>): Polygon {
        val fillPaint = AndroidGraphicFactory.INSTANCE.createPaint().apply {
            color = fillColor
        }
        val strokePaint = AndroidGraphicFactory.INSTANCE.createPaint().apply {
            color = lineColor
            strokeWidth = lineWidth
        }
        val polygon = Polygon(fillPaint, strokePaint, AndroidGraphicFactory.INSTANCE)
        polygon.latLongs.addAll(points)
        return polygon
    }
}
