package com.jupiterlabs.ghostdrive.tracking

import org.mapsforge.core.model.Tile
import org.mapsforge.core.util.MercatorProjection
import org.mapsforge.map.reader.MapFile
import java.io.File

/** Reads only local regional map data. Never submits coordinates to public geocoders. */
object OfflinePlaceLookup {
    private var cachedPath: String = ""
    private var cachedMtime: Long = -1L
    private var cachedMap: MapFile? = null

    @Synchronized
    private fun getMapFile(path: String): MapFile? {
        if (path.isBlank()) return null
        val file = File(path)
        if (!file.exists()) return null
        val mtime = file.lastModified()
        val existing = cachedMap
        if (existing != null && cachedPath == path && cachedMtime == mtime) {
            return existing
        }
        runCatching { existing?.close() }
        return runCatching {
            MapFile(file).also {
                cachedPath = path
                cachedMtime = mtime
                cachedMap = it
            }
        }.getOrNull()
    }

    @Synchronized
    fun near(f: Fix, path: String): String? {
        if (path.isBlank()) return null
        return runCatching {
            val map = getMapFile(path) ?: return null
            val zoom: Byte = 16
            val x = MercatorProjection.longitudeToTileX(f.longitude, zoom)
            val y = MercatorProjection.latitudeToTileY(f.latitude, zoom)
            val first = Tile((x - 1).coerceAtLeast(0), (y - 1).coerceAtLeast(0), zoom, 256)
            val last = Tile(x + 1, y + 1, zoom, 256)
            val data = map.readMapData(first, last) ?: return@runCatching null
            data class Candidate(val name: String, val distance: Double, val poi: Boolean)
            val candidates = mutableListOf<Candidate>()
            for (p in data.pois) {
                val name = p.tags.firstOrNull { it.key == "name" }?.value ?: continue
                val d = Geo.distance(f.latitude, f.longitude, p.position.latitude, p.position.longitude)
                if (d <= 200) candidates.add(Candidate(name, d, true))
            }
            for (w in data.ways) {
                val name = w.tags.firstOrNull { it.key == "name" }?.value ?: continue
                val d = w.latLongs.flatten().minOfOrNull { Geo.distance(f.latitude, f.longitude, it.latitude, it.longitude) } ?: continue
                if (d <= 200) candidates.add(Candidate(name, d, w.tags.any { it.key == "shop" || it.key == "amenity" || it.key == "building" }))
            }
            candidates.sortedWith(compareByDescending<Candidate> { it.poi }.thenBy { it.distance }).firstOrNull()?.let { "Near ${it.name}" }
        }.getOrNull()
    }
}
