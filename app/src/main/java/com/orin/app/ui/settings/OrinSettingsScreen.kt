@file:OptIn(ExperimentalMaterial3Api::class)

package com.orin.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orin.app.data.KeyStoreManager

@Composable
fun OrinSettingsScreen(
    keys: KeyStoreManager,
    onBack: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var selectedProvider by remember { mutableStateOf(keys.activeProviderId()) }
    var apiKeyInput by remember { mutableStateOf(keys.getSecret("api_key_$selectedProvider").orEmpty()) }
    var modelInput by remember { mutableStateOf(keys.activeModel()) }
    var metaTokenInput by remember { mutableStateOf(keys.getSecret("meta_ads_token").orEmpty()) }
    var metaAccountInput by remember { mutableStateOf(keys.getSecret("meta_ad_account_id").orEmpty()) }
    var savedBanner by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = scheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = scheme.background,
                    titleContentColor = scheme.onBackground,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Text(
                        "Settings",
                        style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Serif),
                    )
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Key, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Models & API Keys", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium))
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("gemini" to "Gemini", "anthropic" to "Claude", "openai" to "OpenAI", "deepseek" to "DeepSeek").forEach { (id, label) ->
                            val selected = selectedProvider == id
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) scheme.primary else scheme.surfaceContainerLow)
                                    .border(1.dp, if (selected) scheme.primary else scheme.outlineVariant, RoundedCornerShape(8.dp))
                                    .clickable {
                                        selectedProvider = id
                                        keys.setActiveProviderId(id)
                                        apiKeyInput = keys.getSecret("api_key_$id").orEmpty()
                                        modelInput = keys.activeModel()
                                        savedBanner = null
                                    }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Text(label, style = MaterialTheme.typography.labelMedium, color = if (selected) Color.White else scheme.onSurface)
                            }
                        }
                    }

                    SettingsField(
                        label = "Model ID",
                        value = modelInput,
                        onValueChange = { modelInput = it },
                    )
                    SettingsField(
                        label = "${selectedProvider.replaceFirstChar { it.uppercase() }} API Key",
                        value = apiKeyInput,
                        onValueChange = { apiKeyInput = it },
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = savedBanner ?: "Stored securely in Android Keystore",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(scheme.primary)
                                .clickable {
                                    keys.setActiveProviderId(selectedProvider)
                                    keys.setActiveModel(modelInput.ifBlank { "gemini-2.5-flash" })
                                    keys.putSecret("api_key_$selectedProvider", apiKeyInput)
                                    savedBanner = "Saved for $selectedProvider"
                                }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Text("Save", style = MaterialTheme.typography.labelLarge, color = Color.White)
                        }
                    }
                }
            }
            item { HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f)) }
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.PhoneAndroid, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Meta Ads & Phone Bridge", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium))
                    }
                    SettingsField(label = "Meta Ad Account ID (e.g. act_123...)", value = metaAccountInput, onValueChange = { metaAccountInput = it })
                    SettingsField(label = "Meta Access Token", value = metaTokenInput, onValueChange = { metaTokenInput = it })
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(scheme.primary)
                            .clickable {
                                keys.putSecret("meta_ad_account_id", metaAccountInput)
                                keys.putSecret("meta_ads_token", metaTokenInput)
                                savedBanner = "Saved Meta Ads credentials"
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text("Save Meta Ads", style = MaterialTheme.typography.labelLarge, color = Color.White)
                    }
                }
            }
            item { HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f)) }
            item { SettingsRow(Icons.Outlined.Memory, "Local Offline Models", "On-device GGUF inference") }
            item { HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f)) }
            item { SettingsRow(Icons.Outlined.Mic, "Voice & Speech", "Tap mic in chat for voice commands") }
            item { HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f)) }
            item { SettingsRow(Icons.Outlined.Shield, "Security & Biometrics", "Encrypted Keystore + Action Audit Log active") }
            item { HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f)) }
            item { SettingsRow(Icons.Outlined.Palette, "Appearance", "Warm Editorial Dark / Light") }
        }
    }
}

@Composable
private fun SettingsField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(scheme.surfaceContainerLow)
            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = TextStyle(color = scheme.onSurface, fontSize = 14.sp),
            cursorBrush = SolidColor(scheme.primary),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = scheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
    }
}
