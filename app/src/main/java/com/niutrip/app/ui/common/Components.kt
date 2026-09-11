package com.niutrip.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.niutrip.app.ui.theme.*

@Composable fun SegmentedControl(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.background(Color(0xFFE9EDF2), RoundedCornerShape(10.dp)).padding(3.dp)) {
        options.forEachIndexed { index, label ->
            Box(Modifier.weight(1f).background(if (selected == index) Color.White else Color.Transparent, RoundedCornerShape(8.dp))
                .clickable { onSelect(index) }.padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
                Text(label, color = if (selected == index) Ink else Muted, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable fun LoadingOrError(loading: Boolean, error: String?, onRetry: () -> Unit) {
    when {
        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        error != null -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(error, color = Muted); Spacer(Modifier.height(12.dp)); IconButton(onClick = onRetry) { Icon(Icons.Default.Refresh, "重试") }
        }
    }
}

@Composable fun StatusPill(text: String, color: Color, background: Color = color.copy(alpha = .12f)) {
    Text(text, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
        modifier = Modifier.background(background, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 4.dp))
}

@Composable fun GonePage(message: String = "链接已失效", onBack: (() -> Unit)? = null) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("NiuTrip", color = Green700, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp)); Text(message, color = Muted)
        if (onBack != null) { Spacer(Modifier.height(20.dp)); Button(onClick = onBack) { Text("返回") } }
    }
}
