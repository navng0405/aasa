package com.aasa.eldercare.agent

import java.util.Calendar
import kotlin.random.Random

/**
 * Phase 11: builds the warm, time-of-day-aware launch greeting that
 * Aasa speaks when the app comes to the foreground.
 *
 * Design intent (from AASA_PROJECT_OVERVIEW.md §1 — "a calm,
 * privacy-respecting agent"):
 *  - sound like a real caregiver / companion, not a chatbot
 *  - never name medical conditions (rule §3)
 *  - vary wording so repeat opens don't feel canned
 *  - keep it short (one sentence + one open question) — long lines
 *    fight against the deferred-action UX
 *
 * Pure function: no Android dependencies, trivially testable.
 */
object GreetingBuilder {

    enum class TimeOfDay { MORNING, AFTERNOON, EVENING, NIGHT }

    /**
     * @param userName elder's display name (or "friend" if unset)
     * @param hourOfDay 0..23 — usually [Calendar.HOUR_OF_DAY]
     * @param random injection point for tests; defaults to system random
     */
    fun build(
        userName: String,
        hourOfDay: Int = currentHour(),
        random: Random = Random.Default
    ): String {
        val name = userName.trim().ifBlank { "friend" }
        val tod = classifyHour(hourOfDay)
        val opener = pick(openersFor(tod), random)
        val check = pick(CHECK_INS, random)
        // Keep punctuation soft — TTS treats commas as gentle pauses,
        // which sounds far more human than back-to-back sentences.
        return "$opener, $name. $check"
    }

    fun buildWakeGreeting(
        userName: String,
        hourOfDay: Int = currentHour(),
        random: Random = Random.Default
    ): String {
        val name = userName.trim().ifBlank { "friend" }
        val check = pick(WAKE_CHECK_INS, random)
        return "Hello, $name. $check"
    }

    fun classifyHour(hour: Int): TimeOfDay = when (hour) {
        in 5..11 -> TimeOfDay.MORNING
        in 12..16 -> TimeOfDay.AFTERNOON
        in 17..20 -> TimeOfDay.EVENING
        else -> TimeOfDay.NIGHT
    }

    private fun openersFor(tod: TimeOfDay): List<String> = when (tod) {
        TimeOfDay.MORNING -> MORNING_OPENERS
        TimeOfDay.AFTERNOON -> AFTERNOON_OPENERS
        TimeOfDay.EVENING -> EVENING_OPENERS
        TimeOfDay.NIGHT -> NIGHT_OPENERS
    }

    private fun <T> pick(list: List<T>, random: Random): T =
        list[random.nextInt(list.size)]

    private fun currentHour(): Int =
        Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

    // ---- Copy banks ----------------------------------------------------
    // Kept intentionally small and elder-friendly. No diagnostic words,
    // no urgency, no "How may I assist you?" assistant-speak.

    private val MORNING_OPENERS = listOf(
        "Good morning",
        "Good morning to you",
        "A very good morning",
        "Morning"
    )

    private val AFTERNOON_OPENERS = listOf(
        "Good afternoon",
        "Hi"
    )

    private val EVENING_OPENERS = listOf(
        "Good evening",
        "Hello",
        "Evening"
    )

    private val NIGHT_OPENERS = listOf(
        "Hello",
        "It's good to hear from you"
    )

    /**
     * Companion-style check-in questions. Open-ended on purpose so
     * the elder can take the conversation anywhere — medication,
     * memory, a worry, or just chit-chat.
     */
    private val CHECK_INS = listOf(
        "How are you feeling today?",
        "How are you doing?",
        "It's lovely to hear from you. How are you today?",
        "I'm right here. How are you feeling?",
        "I'm here with you. How are you feeling?",
        "Take your time, I'm listening. How are you today?",
        "How has your day been so far?",
        "How are things with you today?"
    )

    private val WAKE_CHECK_INS = listOf(
        "I'm here with you. How are you feeling today?",
        "I'm listening. How can I help you?",
        "Take your time, I'm right here. What would you like to do?",
        "It's good to hear you. How are you today?"
    )
}
