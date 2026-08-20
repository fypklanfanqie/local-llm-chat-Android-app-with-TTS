package com.chatbyyourside.ui.affinity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

const val AFFINITY_SCROLL_TAG = "affinity_archive_scroll"
const val CHECKIN_SCROLL_TAG = "checkin_shop_scroll"

// 泛化档案风格：中性深色 + Iris 紫强调 + 淡青次级（对齐大众版主题，非方舟金黑）。
private val ArchiveBackground = Color(0xFF0E1420)
private val ArchiveSurface = Color(0xFF161D2B)
private val ArchiveSurfaceRaised = Color(0xFF1E2836)
private val ArchiveAccent = Color(0xFF7C5CFF)
private val ArchiveSecondary = Color(0xFF76C9D6)

@Composable
fun AffinityArchivePage(
    title: String,
    code: String,
    onBack: () -> Unit,
    scrollTag: String,
    content: LazyListScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ArchiveBackground)
            .statusBarsPadding(),
    ) {
        ArchiveHeader(code = code, title = title, onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag(scrollTag),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

@Composable
private fun ArchiveHeader(code: String, title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ArchiveSurface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArchiveBackButton(onClick = onBack)
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(code, color = ArchiveAccent, fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.5.sp)
            Text(title, color = Color(0xFFF2F0EA), fontSize = 23.sp, fontWeight = FontWeight.Bold)
        }
        Text("CHAT COMPANION", color = ArchiveSecondary.copy(alpha = 0.8f), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun ArchiveBackButton(onClick: () -> Unit) {
    androidx.compose.material3.TextButton(
        onClick = onClick,
        modifier = Modifier.background(ArchiveSurfaceRaised, RoundedCornerShape(10.dp)),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) { Text("返回", color = ArchiveAccent, fontSize = 13.sp) }
}

@Composable
fun ArchiveSectionLabel(code: String, title: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(top = 4.dp)) {
        Text(code, color = ArchiveSecondary, fontSize = 9.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.2.sp)
        Text(title, color = Color(0xFFF2F0EA), fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun ArchiveCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(ArchiveSurface, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
fun archivePrimaryColor(): Color = ArchiveAccent

@Composable
fun archiveSecondaryColor(): Color = ArchiveSecondary

@Composable
fun archiveSurfaceColor(): Color = ArchiveSurfaceRaised

@Composable
fun AffinityGiftImage(path: String, size: androidx.compose.ui.unit.Dp) {
    if (path.isBlank()) {
        Box(Modifier.size(size).background(ArchiveAccent.copy(alpha = 0.16f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Text("礼", color = ArchiveAccent, fontWeight = FontWeight.Bold)
        }
    } else {
        coil.compose.AsyncImage(
            model = path,
            contentDescription = null,
            modifier = Modifier.size(size).background(ArchiveSurfaceRaised, RoundedCornerShape(12.dp)),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        )
    }
}
