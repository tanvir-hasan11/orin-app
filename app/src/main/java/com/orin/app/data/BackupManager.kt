package com.orin.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

@Serializable
data class OrinBackupEnvelope(
    val version: Int = 1,
    val saltB64: String,
    val ivB64: String,
    val cipherB64: String,
)

@Serializable
data class OrinBackupPayload(
    val exportedAtMs: Long,
    val provider: String,
    val model: String,
    val themeStyle: String,
    val secrets: Map<String, String> = emptyMap(),
    val conversations: List<BackupConversation> = emptyList(),
    val messages: List<BackupMessage> = emptyList(),
    val memories: List<BackupMemory> = emptyList(),
    val tasks: List<BackupTask> = emptyList(),
)

@Serializable
data class BackupConversation(val id: String, val title: String, val updatedAtMs: Long)

@Serializable
data class BackupMessage(
    val id: String,
    val conversationId: String,
    val isUser: Boolean,
    val text: String,
    val toolSummary: String? = null,
    val createdAtMs: Long,
)

@Serializable
data class BackupMemory(val id: String, val key: String, val value: String, val category: String, val createdAtMs: Long)

@Serializable
data class BackupTask(val id: String, val title: String, val priority: String, val completed: Boolean, val createdAtMs: Long)

/**
 * Password-encrypted AES-GCM-256 export/import for Orin chats, memories, tasks, and optional secrets.
 */
object BackupManager {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val rng = SecureRandom()

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), salt, 100_000, 256)
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    fun encryptPayload(payload: OrinBackupPayload, password: String): String {
        require(password.length >= 4) { "Passphrase must be at least 4 characters." }
        val plain = json.encodeToString(payload).toByteArray(Charsets.UTF_8)
        val salt = ByteArray(16).also { rng.nextBytes(it) }
        val iv = ByteArray(12).also { rng.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(128, iv))
        val sealed = cipher.doFinal(plain)
        val env = OrinBackupEnvelope(
            saltB64 = Base64.getEncoder().encodeToString(salt),
            ivB64 = Base64.getEncoder().encodeToString(iv),
            cipherB64 = Base64.getEncoder().encodeToString(sealed),
        )
        return json.encodeToString(env)
    }

    fun decryptPayload(rawEnvelope: String, password: String): OrinBackupPayload {
        val env = json.decodeFromString<OrinBackupEnvelope>(rawEnvelope)
        val salt = Base64.getDecoder().decode(env.saltB64)
        val iv = Base64.getDecoder().decode(env.ivB64)
        val sealed = Base64.getDecoder().decode(env.cipherB64)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(128, iv))
        val plain = cipher.doFinal(sealed).toString(Charsets.UTF_8)
        return json.decodeFromString<OrinBackupPayload>(plain)
    }

    suspend fun exportFromApp(dao: OrinDao, keys: KeyStoreManager, password: String, includeSecrets: Boolean): String {
        val secretMap = if (includeSecrets) {
            listOf(
                "api_key_gemini", "api_key_anthropic", "api_key_openai", "api_key_deepseek",
                "api_key_custom", "custom_base_url", "meta_ads_token", "meta_ad_account_id",
                "search_key_brave", "search_key_tavily", "search_key_serper", "search_key_serpapi",
            ).mapNotNull { k -> keys.getSecret(k)?.let { k to it } }.toMap()
        } else {
            emptyMap()
        }
        val payload = OrinBackupPayload(
            exportedAtMs = System.currentTimeMillis(),
            provider = keys.activeProviderId(),
            model = keys.activeModel(),
            themeStyle = keys.themeStyle(),
            secrets = secretMap,
            conversations = dao.getAllConversations().map { BackupConversation(it.id, it.title, it.updatedAtMs) },
            messages = dao.getAllMessages().map { BackupMessage(it.id, it.conversationId, it.isUser, it.text, it.toolSummary, it.createdAtMs) },
            memories = dao.getMemories().map { BackupMemory(it.id, it.key, it.value, it.category, it.createdAtMs) },
            tasks = dao.getAllTasks().map { BackupTask(it.id, it.title, it.priority, it.completed, it.createdAtMs) },
        )
        return encryptPayload(payload, password)
    }

    suspend fun importIntoApp(dao: OrinDao, keys: KeyStoreManager, encrypted: String, password: String): String {
        val payload = decryptPayload(encrypted, password)
        keys.setActiveProviderId(payload.provider)
        keys.setActiveModel(payload.model)
        keys.setThemeStyle(payload.themeStyle)
        payload.secrets.forEach { (k, v) -> keys.putSecret(k, v) }
        payload.conversations.forEach { dao.upsertConversation(ConversationEntity(it.id, it.title, it.updatedAtMs)) }
        payload.messages.forEach { dao.insertMessage(MessageEntity(it.id, it.conversationId, it.isUser, it.text, it.toolSummary, null, it.createdAtMs)) }
        payload.memories.forEach { dao.upsertMemory(MemoryEntity(it.id, it.key, it.value, it.category, it.createdAtMs)) }
        payload.tasks.forEach { dao.upsertTask(TaskEntity(it.id, it.title, it.priority, null, it.completed, it.createdAtMs)) }
        return "Imported ${payload.conversations.size} chats, ${payload.memories.size} memories, ${payload.tasks.size} tasks."
    }
}
