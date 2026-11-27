package com.orin.app.tools

import com.orin.app.agent.OrinTool
import com.orin.app.data.KeyStoreManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class SearchHit(val title: String, val url: String, val snippet: String)

object SearchParsers {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseBrave(body: String, max: Int): List<SearchHit> {
        val root = json.parseToJsonElement(body).jsonObject
        val results = root["web"]?.jsonObject?.get("results")?.jsonArray ?: return emptyList()
        return results.take(max).mapNotNull { el ->
            val obj = el.jsonObject
            val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val url = obj["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val desc = obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
            SearchHit(title, url, desc)
        }
    }

    fun parseTavily(body: String, max: Int): List<SearchHit> {
        val root = json.parseToJsonElement(body).jsonObject
        val results = root["results"]?.jsonArray ?: return emptyList()
        return results.take(max).mapNotNull { el ->
            val obj = el.jsonObject
            val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val url = obj["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val content = obj["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
            SearchHit(title, url, content)
        }
    }

    fun parseSerper(body: String, max: Int): List<SearchHit> {
        val root = json.parseToJsonElement(body).jsonObject
        val organic = root["organic"]?.jsonArray ?: return emptyList()
        return organic.take(max).mapNotNull { el ->
            val obj = el.jsonObject
            val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val url = obj["link"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val snippet = obj["snippet"]?.jsonPrimitive?.contentOrNull.orEmpty()
            SearchHit(title, url, snippet)
        }
    }

    fun parseDuckDuckGoLite(html: String, max: Int): List<SearchHit> {
        val linkRegex = Regex("""<a[^>]+class="[^"]*result__a[^"]*"[^>]+href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val snippetRegex = Regex("""class="[^"]*result__snippet[^"]*"[^>]*>(.*?)</""", RegexOption.DOT_MATCHES_ALL)
        val links = linkRegex.findAll(html).toList()
        val snippets = snippetRegex.findAll(html).toList()
        return links.take(max).mapIndexed { i, m ->
            val url = cleanHtml(m.groupValues[1])
            val title = cleanHtml(m.groupValues[2])
            val snip = snippets.getOrNull(i)?.groupValues?.get(1)?.let(::cleanHtml).orEmpty()
            SearchHit(title, url, snip)
        }
    }

    private fun cleanHtml(raw: String): String =
        raw.replace(Regex("<[^>]+>"), " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#x27;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("\\s+"), " ")
            .trim()
}

/**
 * Multi-provider live web search (Tavily / Brave / Serper / SerpApi + keyless DuckDuckGo fallback).
 */
class WebSearchTool(
    private val keys: KeyStoreManager,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .build(),
) : OrinTool {
    override val name = "web_search"
    override val description = "Search the public web for up-to-date answers and cited sources. Uses configured provider keys or keyless search fallback."
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("query") { put("type", "string"); put("description", "Search query") }
            putJsonObject("max_results") { put("type", "integer"); put("description", "Number of results (1..10, default 5)") }
        }
    }

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val formMedia = "application/x-www-form-urlencoded".toMediaType()

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val query = args["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (query.isEmpty()) return@withContext "Provide a 'query'."
        val max = (args["max_results"]?.jsonPrimitive?.intOrNull ?: 5).coerceIn(1, 10)

        keys.getSecret("search_key_tavily")?.let { key ->
            queryTavily(query, key, max)?.let { return@withContext format("Tavily", it) }
        }
        keys.getSecret("search_key_brave")?.let { key ->
            queryBrave(query, key, max)?.let { return@withContext format("Brave", it) }
        }
        keys.getSecret("search_key_serper")?.let { key ->
            querySerper(query, key, max)?.let { return@withContext format("Serper", it) }
        }
        queryDuckDuckGo(query, max)?.let { return@withContext format("DuckDuckGo", it) }
        "No web results found for '$query'."
    }

    private fun queryTavily(query: String, key: String, max: Int): List<SearchHit>? = runCatching {
        val body = buildJsonObject {
            put("api_key", key)
            put("query", query)
            put("max_results", max)
        }.toString().toRequestBody(jsonMedia)
        val req = Request.Builder().url("https://api.tavily.com/search").post(body).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else SearchParsers.parseTavily(resp.body?.string().orEmpty(), max).takeIf { it.isNotEmpty() }
        }
    }.getOrNull()

    private fun queryBrave(query: String, key: String, max: Int): List<SearchHit>? = runCatching {
        val enc = URLEncoder.encode(query, "UTF-8")
        val req = Request.Builder()
            .url("https://api.search.brave.com/res/v1/web/search?q=$enc&count=$max")
            .header("X-Subscription-Token", key)
            .header("Accept", "application/json")
            .get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else SearchParsers.parseBrave(resp.body?.string().orEmpty(), max).takeIf { it.isNotEmpty() }
        }
    }.getOrNull()

    private fun querySerper(query: String, key: String, max: Int): List<SearchHit>? = runCatching {
        val body = buildJsonObject { put("q", query); put("num", max) }.toString().toRequestBody(jsonMedia)
        val req = Request.Builder()
            .url("https://google.serper.dev/search")
            .header("X-API-KEY", key)
            .post(body).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else SearchParsers.parseSerper(resp.body?.string().orEmpty(), max).takeIf { it.isNotEmpty() }
        }
    }.getOrNull()

    private fun queryDuckDuckGo(query: String, max: Int): List<SearchHit>? = runCatching {
        val enc = URLEncoder.encode(query, "UTF-8")
        val req = Request.Builder()
            .url("https://html.duckduckgo.com/html/")
            .header("User-Agent", "Mozilla/5.0 (Android) Orin/1.6")
            .post("q=$enc".toRequestBody(formMedia))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else SearchParsers.parseDuckDuckGoLite(resp.body?.string().orEmpty(), max).takeIf { it.isNotEmpty() }
        }
    }.getOrNull()

    private fun format(provider: String, hits: List<SearchHit>): String =
        buildString {
            appendLine("Web Search ($provider):")
            hits.forEachIndexed { idx, hit ->
                appendLine("[${idx + 1}] ${hit.title} — ${hit.url}")
                if (hit.snippet.isNotBlank()) appendLine("    ${hit.snippet.take(220)}")
            }
        }.trimEnd()
}
