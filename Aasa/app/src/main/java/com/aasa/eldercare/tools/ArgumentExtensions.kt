package com.aasa.eldercare.tools

/**
 * Small helpers for reading values out of [com.aasa.eldercare.agent.AgentAction.arguments].
 * Tools should never crash because Gemma left a key out – these helpers
 * always return null on a miss.
 */

internal fun Map<String, Any?>.stringOrNull(key: String): String? {
    val raw = this[key] ?: return null
    return raw.toString().takeIf { it.isNotBlank() }
}

internal fun Map<String, Any?>.flattenToText(): String =
    entries.joinToString(separator = ", ") { (k, v) -> "$k=${v ?: "null"}" }
