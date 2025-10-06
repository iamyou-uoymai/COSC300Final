package com.example.cosc3001

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class GeminiChatServiceTest {

    @Test
    fun `blank api key triggers fallback without crash`() = runBlocking {
        val svc = GeminiChatService(apiKey = "", model = "gemini-2.0-flash")
        val reply = svc.sendMessage("Hello there")
        assertTrue(reply.contains("API key missing", ignoreCase = true))
    }

    @Test
    fun `empty user input returns empty string`() = runBlocking {
        val svc = GeminiChatService(apiKey = "test")
        val reply = svc.sendMessage("   ")
        assertEquals("", reply)
    }
}

