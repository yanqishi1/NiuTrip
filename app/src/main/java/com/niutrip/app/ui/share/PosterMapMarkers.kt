package com.niutrip.app.ui.share

import com.niutrip.app.core.PointLite
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.ln

data class PosterEndpointNames(val start: String? = null, val end: String? = null)

/** Screen coordinates are in density-independent units, after the camera has settled. */
data class PosterMarkerCandidate(val point: PointLite, val x: Float, val y: Float, val order: Int)
data class PosterMarkerBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    fun expanded(padding: Float) = PosterMarkerBox(left - padding, top - padding, right + padding, bottom + padding)
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

data class PosterNamedMarker(val candidate: PosterMarkerCandidate, val name: String, val box: PosterMarkerBox)

object PosterMapMarkers {
    fun endpointLabelBox(
        start: Pair<Float, Float>, end: Pair<Float, Float>, labelWidth: Float, labelHeight: Float,
        safe: PosterMarkerBox, route: List<Pair<Float, Float>>,
        occupied: List<PosterMarkerBox> = emptyList(),
    ): PosterMarkerBox {
        val x = (start.first + end.first) / 2
        val y = (start.second + end.second) / 2
        val gap = max(14f, hypot(start.first - end.first, start.second - end.second) / 2 + 8)
        val w = labelWidth; val h = labelHeight
        val options = listOf(
            PosterMarkerBox(x - w / 2, y - gap - h, x + w / 2, y - gap),
            PosterMarkerBox(x - w / 2, y + gap, x + w / 2, y + gap + h),
            PosterMarkerBox(x + gap, y - h / 2, x + gap + w, y + h / 2),
            PosterMarkerBox(x - gap - w, y - h / 2, x - gap, y + h / 2),
            PosterMarkerBox(x + gap, y - gap - h, x + gap + w, y - gap),
            PosterMarkerBox(x - gap - w, y - gap - h, x - gap, y - gap),
            PosterMarkerBox(x + gap, y + gap, x + gap + w, y + gap + h),
            PosterMarkerBox(x - gap - w, y + gap, x - gap, y + gap + h),
        )
        val fitting = options.filter { it.left >= safe.left && it.top >= safe.top && it.right <= safe.right && it.bottom <= safe.bottom }
        val segments = route.zipWithNext()
        return fitting.minByOrNull { box ->
            val padded = box.expanded(3f)
            occupied.count { padded.overlaps(it) } * 10000 +
                segments.count { padded.intersectsSegment(it.first, it.second) } + route.count { padded.contains(it.first, it.second) }
        } ?: options[0]
    }

    fun checkinName(point: PointLite): String = point.name?.trim()?.takeIf(String::isNotEmpty)
        ?: point.desc?.lineSequence()?.map(String::trim)?.firstOrNull(String::isNotEmpty)
        ?: "未命名打卡点"

    /** Bounded content scores let a sparse stop compete with a photo-rich cluster. */
    fun contentWeight(point: PointLite): Float {
        val photos = point.images.filter(String::isNotBlank).distinct().size.coerceAtMost(12)
        val descriptionLength = point.desc.orEmpty().count { !it.isWhitespace() }.coerceAtMost(120)
        return (2 * ln(1.0 + photos) / ln(13.0)).toFloat() +
            descriptionLength / 120f * 1.5f + if (point.name.isNullOrBlank()) 0f else .35f
    }

