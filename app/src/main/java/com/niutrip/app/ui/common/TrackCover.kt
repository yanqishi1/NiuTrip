package com.niutrip.app.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.niutrip.app.R
import com.niutrip.app.ui.theme.Green50

@Composable
fun TrackCover(imageUrl: String, modifier: Modifier = Modifier, contentDescription: String? = null) {
    var loadFailed by remember(imageUrl) { mutableStateOf(false) }
    val useDefault = imageUrl.isBlank() || imageUrl.endsWith("/static/covers/default.svg")
    Box(
        modifier = modifier.clip(RoundedCornerShape(8.dp)).background(Green50),
        contentAlignment = Alignment.Center,
    ) {
        if (useDefault || loadFailed) {
            Image(
                painter = painterResource(R.drawable.default_track_logo),
                contentDescription = contentDescription ?: "默认轨迹代表图",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            AsyncImage(
                model = absoluteMediaUrl(imageUrl),
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onError = { loadFailed = true },
            )
        }
    }
}
