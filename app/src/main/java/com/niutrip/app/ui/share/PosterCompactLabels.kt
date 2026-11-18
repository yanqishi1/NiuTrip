package com.niutrip.app.ui.share

import kotlin.math.hypot

data class PosterCompactLabel(val candidate: PosterMarkerCandidate, val name: String,
                              val box: PosterMarkerBox, val textSize: Float)

/** Labels touch their pin's neighbourhood, with no leader and no route segment underneath. */
object PosterCompactLabels {
    fun layout(candidates: List<PosterMarkerCandidate>, reserved: List<PosterMarkerBox>,
               route: List<Pair<Float, Float>>, width: Float, height: Float,
               aliases: Map<String, String>, measure: (String, Float) -> Float): List<PosterCompactLabel> {
        val unique = candidates.distinctBy { it.point.id }
        val segments = route.zipWithNext()
        val occupied = reserved.toMutableList()
        val result = mutableListOf<PosterCompactLabel>()
        val ordered = unique.sortedWith(compareByDescending<PosterMarkerCandidate> { it.point.id in aliases }
            .thenByDescending { PosterMapMarkers.contentWeight(it.point) }
            .thenByDescending { candidate -> unique.filter { it != candidate }.minOfOrNull {
                hypot(it.x - candidate.x, it.y - candidate.y)
            } ?: Float.MAX_VALUE }.thenBy { it.order })
        for (candidate in ordered) {
            val name = aliases[candidate.point.id]?.trim()?.takeIf { it.isNotEmpty() }
                ?: PosterMapMarkers.checkinName(candidate.point)
            val x = candidate.x; val y = candidate.y
            if (x !in 20f..width - 20 || y !in 48f..height - 20) continue
            var label: PosterCompactLabel? = null
            for (size in listOf(22f, 20f, 18f)) {
                val w = measure(name, size); val h = size * 1.3f
                val boxes = listOf(
                    PosterMarkerBox(x + 23, y - 34 - h / 2, x + 23 + w, y - 34 + h / 2),
                    PosterMarkerBox(x - 23 - w, y - 34 - h / 2, x - 23, y - 34 + h / 2),
                    PosterMarkerBox(x - w / 2, y - 57 - h, x + w / 2, y - 57),
                    PosterMarkerBox(x - w / 2, y + 8, x + w / 2, y + 8 + h),
                    PosterMarkerBox(x + 22, y - 52 - h, x + 22 + w, y - 52),
                    PosterMarkerBox(x - 22 - w, y - 52 - h, x - 22, y - 52),
                )
                val box = boxes.firstOrNull { box ->
                    box.left >= 8 && box.top >= 8 && box.right <= width - 8 && box.bottom <= height - 8 &&
                        occupied.none { it.overlaps(box.expanded(3f)) } &&
                        segments.none { box.expanded(2f).intersectsSegment(it.first, it.second) } &&
                        unique.none { it != candidate && box.expanded(3f).overlaps(pinBox(it.x, it.y)) }
                }
                if (box != null) { label = PosterCompactLabel(candidate, name, box, size); break }
            }
            label?.let { result.add(it); occupied.add(it.box.expanded(4f)) }
        }
        return result.sortedBy { it.candidate.order }
    }

    fun pinBox(x: Float, y: Float) = PosterMarkerBox(x - 18, y - 50, x + 18, y)
}
