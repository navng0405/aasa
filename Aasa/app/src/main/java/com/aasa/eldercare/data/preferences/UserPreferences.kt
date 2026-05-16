package com.aasa.eldercare.data.preferences

import android.content.Context
import android.content.SharedPreferences

/**
 * Phase 11: tiny preferences store for personalization state that
 * doesn't deserve a Room table.
 *
 * Currently holds:
 *  - the elder's preferred name ("Naveen"), shown in the greeting
 *    ("Good morning, Naveen.")
 *  - the timestamp of the last spoken greeting, so we don't re-greet
 *    every recomposition/back-nav within the same session.
 *
 * Stored in app-private [SharedPreferences] — no cloud sync, no PII
 * leaves the device. Stays consistent with the "Local-first privacy"
 * non-negotiable from AASA_PROJECT_OVERVIEW.md §3.
 */
class UserPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Display name used in the launch greeting. Defaults to [DEFAULT_NAME]. */
    var userName: String
        get() = prefs.getString(KEY_USER_NAME, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_NAME
        set(value) {
            prefs.edit().putString(KEY_USER_NAME, value.trim()).apply()
        }

    /** Epoch-ms of the most recent spoken launch greeting, or 0 if never. */
    var lastGreetingAtMs: Long
        get() = prefs.getLong(KEY_LAST_GREETING_AT, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_GREETING_AT, value).apply()
        }

    /**
     * Phase 12: whether the elder has opted in to the always-on
     * "Hey Aasa" hotword foreground service. Default is `false` —
     * we never enable background mic listening implicitly.
     */
    var hotwordEnabled: Boolean
        get() = prefs.getBoolean(KEY_HOTWORD_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_HOTWORD_ENABLED, value).apply()
        }

    fun shouldGreet(nowMs: Long, cooldownMs: Long = DEFAULT_COOLDOWN_MS): Boolean {
        val last = lastGreetingAtMs
        if (last == 0L) return true
        return (nowMs - last) >= cooldownMs
    }

    fun markGreeted(nowMs: Long) {
        lastGreetingAtMs = nowMs
    }

    companion object {
        private const val PREFS_NAME = "aasa_user_prefs"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_LAST_GREETING_AT = "last_greeting_at"
        private const val KEY_HOTWORD_ENABLED = "hotword_enabled"

        const val DEFAULT_NAME: String = "friend"

        /**
         * Re-greet only if more than this long has passed since the
         * last greeting. Short enough that returning after a real
         * break feels welcoming, long enough that quick back-nav
         * doesn't re-trigger.
         */
        const val DEFAULT_COOLDOWN_MS: Long = 5L * 60L * 1000L
    }
}
