package com.aasa.eldercare.document

import android.content.Context
import android.net.Uri
import com.aasa.eldercare.agent.AgentAction
import com.aasa.eldercare.tools.DocumentReaderTool
import com.aasa.eldercare.tools.ToolNames
import com.aasa.eldercare.tools.ToolResult
import com.aasa.eldercare.tools.ToolResultKeys
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Shared camera analyzer for "Read this for me".
 *
 * Uses on-device OCR and then the local DocumentReaderTool to produce
 * elder-friendly guidance payloads for the UI.
 */
class DocumentReaderAnalyzer(
    private val context: Context,
    private val tool: DocumentReaderTool = DocumentReaderTool()
) {
    suspend fun analyze(uri: Uri): ToolResult {
        val image = InputImage.fromFilePath(context, uri)
        val ocrText = recognize(image).trim()
        val action = AgentAction(
            intent = "DOCUMENT_READING",
            riskLevel = "LOW",
            tool = ToolNames.DOCUMENT_READER,
            arguments = mapOf(ToolResultKeys.DOCUMENT_TEXT to ocrText),
            assistantResponse = "",
            rawResponse = null
        )
        return tool.execute(action)
    }

    private suspend fun recognize(image: InputImage): String =
        suspendCancellableCoroutine { continuation ->
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(image)
                .addOnSuccessListener { text ->
                    continuation.resume(text.text)
                }
                .addOnFailureListener { error ->
                    continuation.resumeWithException(error)
                }
                .addOnCompleteListener {
                    recognizer.close()
                }
            continuation.invokeOnCancellation {
                recognizer.close()
            }
        }
}
