package com.example.cosc3001

/** Abstraction for the active chat backend (local/stub or any remote service). */
interface ChatBackend {
    /** Sends a user message and returns a concise TTS-friendly response. */
    suspend fun sendMessage(userMessage: String): String
    fun clearHistory() {}

    /** Optional: set active focused dinosaur (normalized key) or null for general context. */
    fun setActiveFocusDino(dino: String?) {}
}
