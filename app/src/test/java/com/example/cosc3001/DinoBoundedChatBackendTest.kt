package com.example.cosc3001

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeBackend: ChatBackend {
    override suspend fun sendMessage(userMessage: String): String = "DELEGATED:$userMessage. Extra sentence one. Extra sentence two. Extra sentence three."
}

class DinoBoundedChatBackendTest {

    private val dinoTokens = listOf("t-rex", "trex", "tyrannosaurus", "spinosaurus", "triceratops", "velociraptor", "raptor")
    private fun String.hasDino(): Boolean = dinoTokens.any { this.lowercase().contains(it) }

    @Test
    fun `off-topic question yields gentle redirect with fact`() = runBlocking {
        val bounded = DinoBoundedChatBackend(FakeBackend())
        val reply = bounded.sendMessage("What's the weather today?")
        assertTrue("Expected a dinosaur fact in redirect", reply.hasDino())
        assertTrue("Should contain gentle redirect cue", reply.lowercase().contains("focus") || reply.lowercase().contains("exhibit"))
    }

    @Test
    fun `on-topic keyword passes to delegate and preserves dino context`() = runBlocking {
        val bounded = DinoBoundedChatBackend(FakeBackend())
        val reply = bounded.sendMessage("Tell me about the Triceratops horns")
        assertTrue("Delegate response should include user dinosaur keyword", reply.lowercase().contains("triceratops"))
        assertTrue("Should not exceed 3 sentences", reply.split(Regex("(?<=[.!?])\\s+")).size <= 3)
    }

    @Test
    fun `greeting without dino returns greeting plus fact`() = runBlocking {
        val bounded = DinoBoundedChatBackend(FakeBackend())
        val reply = bounded.sendMessage("Hello")
        assertTrue("Greeting response should include a dinosaur fact", reply.hasDino())
        assertTrue("Greeting tone missing", reply.lowercase().contains("hi") || reply.lowercase().contains("guide") || reply.lowercase().contains("fact"))
    }

    @Test
    fun `broad generic prompt returns a dinosaur fact`() = runBlocking {
        val bounded = DinoBoundedChatBackend(FakeBackend())
        val reply = bounded.sendMessage("Tell me something")
        assertTrue("Broad generic should yield a dinosaur fact", reply.hasDino())
    }

    @Test
    fun `exhibit related but non-specific query returns redirect fact`() = runBlocking {
        val bounded = DinoBoundedChatBackend(FakeBackend())
        val reply = bounded.sendMessage("Tell me about this exhibit")
        assertTrue("Redirect should still contain dinosaur context", reply.hasDino())
    }

    @Test
    fun `post filter injects fact if delegate lacks dinosaur term`() = runBlocking {
        val backend = object : ChatBackend { override suspend fun sendMessage(userMessage: String) = "General answer without specific creature." }
        val bounded = DinoBoundedChatBackend(backend)
        val reply = bounded.sendMessage("Explain T-Rex vision") // user input has T-Rex so maybe retained
        assertTrue("Post-filter should ensure dinosaur mention", reply.hasDino())
    }

    @Test
    fun `off topic with focus is interpreted as that dinosaur`() = runBlocking {
        val bounded = DinoBoundedChatBackend(FakeBackend())
        bounded.setActiveFocusDino("t-rex")
        val reply = bounded.sendMessage("What is the capital of France?")
        val low = reply.lowercase()
        assertTrue("Should interpret generic question in context of focused dino", low.contains("about the t-rex") || low.contains("t-rex"))
    }

    @Test
    fun `off topic after focus reset does not keep prior focus context phrase`() = runBlocking {
        val bounded = DinoBoundedChatBackend(FakeBackend())
        bounded.setActiveFocusDino("t-rex")
        // reset focus
        bounded.setActiveFocusDino(null)
        val reply = bounded.sendMessage("What is the capital of France?")
        val low = reply.lowercase()
        // We specifically ensure the enriched focus phrase isn't leaking; random fact may still mention t-rex, so we only forbid the pattern
        assertTrue("Should not contain enriched focus phrase after reset", !low.contains("about the t-rex:"))
    }

    @Test
    fun `pronoun resolution uses focus dinosaur`() = runBlocking {
        val bounded = DinoBoundedChatBackend(FakeBackend())
        bounded.setActiveFocusDino("spinosaurus")
        val reply = bounded.sendMessage("What about its claws?")
        val low = reply.lowercase()
        assertTrue("Pronoun-based query should reference focus dinosaur", low.contains("spinosaurus"))
        assertTrue("Should include user content transformed", low.contains("claws"))
    }
}
