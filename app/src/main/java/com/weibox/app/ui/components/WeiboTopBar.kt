package com.weibox.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.weibox.app.R

/**
 * @param below 工具栏下方、分割线上方的附加内容（如搜索入口条）。
 *   放在顶栏内部而不是内容区，是因为内容区顶部要留给下拉刷新指示器——
 *   它静止时向上平移一个身位藏在顶栏后面，内容区一旦被别的东西顶下去就会露出来。
 */
@Composable
fun WeiboTopBar(
    title: String,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable () -> Unit = {},
    onTitleClick: (() -> Unit)? = null,
    below: @Composable () -> Unit = {}
) {
    Column {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            // 处理状态栏高度
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))

            // 工具栏本体：左文字 | 中间 logo | 右操作
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Row(
                    Modifier.align(Alignment.CenterStart),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    navigationIcon()
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .padding(start = 16.dp)
                            .then(
                                if (onTitleClick != null) Modifier.clickable { onTitleClick() }
                                else Modifier
                            )
                    )
                }

                Image(
                    painter = painterResource(R.drawable.ic_logo),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(32.dp)
                )

                Row(
                    Modifier.align(Alignment.CenterEnd),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    actions()
                }
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            below()
        }

        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outline
        )
    }
}
