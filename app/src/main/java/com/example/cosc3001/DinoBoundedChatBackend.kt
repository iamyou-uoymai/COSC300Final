package com.example.cosc3001

/**
 * Dinosaur-focused guidance wrapper.
 * Now permissive: always returns a dinosaur-oriented response.
 * - On-topic queries delegate to the backend and post-filter to keep concise.
 * - Off-topic, broad, or greeting prompts return a gentle redirect plus a random dinosaur fact.
 * - Ensures final answer includes at least one exhibit dinosaur reference (injects a fact if missing).
 * - NEW: When an active focus dinosaur is set, generic queries are interpreted about that dinosaur.
 */
class DinoBoundedChatBackend(
    private val delegate: ChatBackend,
    private val refusalMessage: String = "I can only answer about T-Rex, Spinosaurus, Triceratops or Velociraptor.",
    private val maxSentences: Int = 3,
    private val maxChars: Int = 400,
    private val greetingMessage: String = "Hi! I'm your museum dinosaur guide. You can ask me about T-Rex, Spinosaurus, Triceratops or Velociraptor."
) : ChatBackend {

    private val dinoKeywords = listOf(
        "trex", "t-rex", "t rex", "tyrannosaurus", "tyrannosaurus rex",
        "spino", "spinosaurus",
        "triceratops",
        "velociraptor", "raptor"
    )
    private val canonicalMap = mapOf(
        // normalized (remove spaces/dashes) -> canonical display name
        "trex" to "T-Rex",
        "tyrannosaurusrex" to "T-Rex",
        "spinosaurus" to "Spinosaurus",
        "triceratops" to "Triceratops",
        "velociraptor" to "Velociraptor",
        "raptor" to "Velociraptor"
    )
    private val greetingKeywords = listOf("hi", "hello", "hey", "welcome", "guide")
    private val broadQueries = setOf(
        "tell me something", "tell me a fact", "give me a fact", "something interesting", "anything", "surprise me"
    )

    private val generalFacts = listOf(
        "T-Rex had one of the most powerful bites of any land animal.",
        "Spinosaurus likely spent time in water hunting fish.",
        "Triceratops used its three horns and frill for display and defense.",
        "Velociraptor had a large sickle-shaped claw on each hind foot.",
        "Spinosaurus's sail may have helped with display or temperature regulation.",
        "Velociraptor was smaller than in movies—about the size of a large turkey."
    )
    private val perDinoFacts = mapOf(
        "T-Rex" to listOf(
            "T-Rex could see movement very well, aiding its predatory behavior.",
            "A T-Rex tooth could be longer than a human hand.",
            "Its bite force may have exceeded 5,000 kilograms."),
        "Spinosaurus" to listOf(
            "Spinosaurus had conical teeth ideal for gripping slippery prey.",
            "Its center of mass suggests strong forelimbs for grabbing fish.",
            "Fossils hint at semi-aquatic adaptations."),
        "Triceratops" to listOf(
            "Triceratops had a parrot-like beak for cropping tough plants.",
            "Its frill might have helped with species recognition.",
            "Juvenile Triceratops frills changed shape as they aged."),
        "Velociraptor" to listOf(
            "Velociraptor likely had feathers.",
            "It used its sickle claw perhaps more for pinning than slashing.",
            "Its skull was lightly built and flexible for quick bites.")
    )

    private fun randomGeneralFact(): String = generalFacts.random()
    private fun randomFactFor(display: String): String = perDinoFacts[display]?.random() ?: randomGeneralFact()

    @Volatile private var activeFocusDisplay: String? = null

    override fun setActiveFocusDino(dino: String?) {
        activeFocusDisplay = dino?.let { normalizeToDisplay(it) }
    }

    private fun normalizeToDisplay(raw: String): String? {
        val key = raw.lowercase().replace(" ", "").replace("-", "")
        return canonicalMap[key]
    }

    override suspend fun sendMessage(userMessage: String): String {
        val msg = userMessage.trim()
        if (msg.isEmpty()) return ""
        val lower = msg.lowercase()

        val isGreeting = greetingKeywords.any { lower.startsWith(it) }
        val hasDino = containsDino(lower)
        val isBroad = isBroadGeneric(lower)
        val focus = activeFocusDisplay

        // Greeting without dinosaur & with focus
        if (isGreeting && !hasDino && focus != null) {
            return postFilter("You're looking at the $focus. ${randomFactFor(focus)}")
        }

        // Generic greeting without focus
        if (isGreeting && focus == null && !hasDino) {
            return postFilter("$greetingMessage Here's a quick fact: ${randomGeneralFact()}")
        }

        // Broad generic prompts -> supply fact (focused if available)
        if (isBroad && !hasDino) {
            val fact = focus?.let { randomFactFor(it) } ?: randomGeneralFact()
            return postFilter("Here's a ${focus ?: "dinosaur"} fact: $fact")
        }

        // Off-topic & focus available -> interpret as about focus
        if (!hasDino && focus != null) {
            // If user refers with pronouns or generic wording, still treat as focus context
            val enriched = "About the $focus: $msg"
            val raw = delegate.sendMessage(enriched)
            return postFilter(raw.ifBlank { randomFactFor(focus) })
        }

        // Off-topic & no focus
        if (!hasDino) {
            return postFilter("I focus on our exhibit dinosaurs. Here's something interesting: ${randomGeneralFact()}")
        }

        // On-topic -> delegate
        val raw = delegate.sendMessage(msg)
        return postFilter(raw)
    }

    private fun containsDino(lower: String): Boolean = dinoKeywords.any { it in lower }
    private fun isBroadGeneric(lower: String): Boolean = lower in broadQueries

    private fun postFilter(ans: String): String {
        val trimmed = ans.trim()
        if (trimmed.isEmpty()) return randomGeneralFact()
        val sentences = trimmed.split(Regex("(?<=[.!?])\\s+")).take(maxSentences)
        var joined = sentences.joinToString(" ") { it.trim() }.take(maxChars).trim()
        val lower = joined.lowercase()
        if (!dinoKeywords.any { it in lower }) {
            // Prefer focus fact if available, else general
            val focus = activeFocusDisplay
            val fact = focus?.let { randomFactFor(it) } ?: randomGeneralFact()
            joined = "$joined $fact".trim()
        }
        return joined
    }

    override fun clearHistory() { delegate.clearHistory() }
}
