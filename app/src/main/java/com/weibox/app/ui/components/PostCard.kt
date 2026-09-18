package com.weibox.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.weibox.app.data.model.WeiboPost
import com.weibox.app.data.repository.WeiboRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 头像首字母颜色池
private val avatarColors = listOf(
    Color(0xFFFA7D40), Color(0xFF4CAF50), Color(0xFF2196F3),
    Color(0xFF9C27B0), Color(0xFFFF5722), Color(0xFF009688)
)

@Composable
fun PostCard(
    post: WeiboPost,
    onUserClick: (String) -> Unit,
    repo: WeiboRepository? = null,
    modifier: Modifier = Modifier
) {
    var viewerUrls by remember { mutableStateOf<List<String>?>(null) }
    var viewerStart by remember { mutableIntStateOf(0) }
    var showComments by remember { mutableStateOf(false) }

    if (viewerUrls != null) {
        ImageViewerDialog(
            urls = viewerUrls!!,
            initialIndex = viewerStart,
            onDismiss = { viewerUrls = null }
        )
    }
    if (showComments && repo != null) {
        CommentsBottomSheet(
            postId = post.id,
            commentCount = post.commentsCount,
            repo = repo,
            onDismiss = { showComments = false }
        )
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            // 头部：头像 + 昵称 / 时间·来源
            Row(verticalAlignment = Alignment.CenterVertically) {
                UserAvatar(
                    avatarUrl = post.userAvatar,
                    name = post.userName,
                    size = 44,
                    onClick = { if (post.userId.isNotEmpty()) onUserClick(post.userId) }
                )

                Spacer(Modifier.width(10.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        text = post.userName,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // 次要信息压到第二行，让昵称独占一行、层次更清楚
                    val meta = listOfNotNull(
                        formatTime(post.createdAtTimestamp).takeIf { it.isNotBlank() },
                        post.source.takeIf { it.isNotBlank() }?.let { "来自 $it" }
                    ).joinToString(" · ")
                    if (meta.isNotBlank()) {
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // 正文：与头像不再左对齐缩进，整幅卡片宽度可用
            if (post.text.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = post.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis
                )
            }

            val pics = post.pics
            if (pics.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                ImageGrid(pics = pics, onImageClick = { idx ->
                    viewerUrls = pics; viewerStart = idx
                })
            }

            post.retweetPost?.let { rt ->
                Spacer(Modifier.height(10.dp))
                RetweetCard(
                    post = rt,
                    onUserClick = onUserClick,
                    onImageClick = { idx ->
                        viewerUrls = rt.pics; viewerStart = idx
                    }
                )
            }

            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                ActionItem(
                    icon = Icons.Outlined.Repeat,
                    label = formatCount(post.repostsCount)
                )
                ActionItem(
                    icon = Icons.Outlined.ChatBubbleOutline,
                    label = formatCount(post.commentsCount),
                    onClick = if (repo != null && post.commentsCount > 0) {
                        { showComments = true }
                    } else null
                )
                ActionItem(
                    icon = Icons.Outlined.FavoriteBorder,
                    label = formatCount(post.likesCount)
                )
            }
        }
    }
}

@Composable
fun UserAvatar(
    avatarUrl: String,
    name: String,
    size: Int = 40,
    onClick: (() -> Unit)? = null
) {
    val colorIndex = (name.firstOrNull()?.code ?: 0) % avatarColors.size
    val bgColor = avatarColors[colorIndex]
    val initial = name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"

    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(bgColor)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        if (avatarUrl.isNotBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(avatarUrl).crossfade(true).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = initial,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = (size * 0.4f).sp
            )
        }
    }
}

@Composable
private fun RetweetCard(
    post: WeiboPost,
    onUserClick: (String) -> Unit,
    onImageClick: (Int) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(Modifier.padding(12.dp)) {
            if (post.userName.isNotBlank()) {
                Text(
                    text = "@${post.userName}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { if (post.userId.isNotEmpty()) onUserClick(post.userId) }
                )
                Spacer(Modifier.height(3.dp))
            }
            if (post.text.isNotBlank()) {
                Text(
                    text = post.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (post.pics.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                ImageGrid(
                    pics = post.pics,
                    onImageClick = onImageClick,
                    cornerRadius = 8,
                    gap = 3
                )
            }
        }
    }
}

/**
 * 九宫格。按容器宽度用 weight 分配，不再写死 dp——这样窄屏不溢出、宽屏不留白，
 * 每格统一 1:1（单图除外，给它更大的展示比例）。
 */
@Composable
private fun ImageGrid(
    pics: List<String>,
    onImageClick: (Int) -> Unit,
    cornerRadius: Int = 12,
    gap: Int = 4
) {
    val count = pics.size.coerceAtMost(9)
    if (count == 0) return

    if (count == 1) {
        // 单图给个更舒展的比例，而不是挤成小方块
        ImageCell(
            url = pics[0],
            cornerRadius = cornerRadius,
            onClick = { onImageClick(0) },
            modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f)
        )
        return
    }

    val cols = if (count == 4) 2 else 3
    val rows = (count + cols - 1) / cols
    Column(verticalArrangement = Arrangement.spacedBy(gap.dp)) {
        for (row in 0 until rows) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gap.dp)
            ) {
                for (col in 0 until cols) {
                    val idx = row * cols + col
                    if (idx < count) {
                        ImageCell(
                            url = pics[idx],
                            cornerRadius = cornerRadius,
                            onClick = { onImageClick(idx) },
                            modifier = Modifier.weight(1f).aspectRatio(1f)
                        )
                    } else {
                        // 占位，保证最后一行的图片和上面几行等宽
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageCell(
    url: String,
    cornerRadius: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(url).crossfade(true).build(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
    )
}

/**
 * 操作栏的一项。可点击时套一层圆角 clickable，让 ripple 落在胶囊范围内，
 * 同时把触控区放大到接近 48dp 高——原来只有 16dp 图标加 4dp 内边距，太难点。
 */
@Composable
private fun ActionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: (() -> Unit)? = null
) {
    val interactive = onClick != null
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .then(if (interactive) Modifier.clickable(onClick = onClick!!) else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (interactive) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        if (label != "0") {
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatCount(n: Int): String = when {
    n <= 0    -> "0"
    n >= 10000 -> "${n / 10000}万"
    else      -> n.toString()
}

private fun formatTime(ts: Long): String {
    if (ts == 0L) return ""
    val now = System.currentTimeMillis()
    val diff = now - ts
    return when {
        diff < 60_000      -> "刚刚"
        diff < 3600_000    -> "${diff / 60_000}分钟前"
        diff < 86400_000   -> "${diff / 3600_000}小时前"
        diff < 86400_000 * 7 -> "${diff / 86400_000}天前"
        else -> SimpleDateFormat("MM-dd", Locale.CHINA).format(Date(ts))
    }
}
