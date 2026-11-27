package com.orin.app.ui.onboarding

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.orin.app.data.KeyStoreManager
import com.orin.app.ui.components.OrinSunburstMark
import com.orin.app.util.OrinPermissions

private val providers = listOf(
    "gemini" to "Gemini",
    "anthropic" to "Claude",
    "openai" to "OpenAI",
    "deepseek" to "DeepSeek",
    "custom" to "Custom API",
)

@Composable
fun OrinOnboardingScreen(
    keys: KeyStoreManager,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var step by rememberSaveable { mutableIntStateOf(0) }
    var provider by rememberSaveable { mutableStateOf(keys.activeProviderId()) }
    var model by rememberSaveable { mutableStateOf(keys.activeModel()) }
    var apiKey by rememberSaveable {
        mutableStateOf(keys.getSecret("api_key_${keys.activeProviderId()}").orEmpty())
    }
    var baseUrl by rememberSaveable { mutableStateOf(keys.customBaseUrl()) }
    var error by remember { mutableStateOf<String?>(null) }
    var permissionRefresh by remember { mutableIntStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissionRefresh++ }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { permissionRefresh++ }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(28.dp))
        OrinSunburstMark(size = 54.dp)
        Spacer(Modifier.height(14.dp))
        Text(
            text = "Orin",
            style = MaterialTheme.typography.headlineLarge.copy(
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Normal,
            ),
            color = scheme.onBackground,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "A private agent for your phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))

        when (step) {
            0 -> {
                OnboardingCard {
                    Text(
                        "Orin can read your calendar, find contacts, send approved texts, track tasks and inspect your Meta Ads — all from a single chat.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = scheme.onSurface,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Your API keys stay in Android's hardware-backed Keystore. Nothing is sent anywhere except the AI provider you choose.",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = { step = 1 },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Get started") }
            }

            1 -> {
                OnboardingCard {
                    Text("Choose a model provider", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        providers.forEach { (id, label) ->
                            val selected = provider == id
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) scheme.primary else scheme.surfaceContainerHigh)
                                    .border(
                                        1.dp,
                                        if (selected) scheme.primary else scheme.outlineVariant,
                                        RoundedCornerShape(8.dp),
                                    )
                                    .clickable {
                                        provider = id
                                        keys.setActiveProviderId(id)
                                        apiKey = keys.getSecret("api_key_$id").orEmpty()
                                        model = keys.defaultModel(id)
                                        error = null
                                    }
                                    .padding(horizontal = 12.dp, vertical = 7.dp),
                            ) {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (selected) Color.White else scheme.onSurface,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = { Text("Model ID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    if (provider == "custom") {
                        OutlinedTextField(
                            value = baseUrl,
                            onValueChange = { baseUrl = it },
                            label = { Text("Base URL (e.g. https://api.example.com/v1)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("${provider.replaceFirstChar { it.uppercase() }} API key") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    error?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = scheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = {
                        val trimmed = apiKey.trim()
                        if (trimmed.isEmpty()) {
                            error = "Add an API key to continue."
                        } else {
                            keys.setActiveProviderId(provider)
                            keys.setActiveModel(model.trim().ifBlank { keys.defaultModel(provider) })
                            keys.putSecret("api_key_$provider", trimmed)
                            if (provider == "custom") {
                                keys.setCustomBaseUrl(baseUrl)
                                keys.setCustomModel(model.trim().ifBlank { keys.defaultModel("custom") })
                            }
                            error = null
                            step = 2
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save & continue") }
            }

            2 -> {
                OnboardingCard {
                    Text("Allow phone tools", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "You can grant these later in Settings. Orin asks for your approval before any side effect.",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OrinPermissions.groups.forEach { group ->
                        val granted = remember(permissionRefresh, group.id) {
                            OrinPermissions.isGranted(context, group)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(group.title, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    if (granted) "Granted" else group.rationale,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (granted) scheme.primary else scheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            TextButton(
                                onClick = {
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
                            ) { Text(if (granted) "Manage" else "Allow") }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = { step = 3 },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Continue") }
                TextButton(onClick = onDone) { Text("Skip for now") }
            }

            else -> {
                OnboardingCard {
                    Text("You're ready.", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Ask Orin for your morning brief, a contact's number, or to run a workspace command. Anything that changes the world needs your approval first.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onDone,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Start chatting") }
            }
        }
    }
}

@Composable
private fun OnboardingCard(content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(scheme.surfaceContainerLow)
            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(18.dp))
            .padding(18.dp),
    ) {
        content()
    }
}