    /** Place the measured name label around its real anchor, never on a route segment. */
    fun layoutNames(
        candidates: List<PosterMarkerCandidate>, reserved: List<PosterMarkerBox>,
        route: List<Pair<Float, Float>>, width: Float, height: Float,
        preferredNames: Map<String, String> = emptyMap(),
        measureWidth: (String) -> Float,
    ): List<PosterNamedMarker> {
        val unique = candidates.distinctBy { it.point.id }.filter {
            it.x in 8f..(width - 8) && it.y in 8f..(height - 30)
        }
        val segments = route.zipWithNext()
        val occupied = reserved.toMutableList()
        val budget = max((width * height / 6_500f).toInt().coerceIn(4, 24), preferredNames.size.coerceAtMost(24))
        val names = unique.associateWith { preferredNames[it.point.id]?.trim()?.takeIf(String::isNotEmpty) ?: checkinName(it.point) }
        val options = unique.associateWith { candidate ->
            val w = measureWidth(names.getValue(candidate)).coerceIn(48f, 132f)
            val h = 26f
            val x = candidate.x; val y = candidate.y
            // Dense scenic areas need labels outside the local route cluster, connected by a leader.
            val gaps = if (candidate.point.id in preferredNames) listOf(12f, 24f, 40f, 64f, 96f, 136f)
                else listOf(12f, 24f, 40f)
            val boxes = gaps.flatMap { gap -> listOf(
                PosterMarkerBox(x - w / 2, y - gap - h, x + w / 2, y - gap),
                PosterMarkerBox(x + gap, y - h / 2, x + gap + w, y + h / 2),
                PosterMarkerBox(x - gap - w, y - h / 2, x - gap, y + h / 2),
                PosterMarkerBox(x - w / 2, y + gap, x + w / 2, y + gap + h),
                PosterMarkerBox(x + gap, y - gap - h, x + gap + w, y - gap),
                PosterMarkerBox(x - gap - w, y - gap - h, x - gap, y - gap),
                PosterMarkerBox(x + gap, y + gap, x + gap + w, y + gap + h),
                PosterMarkerBox(x - gap - w, y + gap, x - gap, y + gap + h),
            ) }
            boxes.filter { box ->
                val padded = box.expanded(3f)
                box.left >= 8 && box.top >= 8 && box.right <= width - 8 && box.bottom <= height - 30 &&
                    route.none { padded.contains(it.first, it.second) } &&
                    segments.none { padded.intersectsSegment(it.first, it.second) } &&
                    unique.none { it != candidate && padded.contains(it.x, it.y) }
            }
        }
        val isolation = unique.associateWith { candidate ->
            (unique.filter { it != candidate }.minOfOrNull { hypot(it.x - candidate.x, it.y - candidate.y) }
                ?: 120f).coerceAtMost(120f) / 120f
        }
        val remaining = unique.toMutableList()
        val selected = mutableListOf<PosterNamedMarker>()
        while (remaining.isNotEmpty() && selected.size < budget) {
            fun score(candidate: PosterMarkerCandidate): Float {
                val coverage = (selected.minOfOrNull {
                    hypot(it.candidate.x - candidate.x, it.candidate.y - candidate.y)
                } ?: 120f).coerceAtMost(120f) / 120f
                return (if (candidate.point.id in preferredNames) 100f else 0f) +
                    contentWeight(candidate.point) + isolation.getValue(candidate) * 1.2f + coverage * 2f
            }
            fun available(candidate: PosterMarkerCandidate) = options.getValue(candidate).filter { option ->
                val tip = candidate.x.coerceIn(option.left + 4, option.right - 4) to
                    candidate.y.coerceIn(option.top + 4, option.bottom - 4)
                occupied.none { it.overlaps(option.expanded(4f)) || it.intersectsSegment(candidate.x to candidate.y, tip) }
            }
            // Give constrained priority stops their scarce label space before more flexible stops.
            val preferred = remaining.filter { it.point.id in preferredNames }.associateWith(::available)
            val next = if (preferred.isNotEmpty()) preferred.keys.minWithOrNull(
                compareBy<PosterMarkerCandidate> { preferred.getValue(it).size }.thenByDescending { score(it) }.thenBy { it.order })
            else remaining.maxWithOrNull(compareBy<PosterMarkerCandidate> { score(it) }.thenBy { -it.order })
            if (next == null) break
            remaining.remove(next)
            if (next.point.id !in preferredNames && selected.any { hypot(it.candidate.x - next.x, it.candidate.y - next.y) < 28f }) continue
            val anchor = PosterMarkerBox(next.x - 4, next.y - 4, next.x + 4, next.y + 4)
            if (occupied.any { it.overlaps(anchor) }) continue
            val box = (preferred[next] ?: available(next)).firstOrNull() ?: continue
            selected.add(PosterNamedMarker(next, names.getValue(next), box))
            occupied.add(box.expanded(4f)); occupied.add(anchor)
        }
        return selected.sortedBy { it.candidate.order }
    }

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
