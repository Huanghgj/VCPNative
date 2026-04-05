package com.vcpnative.app.feature.models

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vcpnative.app.app.AppContainer
import kotlinx.coroutines.launch

@Composable
fun ModelsRoute(
    appContainer: AppContainer,
    onNavigateBack: () -> Unit,
) {
    var hotModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var favorites by remember { mutableStateOf<Set<String>>(emptySet()) }
    var allModels by remember { mutableStateOf<List<String>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val tracker = appContainer.modelUsageTracker

    LaunchedEffect(Unit) {
        hotModels = tracker.getHotModels(20)
        favorites = tracker.getFavorites().toSet()
        // 也获取服务器模型列表
        try {
            val serverModels = appContainer.modelCatalog.fetchAvailableModels()
            allModels = serverModels.map { it.id }
        } catch (_: Exception) {}
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                }
                Text("模型管理", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
        }

        // 收藏模型
        if (favorites.isNotEmpty()) {
            item {
                SectionTitle("⭐ 收藏模型")
            }
            items(favorites.toList()) { modelId ->
                ModelRow(
                    modelId = modelId,
                    isFavorite = true,
                    usageRank = hotModels.indexOf(modelId).takeIf { it >= 0 },
                    onToggleFavorite = {
                        scope.launch {
                            tracker.toggleFavorite(modelId)
                            favorites = tracker.getFavorites().toSet()
                        }
                    },
                )
            }
            item { Spacer(Modifier.height(16.dp)) }
        }

        // 热门模型
        if (hotModels.isNotEmpty()) {
            item {
                SectionTitle("🔥 使用排行")
            }
            items(hotModels) { modelId ->
                ModelRow(
                    modelId = modelId,
                    isFavorite = modelId in favorites,
                    usageRank = hotModels.indexOf(modelId),
                    onToggleFavorite = {
                        scope.launch {
                            tracker.toggleFavorite(modelId)
                            favorites = tracker.getFavorites().toSet()
                        }
                    },
                )
            }
            item { Spacer(Modifier.height(16.dp)) }
        }

        // 全部可用模型
        if (allModels.isNotEmpty()) {
            item {
                SectionTitle("📋 服务器模型 (${allModels.size})")
            }
            items(allModels) { modelId ->
                ModelRow(
                    modelId = modelId,
                    isFavorite = modelId in favorites,
                    usageRank = hotModels.indexOf(modelId).takeIf { it >= 0 },
                    onToggleFavorite = {
                        scope.launch {
                            tracker.toggleFavorite(modelId)
                            favorites = tracker.getFavorites().toSet()
                        }
                    },
                )
            }
        }

        if (hotModels.isEmpty() && allModels.isEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("还没有使用记录，开始聊天后这里会显示模型排行", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

@Composable
private fun ModelRow(
    modelId: String,
    isFavorite: Boolean,
    usageRank: Int?,
    onToggleFavorite: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (usageRank != null && usageRank < 3) {
                Icon(
                    Icons.Outlined.LocalFireDepartment,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = when (usageRank) {
                        0 -> Color(0xFFFF6B35)
                        1 -> Color(0xFFFF9500)
                        else -> Color(0xFFFFCC00)
                    },
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = modelId,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onToggleFavorite, modifier = Modifier.size(36.dp)) {
                Icon(
                    if (isFavorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = "收藏",
                    tint = if (isFavorite) Color(0xFFFF2D55) else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
