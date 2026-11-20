package com.niutrip.app.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.niutrip.app.core.DayGroup
import com.niutrip.app.core.PointLite
import com.niutrip.app.core.dayColor
import com.niutrip.app.ui.common.absoluteMediaUrl
import com.niutrip.app.ui.theme.Line
import com.niutrip.app.ui.theme.Muted
import java.time.format.DateTimeFormatter

internal data class CheckinTimelineEntry(val point: PointLite, val dayIndex: Int)

internal data class PointPhotoGroup(val id: String, val title: String, val images: List<String>)

internal fun timelinePhotoGroups(timeline: List<CheckinTimelineEntry>): List<PointPhotoGroup> = timeline
    .filter { it.point.images.isNotEmpty() }
    .map { PointPhotoGroup(it.point.id, it.point.name?.trim()?.takeIf(String::isNotEmpty) ?: "旅途打卡", it.point.images) }

internal fun checkinTimeline(days: List<DayGroup>): List<CheckinTimelineEntry> = days
    .flatMapIndexed { index, day -> day.points.filter(PointLite::isCheckin).map { CheckinTimelineEntry(it, index) } }
    .sortedWith(compareByDescending<CheckinTimelineEntry> { it.point.time }.thenBy { it.point.id })
    .distinctBy { it.point.id }

private val timelineDate = DateTimeFormatter.ofPattern("MM-dd")
private val timelineTime = DateTimeFormatter.ofPattern("HH:mm")

@Composable
internal fun CheckinTimelineRow(
    entry: CheckinTimelineEntry,
    selected: Boolean,
    first: Boolean,
    last: Boolean,
    onClick: () -> Unit,
    onImageClick: (Int) -> Unit,
) {
    val point = entry.point
    val dayColor = Color(dayColor(entry.dayIndex))
    Row(Modifier.fillMaxWidth().drawBehind {
        val dotCenter = 19.5.dp.toPx()
        drawLine(Line, Offset(60.dp.toPx(), if (first) dotCenter else 0f),
            Offset(60.dp.toPx(), if (last) dotCenter else size.height), 1.dp.toPx())
    }
        .background(if (selected) dayColor.copy(alpha = .06f) else Color.Transparent, RoundedCornerShape(8.dp))
        .clickable(onClick = onClick).padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.width(44.dp).padding(top = 1.dp), horizontalAlignment = Alignment.End) {
            Text(point.time.format(timelineDate), color = Muted, style = MaterialTheme.typography.labelMedium)
            Text(point.time.format(timelineTime), color = Muted, style = MaterialTheme.typography.labelSmall)
        }
        Column(Modifier.width(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.padding(top = 5.dp).size(9.dp).background(dayColor, CircleShape))
        }
        Column(Modifier.weight(1f).padding(bottom = 14.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(point.name?.trim()?.takeIf(String::isNotEmpty) ?: "旅途打卡",
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            point.desc?.takeIf(String::isNotBlank)?.let {
                Text(it, color = Muted, style = MaterialTheme.typography.bodySmall,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (point.images.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(point.images, key = { index, image -> "$index:$image" }) { index, image ->
                    Box(Modifier.size(64.dp).clip(RoundedCornerShape(6.dp)).background(Line.copy(alpha = .4f))
                        .clickable { onImageClick(index) }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Photo, null, tint = Muted, modifier = Modifier.size(22.dp))
                        AsyncImage(model = absoluteMediaUrl(image), contentDescription = "${point.name ?: "打卡"}照片 ${index + 1}",
                            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                }
            }
        }
    }
}
