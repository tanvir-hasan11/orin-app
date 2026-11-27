@file:OptIn(ExperimentalMaterial3Api::class)

package com.orin.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.orin.app.agent.OrinAgentRuntime
import com.orin.app.control.OrinControlService
import com.orin.app.notify.OrinNotificationListener
import com.orin.app.util.OrinCrashHandler
import com.orin.app.util.OrinPermissions
import com.orin.app.voice.OrbOverlay
import com.orin.app.voice.OrinVoice
import com.orin.app.voice.OrinVoiceService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun OrinSettingsScreen(
    runtime: OrinAgentRuntime,
    onBack: () -> Unit,
    onDataCleared: () -> Unit,
) {
    val keys = runtime.keys
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedProvider by remember { mutableStateOf(keys.activeProviderId()) }
    var apiKeyInput by remember { mutableStateOf(keys.getSecret("api_key_$selectedProvider").orEmpty()) }
    var modelInput by remember { mutableStateOf(keys.activeModel()) }
    var customBaseUrlInput by remember { mutableStateOf(keys.customBaseUrl()) }
    var metaTokenInput by remember { mutableStateOf(keys.getSecret("meta_ads_token").orEmpty()) }
    var metaAccountInput by remember { mutableStateOf(keys.getSecret("meta_ad_account_id").orEmpty()) }
    var savedBanner by remember { mutableStateOf<String?>(null) }
    var shellEnabled by remember { mutableStateOf(keys.shellEnabled()) }
    var speakReplies by remember { mutableStateOf(keys.speakReplies()) }
    var voiceLanguage by remember { mutableStateOf(keys.voiceLanguage()) }
    var orbEnabled by remember {
        mutableStateOf(
            context.getSharedPreferences("orin_voice", android.content.Context.MODE_PRIVATE)
                .getBoolean(OrinVoice.PREF_ORB_ENABLED, false),
        )
    }
    var overlayGranted by remember { mutableStateOf(OrbOverlay.canShow(context)) }
    var controlEnabled by remember { mutableStateOf(OrinControlService.isEnabled(context)) }
    var notificationsEnabled by remember { mutableStateOf(OrinNotificationListener.isEnabled(context)) }
    var confirmClear by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showCrash by remember { mutableStateOf(false) }
    val lastCrash = remember(showCrash) { if (showCrash) OrinCrashHandler.lastCrash(context) else null }
    var permissionRefresh by remember { mutableIntStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissionRefresh++ }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        permissionRefresh++
        overlayGranted = OrbOverlay.canShow(context)
        controlEnabled = OrinControlService.isEnabled(context)
        notificationsEnabled = OrinNotificationListener.isEnabled(context)
    }

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
                        listOf(
                            "gemini" to "Gemini",
                            "anthropic" to "Claude",
                            "openai" to "OpenAI",
                            "deepseek" to "DeepSeek",
                            "custom" to "Custom API",
                        ).forEach { (id, label) ->
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
                                        modelInput = keys.defaultModel(id)
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
                    if (selectedProvider == "custom") {
                        SettingsField(
                            label = "Base URL (OpenAI-compatible, e.g. https://api.example.com/v1)",
                            value = customBaseUrlInput,
                            onValueChange = { customBaseUrlInput = it },
                        )
                    }
                    SettingsField(
                        label = "${selectedProvider.replaceFirstChar { it.uppercase() }} API Key",
                        value = apiKeyInput,
                        onValueChange = { apiKeyInput = it },
                        secret = true,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = savedBanner ?: "Stored in Android Keystore",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(scheme.primary)
                                .clickable {
                                    keys.setActiveProviderId(selectedProvider)
                                    keys.setActiveModel(modelInput.trim().ifBlank { keys.defaultModel(selectedProvider) })
                                    keys.putSecret("api_key_$selectedProvider", apiKeyInput)
                                    if (selectedProvider == "custom") {
                                        keys.setCustomBaseUrl(customBaseUrlInput)
                                        keys.setCustomModel(modelInput.trim().ifBlank { keys.defaultModel("custom") })
                                    }
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
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Shield, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Phone Permissions", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium))
                    }
                    OrinPermissions.groups.forEach { group ->
                        val granted = remember(permissionRefresh, group.id) {
                            OrinPermissions.isGranted(context, group)
                        }
                        PermissionRow(
                            title = group.title,
                            subtitle = if (granted) "Granted" else group.rationale,
                            granted = granted,
                            onAction = {
                                if (granted) {
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.parse("package:${context.packageName}"),
                                        ),
                                    )
                                } else {
                                    permissionLauncher.launch(group.permissions.toTypedArray())
                                }
                            },
                        )
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
                        Text("Meta Ads", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium))
                    }
                    SettingsField(
                        label = "Meta Ad Account ID (e.g. act_123...)",
                        value = metaAccountInput,
                        onValueChange = { metaAccountInput = it },
                    )
                    SettingsField(
                        label = "Meta Access Token",
                        value = metaTokenInput,
                        onValueChange = { metaTokenInput = it },
                        secret = true,
                    )
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

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Mic, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Voice", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium))
                    }
                    SettingsToggleRow(
                        icon = Icons.Outlined.Mic,
                        title = "Speak replies aloud",
                        subtitle = "Orin reads its answers using your device voice",
                        checked = speakReplies,
                        onCheckedChange = {
                            speakReplies = it
                            keys.setSpeakReplies(it)
                        },
                    )
                    SettingsToggleRow(
                        icon = Icons.Outlined.PhoneAndroid,
                        title = "Floating orb",
                        subtitle = if (overlayGranted) {
                            "Tap over any app to talk to Orin"
                        } else {
                            "Requires \"Display over other apps\" permission"
                        },
                        checked = orbEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled && !overlayGranted) {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}"),
                                    ),
                                )
                            } else {
                                orbEnabled = enabled
                                context.getSharedPreferences("orin_voice", android.content.Context.MODE_PRIVATE)
                                    .edit().putBoolean(OrinVoice.PREF_ORB_ENABLED, enabled).apply()
                                if (enabled) OrinVoiceService.refresh(context) else OrinVoiceService.stop(context)
                            }
                        },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Voice language",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        OrinVoice.languages().forEach { (tag, label) ->
                            val selected = voiceLanguage == tag
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) scheme.primary else scheme.surfaceContainerLow)
                                    .border(1.dp, if (selected) scheme.primary else scheme.outlineVariant, RoundedCornerShape(8.dp))
                                    .clickable {
                                        voiceLanguage = tag
                                        keys.setVoiceLanguage(tag)
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            ) {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selected) Color.White else scheme.onSurface,
                                )
                            }
                        }
                    }
                    Text(
                        text = "বাংলা ও হিন্দী শোনার জন্য ডিভাইসের ভয়েস প্যাক লাগতে পারে (Settings → Languages & input)। বাংলা প্যাক না থাকলে Orin Hindi/English-এ ফিরে যাবে।",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }

            item { HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f)) }

            item {
                SettingsRow(
                    icon = Icons.Outlined.Shield,
                    title = "Message summaries",
                    subtitle = if (notificationsEnabled) {
                        "On — codes and links are hidden from summaries"
                    } else {
                        "Off — allow notification access for \"what did I miss?\""
                    },
                    onClick = {
                        context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                    },
                )
            }

            item { HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f)) }

            item {
                SettingsRow(
                    icon = Icons.Outlined.PhoneAndroid,
                    title = "Screen control",
                    subtitle = if (controlEnabled) {
                        "On — Orin can read and operate apps you open"
                    } else {
                        "Off — enable the accessibility service to use any app"
                    },
                    onClick = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                )
            }

            item {
                SettingsRow(
                    icon = Icons.Outlined.Shield,
                    title = "Approval cards over other apps",
                    subtitle = if (overlayGranted) {
                        "Granted — Allow/Deny cards can appear over any app"
                    } else {
                        "Required for finger-only Allow cards during screen control"
                    },
                    onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    },
                )
            }

            item { HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f)) }

            item {
                SettingsToggleRow(
                    icon = Icons.Outlined.Terminal,
                    title = "Workspace shell",
                    subtitle = "Every command needs approval",
                    checked = shellEnabled,
                    onCheckedChange = {
                        shellEnabled = it
                        keys.setShellEnabled(it)
                    },
                )
            }

            item { HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f)) }
            item {
                SettingsRow(
                    icon = Icons.Outlined.Delete,
                    title = "Clear all local data",
                    subtitle = "Chats, tasks and audit log",
                    onClick = { confirmClear = true },
                )
            }
            item { HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f)) }
            item {
                SettingsRow(
                    icon = Icons.Outlined.Info,
                    title = "About Orin",
                    subtitle = "Version 1.6.0 · Privacy",
                    onClick = { showAbout = true },
                )
            }
            item { HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f)) }
            item {
                SettingsRow(
                    icon = Icons.Outlined.Info,
                    title = "Last crash log",
                    subtitle = if (OrinCrashHandler.lastCrash(context) != null) {
                        "Tap to see the exact error — send it to the developer"
                    } else {
                        "No crash recorded"
                    },
                    onClick = { showCrash = true },
                )
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear all local data?") },
            text = {
                Text("This deletes chats, tasks and the audit log. API keys remain in the Android Keystore.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        scope.launch {
                            withContext(Dispatchers.IO) { runtime.db.clearAllTables() }
                            onDataCleared()
                        }
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            },
        )
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text("About Orin") },
            text = {
                Text(
                    "Orin is a personal Android agent. Chat text is sent to the AI provider you select. " +
                        "Calendar, contacts, SMS and Meta data are only read when a tool is called. " +
                        "API keys and tokens are stored with Android Keystore encryption. " +
                        "Orin never uploads your data to its own servers.",
                )
            },
            confirmButton = {
                TextButton(onClick = { showAbout = false }) { Text("Close") }
            },
        )
    }

    if (showCrash) {
        AlertDialog(
            onDismissRequest = { showCrash = false },
            title = { Text(lastCrash?.first ?: "No crash log") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        lastCrash?.second ?: "No crash has been recorded on this device.",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showCrash = false }) { Text("Close") }
            },
            dismissButton = {
                TextButton(onClick = {
                    OrinCrashHandler.clear(context)
                    showCrash = false
                }) { Text("Delete log") }
            },
        )
    }
}

@Composable
private fun SettingsField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    secret: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    var revealed by remember { mutableStateOf(false) }
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
            visualTransformation = if (secret && !revealed) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (secret) {
            Text(
                text = if (revealed) "Hide" else "Show",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.primary,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clickable { revealed = !revealed },
            )
        }
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
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

@Composable
private fun SettingsToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = scheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PermissionRow(
    title: String,
    subtitle: String,
    granted: Boolean,
    onAction: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (granted) scheme.primary else scheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onAction) {
            Text(if (granted) "Manage" else "Allow")
        }
    }
}
