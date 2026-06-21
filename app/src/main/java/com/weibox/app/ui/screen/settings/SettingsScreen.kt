package com.weibox.app.ui.screen.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.weibox.app.R
import com.weibox.app.data.prefs.ThemeMode
import com.weibox.app.ui.components.WeiboTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsState()
    var tokenVisible by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { WeiboTopBar("设置") }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // ── 服务器连接 ────────────────────────────────────────
            SectionTitle("服务器连接")

            Text(
                "填入你的 WeiboX Server 地址和 API Token（在 server 的「设置 → API Token」里新建）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )

            OutlinedTextField(
                value = state.serverUrlInput,
                onValueChange = vm::onServerUrlChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("服务器地址") },
                placeholder = { Text("https://weibo.example.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )

            OutlinedTextField(
                value = state.apiTokenInput,
                onValueChange = vm::onApiTokenChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("API Token") },
                singleLine = true,
                visualTransformation = if (tokenVisible) VisualTransformation.None
                                       else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { tokenVisible = !tokenVisible }) {
                        Icon(
                            if (tokenVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = null
                        )
                    }
                }
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = vm::saveServer,
                    modifier = Modifier.weight(1f),
                    enabled = state.serverUrlInput != state.serverUrl ||
                              state.apiTokenInput != state.apiToken
                ) { Text(if (state.saved) "已保存" else "保存") }

                OutlinedButton(
                    onClick = vm::testConnection,
                    modifier = Modifier.weight(1f),
                    enabled = !state.testing && state.serverUrlInput.isNotBlank()
                ) {
                    if (state.testing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text("测试连接")
                }
            }

            state.connectionMessage?.let { msg ->
                val isError = msg.startsWith("连接失败")
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    color = if (isError) MaterialTheme.colorScheme.errorContainer
                            else MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = msg,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isError) MaterialTheme.colorScheme.onErrorContainer
                                else MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            HorizontalDivider()

            // ── 外观 ──────────────────────────────────────────────
            SectionTitle("外观")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.DarkMode, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Text("主题", style = MaterialTheme.typography.bodyLarge)
            }
            Spacer(Modifier.height(8.dp))
            val themeOptions = listOf(
                ThemeMode.SYSTEM to "跟随系统",
                ThemeMode.LIGHT  to "浅色",
                ThemeMode.DARK   to "深色"
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                themeOptions.forEachIndexed { index, (mode, label) ->
                    SegmentedButton(
                        selected = state.themeMode == mode,
                        onClick = { vm.setThemeMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index, themeOptions.size)
                    ) { Text(label) }
                }
            }

            HorizontalDivider()

            // ── 支持开发者 ────────────────────────────────────────
            SectionTitle("支持开发者")
            Image(
                painter = painterResource(R.drawable.payme),
                contentDescription = "收款二维码",
                modifier = Modifier
                    .size(200.dp)
                    .align(Alignment.CenterHorizontally)
            )
            OutlinedButton(
                onClick = vm::savePayQrCode,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.SaveAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("保存二维码到相册")
            }
            state.donateMessage?.let { msg ->
                val isError = msg.startsWith("保存失败")
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    color = if (isError) MaterialTheme.colorScheme.errorContainer
                            else MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = msg,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isError) MaterialTheme.colorScheme.onErrorContainer
                                else MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            HorizontalDivider()

            // ── 关于 ──────────────────────────────────────────────
            SectionTitle("关于")
            val context = LocalContext.current
            val versionName = remember {
                runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
                }.getOrDefault("?")
            }
            Text(
                "WeiboX v$versionName\n第三方微博客户端，数据来自你自建的 WeiboX Server。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "本项目开源，欢迎 Star 和反馈问题：",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            TextButton(
                onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/bidabrain/weiboX"))
                    )
                },
                contentPadding = PaddingValues(0.dp)
            ) {
                Text(
                    "github.com/bidabrain/weiboX",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
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
        color = MaterialTheme.colorScheme.primary
    )
}
