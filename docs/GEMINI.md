# Gemini Integration

This app includes an optional Google Gemini (Generative Language API) chat backend.

## 1. Add your API Key
Preferred (user-level): `~/.gradle/gradle.properties`
```
GEMINI_API_KEY=YOUR_KEY_HERE
GEMINI_MODEL=gemini-2.0-flash   # (optional, defaults automatically if omitted)
```

Alternative (shell / CI):
```
export GEMINI_API_KEY=YOUR_KEY_HERE
export GEMINI_MODEL=gemini-2.0-flash
./gradlew assembleDebug
```

Avoid committing keys to the repo. Never place secrets in project `gradle.properties` unless you are sure it will be gitignored.

## 2. BuildConfig Fields
At build time the following are injected (see `app/build.gradle.kts`):
- `BuildConfig.GEMINI_API_KEY`
- `BuildConfig.GEMINI_MODEL`

## 3. Runtime Usage
`MainActivity` wires a `GeminiChatService` implementing `ChatBackend`:
```kotlin
private val chatBackend: ChatBackend by lazy {
    GeminiChatService(
        apiKey = BuildConfig.GEMINI_API_KEY,
        model = BuildConfig.GEMINI_MODEL,
        systemPrompt = "You are an on-device AR assistant. Keep answers short and easy to speak aloud."
    )
}
```

`GeminiChatService.sendMessage("Hi")` will:
1. Append user turn to an in-memory ring buffer (history capped).
2. POST JSON to `https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent` with header `X-goog-api-key`.
3. Parse first candidate text.
4. Sanitize for Text-To-Speech (single line, char-limit).

If the API key is blank you get a fallback string and **no network call** occurs.

## 4. Example cURL (mirrors internal logic)
```
curl "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent" \
  -H 'Content-Type: application/json' \
  -H "X-goog-api-key: $GEMINI_API_KEY" \
  -d '{
    "contents": [
      {"parts": [{"text": "Explain how AI works in a few words"}] }
    ]
  }'
```

## 5. Limits & Notes
- Only the first candidate/part is used (simple heuristic consistent with many quick use cases).
- Roles are converted: internal `assistant` -> Gemini `model`.
- System prompt mapped to `system_instruction` (optional field) when non-blank.
- No streaming implemented (single response). Can be extended by adding streaming endpoint when available.
- Timeout: connect 8s, read 25s.

## 6. Extending
To add streaming or tool calls, consider adding an abstraction layer or updating `GeminiChatService` to accept an injected `OkHttpClient` or `CallFactory` for testability.

## 7. Security
Treat the API key as sensitive. For production consider proxying through your own backend to apply rate limiting and usage auditing.

---

