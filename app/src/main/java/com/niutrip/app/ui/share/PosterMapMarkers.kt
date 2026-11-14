package com.niutrip.app.ui.share

import com.niutrip.app.core.PointLite
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class PosterEndpointNames(val start: String? = null, val end: String? = null)

/** Screen coordinates are in density-independent units, after the camera has settled. */
data class PosterMarkerCandidate(val point: PointLite, val x: Float, val y: Float, val order: Int)
data class PosterMarkerBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun overlaps(other: PosterMarkerBox): Boolean =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top
    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom

    fun intersectsSegment(a: Pair<Float, Float>, b: Pair<Float, Float>): Boolean {
        var lower = 0f; var upper = 1f
        val dx = b.first - a.first; val dy = b.second - a.second
        fun clip(p: Float, q: Float): Boolean {
            if (p == 0f) return q >= 0f
            val ratio = q / p
            if (p < 0) lower = max(lower, ratio) else upper = min(upper, ratio)
            return lower <= upper
        }
        return clip(-dx, a.first - left) && clip(dx, right - a.first) &&
            clip(-dy, a.second - top) && clip(dy, bottom - a.second)
    }
}

object PosterMapMarkers {
    fun endpointName(point: PointLite, district: String?): String? =
        point.name?.trim()?.takeIf { it.isNotEmpty() }
            ?: district?.trim()?.takeIf { it.isNotEmpty() }

    /** Prefer authored content, then spread markers across the visible route. */
    fun select(
        candidates: List<PosterMarkerCandidate>,
        reserved: List<PosterMarkerBox>,
        route: List<Pair<Float, Float>>,
        width: Float,
        height: Float,
    ): List<PosterMarkerCandidate> {
        val budget = (width * height / 18_000f).toInt().coerceIn(2, 12)
        val occupied = reserved.toMutableList()
        val selected = mutableListOf<PosterMarkerCandidate>()
        val segments = route.zipWithNext()
        val remaining = candidates.distinctBy { it.point.id }.filter { candidate ->
            val box = box(candidate)
            box.left >= 8 && box.top >= 8 && box.right <= width - 8 && box.bottom <= height - 30 &&
                occupied.none { it.overlaps(box) } && route.none { (x, y) ->
                    hypot(x - candidate.x, y - candidate.y) > 22f && box.contains(x, y)
                } && segments.none { (a, b) ->
                    val dx = b.first - a.first; val dy = b.second - a.second
                    val lengthSquared = dx * dx + dy * dy
                    val t = if (lengthSquared == 0f) 0f else
                        (((candidate.x - a.first) * dx + (candidate.y - a.second) * dy) / lengthSquared).coerceIn(0f, 1f)
                    val distance = hypot(candidate.x - a.first - t * dx, candidate.y - a.second - t * dy)
                    distance > 22f && box.intersectsSegment(a, b)
                }
        }.toMutableList()
        while (remaining.isNotEmpty() && selected.size < budget) {
            val next = remaining.maxWithOrNull(compareBy<PosterMarkerCandidate> { candidate ->
                val importance = (if (candidate.point.images.isNotEmpty()) 4 else 0) +
                    (if (!candidate.point.name.isNullOrBlank()) 2 else 0) +
                    (if (!candidate.point.desc.isNullOrBlank()) 1 else 0)
                val spread = selected.minOfOrNull { hypot(it.x - candidate.x, it.y - candidate.y) }
                    ?: max(width, height)
                importance * 1000f + spread
            }.thenBy { -it.order }) ?: break
            selected.add(next)
            occupied.add(box(next))
            remaining.removeAll { candidate ->
                occupied.any { it.overlaps(box(candidate)) } ||
                    hypot(candidate.x - next.x, candidate.y - next.y) < 58f
            }
        }
        return selected.sortedBy { it.order }
    }

    private fun box(point: PosterMarkerCandidate) =
        PosterMarkerBox(point.x - 17f, point.y - 40f, point.x + 17f, point.y + 5f)
}
