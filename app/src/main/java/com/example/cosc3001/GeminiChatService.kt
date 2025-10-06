package com.example.cosc3001

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Chat backend for Google Gemini (Generative Language API). */
class GeminiChatService(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    private val maxHistory: Int = 12,
    private val maxOutputChars: Int = 400
) : ChatBackend {

    private data class Turn(val role: String, val text: String)
    private val history = ArrayDeque<Turn>()

    companion object {
        private const val TAG = "GeminiChat"
        private const val DEFAULT_MODEL = "gemini-2.0-flash"
        private const val DEFAULT_SYSTEM_PROMPT = "You are an AR scene assistant. Be concise, plain and easy to speak aloud. Avoid markdown." // kept short
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        private val MEDIA_JSON = "application/json; charset=utf-8".toMediaType()
        private const val ENDPOINT_BASE = "https://generativelanguage.googleapis.com/v1beta/models/"
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun sendMessage(userMessage: String): String {
        val trimmed = userMessage.trim()
        if (trimmed.isEmpty()) return ""
        if (apiKey.isBlank()) {
            return fallbackNoKey(trimmed)
        }
        // Record user turn
        history.addLast(Turn("user", trimmed))
        while (history.size > maxHistory) history.removeFirst()

        return try {
            val requestObj = buildRequestPayload()
            val bodyJson = json.encodeToString(GeminiRequest.serializer(), requestObj)
            val url = ENDPOINT_BASE + model + ":generateContent"
            val req = Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .header("X-goog-api-key", apiKey)
                .post(bodyJson.toRequestBody(MEDIA_JSON))
                .build()
            val replyText = withContext(Dispatchers.IO) {
                client.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string() // nullable
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "HTTP ${resp.code} body=${raw?.take(160)}")
                        return@use fallbackHttp(resp.code)
                    }
                    val parsed = raw?.let { runCatching { json.decodeFromString(GeminiResponse.serializer(), it) }.getOrNull() }
                    val candidateText = parsed?.candidates
                        ?.firstOrNull()
                        ?.content
                        ?.parts
                        ?.firstOrNull()
                        ?.text
                    val cleaned = candidateText?.trim().orEmpty()
                    if (cleaned.isBlank()) fallbackEmpty() else sanitizeForTTS(cleaned)
                }
            }
            history.addLast(Turn("assistant", replyText))
            while (history.size > maxHistory) history.removeFirst()
            replyText
        } catch (t: Throwable) {
            Log.e(TAG, "Gemini request failed: ${t.message}", t)
            fallbackException(t)
        }
    }

    override fun clearHistory() { history.clear() }

    private fun buildRequestPayload(): GeminiRequest {
        val contentList = mutableListOf<GeminiContent>()
        // Represent system prompt using system_instruction (per API optional) if non-blank
        val system = systemPrompt.takeIf { it.isNotBlank() }?.let {
            GeminiSystemInstruction(parts = listOf(GeminiPart(text = it)))
        }
        // Convert history: role must be "user" or "model" for Gemini (model=assistant)
        history.forEach { turn ->
            val role = when (turn.role) {
                "assistant" -> "model"
                else -> "user"
            }
            contentList.add(GeminiContent(role = role, parts = listOf(GeminiPart(text = turn.text))))
        }
        return GeminiRequest(
            contents = contentList,
            systemInstruction = system
        )
    }

    private fun sanitizeForTTS(text: String): String = text
        .replace('\n', ' ')
        .replace(Regex("\\s+"), " ")
        .take(maxOutputChars)
        .trim()

    private fun fallbackNoKey(question: String) = "Gemini API key missing. Asked: '${question.take(40)}'"
    private fun fallbackHttp(code: Int) = "Gemini error $code. Try later."
    private fun fallbackEmpty() = "I don't have a response right now."
    private fun fallbackException(t: Throwable) = "Network issue: ${t.javaClass.simpleName}".trim()
}

// --- Serialization models ---
@Serializable
data class GeminiRequest(
    val contents: List<GeminiContent>,
    @SerialName("system_instruction") val systemInstruction: GeminiSystemInstruction? = null
)

@Serializable
data class GeminiSystemInstruction(
    val parts: List<GeminiPart>
)

@Serializable
data class GeminiContent(
    val role: String? = null,
    val parts: List<GeminiPart>
)

@Serializable
data class GeminiPart(
    val text: String? = null
)

@Serializable
data class GeminiResponse(
    val candidates: List<GeminiCandidate>? = null
)

@Serializable
data class GeminiCandidate(
    val content: GeminiContent? = null
)
