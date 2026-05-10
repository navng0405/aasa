package com.aasa.eldercare.tools

/**
 * Outcome of a single [AgentTool.execute] call.
 *
 * Tools never throw for "expected" failures (e.g. missing argument); they
 * return a failed [ToolResult] instead so the orchestrator can surface
 * the message to the UI uniformly.
 */
data class ToolResult(
    val success: Boolean,
    val message: String,
    val data: Map<String, Any?> = emptyMap()
) {
    companion object {
        fun ok(message: String, data: Map<String, Any?> = emptyMap()): ToolResult =
            ToolResult(success = true, message = message, data = data)

        fun fail(message: String, data: Map<String, Any?> = emptyMap()): ToolResult =
            ToolResult(success = false, message = message, data = data)
    }
}
