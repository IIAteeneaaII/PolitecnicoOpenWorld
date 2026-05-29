package ovh.gabrielhuav.pow.features.map_exterior.viewmodel

import org.osmdroid.util.GeoPoint
import ovh.gabrielhuav.pow.domain.models.MapWay
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

internal class RoadNetworkIndex {

    private data class Seg(
        val s: GeoPoint, val e: GeoPoint,
        val minLat: Double, val maxLat: Double,
        val minLon: Double, val maxLon: Double
    )

    private val CELL = 0.0025
    private val NODE_GRID_SIZE = 0.001

    private var indexedRef: List<MapWay>? = null
    private var segs: List<Seg> = emptyList()
    private var segGrid: Map<Long, List<Seg>> = emptyMap()
    private var nodeGrid: Map<Pair<Int, Int>, List<GeoPoint>> = emptyMap()

    fun rebuild(network: List<MapWay>) {
        rebuildSegIndex(network)
        rebuildNodeGrid(network)
        indexedRef = network
    }

    fun getNearestPoint(t: GeoPoint, network: List<MapWay>): GeoPoint {
        ensureIndex(network)
        val cands = candidates(t)
        if (cands.isEmpty()) return t
        var best = Double.MAX_VALUE
        var pt = t
        for (seg in cands) {
            val p = project(t, seg.s, seg.e)
            val d = distance(t, p)
            if (d < best) { best = d; pt = p }
        }
        return pt
    }

    fun calculateRoute(from: GeoPoint, to: GeoPoint, network: List<MapWay>): List<GeoPoint> {
        if (network.isEmpty()) return listOf(from, to)
        ensureIndex(network)
        val route = mutableListOf(from)
        val startPoint = getNearestPoint(from, network)
        val endPoint = getNearestPoint(to, network)
        var current = startPoint
        val visited = mutableSetOf<String>()
        for (i in 0 until 20) {
            val distToTarget = distance(current, endPoint)
            if (distToTarget < 0.0005) break
            var bestNext: GeoPoint? = null
            var bestDist = distToTarget
            for (nodePt in nearbyNodes(current)) {
                val nodeKey = "${nodePt.latitude},${nodePt.longitude}"
                if (visited.contains(nodeKey)) continue
                if (distance(current, nodePt) < 0.003) {
                    val d = distance(nodePt, endPoint)
                    if (d < bestDist) { bestDist = d; bestNext = nodePt }
                }
            }
            if (bestNext != null) {
                current = bestNext
                visited.add("${current.latitude},${current.longitude}")
                route.add(current)
            } else break
        }
        route.add(endPoint)
        route.add(to)
        return route.distinctBy { "${it.latitude},${it.longitude}" }
    }

    fun distance(a: GeoPoint, b: GeoPoint): Double =
        sqrt((a.latitude - b.latitude).pow(2) + (a.longitude - b.longitude).pow(2))

    private fun ensureIndex(network: List<MapWay>) {
        if (indexedRef === network) return
        rebuild(network)
    }

    private fun rebuildSegIndex(network: List<MapWay>) {
        val newSegs = ArrayList<Seg>(network.sumOf { it.nodes.size })
        val newGrid = HashMap<Long, MutableList<Seg>>()
        for (way in network) {
            for (i in 0 until way.nodes.size - 1) {
                val a = way.nodes[i]; val b = way.nodes[i + 1]
                val seg = Seg(
                    GeoPoint(a.lat, a.lon), GeoPoint(b.lat, b.lon),
                    min(a.lat, b.lat), max(a.lat, b.lat),
                    min(a.lon, b.lon), max(a.lon, b.lon)
                )
                newSegs.add(seg)
                for (r in cell(seg.minLat)..cell(seg.maxLat))
                    for (c in cell(seg.minLon)..cell(seg.maxLon))
                        newGrid.getOrPut(pack(r, c)) { mutableListOf() }.add(seg)
            }
        }
        segs = newSegs
        segGrid = newGrid
    }

    private fun rebuildNodeGrid(network: List<MapWay>) {
        val uniqueNodes = linkedMapOf<String, GeoPoint>()
        network.forEach { way ->
            way.nodes.forEach { node ->
                val key = "${node.lat},${node.lon}"
                if (!uniqueNodes.containsKey(key)) uniqueNodes[key] = GeoPoint(node.lat, node.lon)
            }
        }
        nodeGrid = uniqueNodes.values.groupBy { point ->
            val latCell = floor(point.latitude / NODE_GRID_SIZE).toInt()
            val lonCell = floor(point.longitude / NODE_GRID_SIZE).toInt()
            latCell to lonCell
        }
    }

    private fun nearbyNodes(point: GeoPoint): List<GeoPoint> {
        if (nodeGrid.isEmpty()) return emptyList()
        val latCell = floor(point.latitude / NODE_GRID_SIZE).toInt()
        val lonCell = floor(point.longitude / NODE_GRID_SIZE).toInt()
        val nearby = mutableListOf<GeoPoint>()
        for (dLat in -1..1)
            for (dLon in -1..1)
                nodeGrid[(latCell + dLat) to (lonCell + dLon)]?.let { nearby.addAll(it) }
        return if (nearby.isNotEmpty()) nearby else nodeGrid.values.flatten()
    }

    private fun candidates(loc: GeoPoint): List<Seg> {
        val r = cell(loc.latitude); val c = cell(loc.longitude)
        val res = LinkedHashSet<Seg>()
        for (dr in -1..1) for (dc in -1..1) segGrid[pack(r + dr, c + dc)]?.let { res.addAll(it) }
        return if (res.isNotEmpty()) res.toList() else segs
    }

    private fun pack(r: Int, c: Int): Long = r.toLong() * 1_000_003L + c.toLong()
    private fun cell(v: Double): Int = floor(v / CELL).toInt()

    private fun project(p: GeoPoint, v: GeoPoint, w: GeoPoint): GeoPoint {
        val l2 = (w.latitude - v.latitude).pow(2) + (w.longitude - v.longitude).pow(2)
        if (l2 == 0.0) return v
        val t = max(0.0, min(1.0,
            ((p.latitude - v.latitude) * (w.latitude - v.latitude) +
             (p.longitude - v.longitude) * (w.longitude - v.longitude)) / l2))
        return GeoPoint(
            v.latitude + t * (w.latitude - v.latitude),
            v.longitude + t * (w.longitude - v.longitude)
        )
    }
}