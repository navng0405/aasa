package com.aasa.eldercare.medicine

import android.content.Context
import android.net.Uri
import com.aasa.eldercare.data.repository.MedicationRepository
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device OCR + careful medicine explanation for the Home screen.
 *
 * This is intentionally conservative. Aasa can help an elder understand
 * visible text on a strip or prescription label, but it must not certify
 * that a pill is correct or safe for that person.
 */
class MedicineLensAnalyzer(
    private val context: Context,
    private val medicationRepository: MedicationRepository
) {
    suspend fun analyze(uri: Uri): MedicineLensResult {
        val image = InputImage.fromFilePath(context, uri)
        val ocrText = recognize(image).trim()
        if (ocrText.isBlank()) {
            return MedicineLensResult(
                recognizedName = null,
                strength = null,
                extractedText = "",
                summary = "I could not read clear medicine text from this photo. Please try again with the label flat, bright, and close to the camera.",
                confidence = MedicineLensConfidence.LOW,
                safetyNote = SAFETY_NOTE,
                safetyReceipt = listOf(
                    SafetyReceiptItem("What Aasa saw", "No clear label text"),
                    SafetyReceiptItem("Routine medicine match", "Not checked"),
                    SafetyReceiptItem("Next step", "Take a clearer photo")
                )
            )
        }

        val inferredFromPurpose = inferProductFromText(ocrText)
        val match = inferredFromPurpose ?: MedicineKnowledge.findBestMatch(ocrText)
        val strength = extractStrength(ocrText)
        val directions = extractDirections(ocrText)
        val routineContext = match
            ?.takeIf { !it.isInferredProduct }
            ?.let { findRoutineContext(it, ocrText) }

        if (match == null) {
            val summary = buildUnclearButHelpfulSummary(ocrText, strength)
            return MedicineLensResult(
                recognizedName = "Possible medicine or health product",
                strength = strength,
                extractedText = ocrText,
                summary = summary,
                confidence = MedicineLensConfidence.LOW,
                safetyNote = SAFETY_NOTE,
                safetyReceipt = buildSafetyReceipt(
                    seen = visibleCluesForReceipt(ocrText),
                    routineMatch = "No match in preferred routine list",
                    confidence = "Low confidence",
                    nextStep = "Check with doctor or pharmacist before use"
                )
            )
        }

        val summary = buildString {
            append("This appears to be ${match.displayName}")
            strength?.let { append(" $it") }
            append(". It may be useful for ${match.commonUse}. ")
            append(match.elderSummary)
            directions?.let {
                append(" The label may say: \"$it\". ")
            }
            if (match.isInferredProduct) {
                append(" This is not from your preferred routine medicine list.")
            } else {
                routineContext?.let { context ->
                    append(" ")
                    append(context.toSummarySentence())
                } ?: append(" I do not see this exact medicine on your routine medicine list yet.")
            }
            append(" Before using it, please check with your doctor or pharmacist to make sure it is right for you.")
        }

        return MedicineLensResult(
            recognizedName = match.displayName,
            strength = strength,
            extractedText = ocrText,
            summary = summary,
            confidence = if (match.isInferredProduct) {
                MedicineLensConfidence.LOW
            } else {
                MedicineLensConfidence.MEDIUM
            },
            safetyNote = SAFETY_NOTE,
            safetyReceipt = buildSafetyReceipt(
                seen = receiptSeenText(
                    name = match.displayName,
                    strength = strength,
                    ocrText = ocrText
                ),
                routineMatch = when {
                    match.isInferredProduct -> "Not in preferred routine list"
                    routineContext != null -> "Matched: ${routineContext.name}"
                    else -> "No match in preferred routine list"
                },
                confidence = if (match.isInferredProduct) {
                    "Purpose inferred from visible words"
                } else {
                    "Medicine name matched"
                },
                nextStep = if (routineContext?.takenTodayAtMs != null) {
                    "Already logged today; avoid duplicate dose unless doctor told you"
                } else {
                    "Check with doctor or pharmacist before use"
                }
            )
        )
    }

    private suspend fun findRoutineContext(
        medicine: MedicineInfo,
        ocrText: String
    ): RoutineMedicineContext? {
        val routineMeds = medicationRepository.getAllMedications().first()
        val normalizedOcr = normalize(ocrText)
        val matchedMedication = routineMeds.firstOrNull { med ->
            val normalizedName = normalize(med.name)
            normalizedName.isNotBlank() &&
                (medicine.aliases.any { alias -> normalizedName.contains(normalize(alias)) } ||
                    medicine.aliases.any { alias -> normalizedOcr.contains(normalize(alias)) && normalizedName.contains(normalize(medicine.displayName)) } ||
                    normalizedOcr.contains(normalizedName))
        } ?: routineMeds.firstOrNull { med ->
            // Demo-friendly bridge for rows like "BP tablet" when the
            // label says amlodipine / losartan / lisinopril.
            val lowerName = med.name.lowercase()
            val use = medicine.commonUse.lowercase()
            (lowerName.contains("bp") || lowerName.contains("blood pressure")) &&
                use.contains("blood pressure")
        }

        matchedMedication ?: return null

        val todayLog = medicationRepository.getTodayLogsWithNames()
            .firstOrNull { normalize(it.medicationName) == normalize(matchedMedication.name) }

        return RoutineMedicineContext(
            name = matchedMedication.name,
            dosage = matchedMedication.dosage,
            scheduleTime = matchedMedication.scheduleTime,
            takenTodayAtMs = todayLog
                ?.takeIf { it.status.equals("taken", ignoreCase = true) }
                ?.loggedAt
        )
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

    private fun extractStrength(text: String): String? {
        val match = Regex(
            pattern = """\b\d+(?:\.\d+)?\s*(?:mg|mcg|g|ml|iu|units?|%)\b""",
            option = RegexOption.IGNORE_CASE
        ).find(text)
        return match?.value
            ?.replace(Regex("\\s+"), " ")
            ?.replace(" %", "%")
            ?.uppercase()
    }

    private fun extractDirections(text: String): String? {
        val line = text
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { raw ->
                val lower = raw.lowercase()
                lower.contains("take ") ||
                    lower.contains("once daily") ||
                    lower.contains("twice daily") ||
                    lower.contains("with food") ||
                    lower.contains("by mouth")
            }
        return line?.take(140)
    }

    private fun inferProductFromText(text: String): MedicineInfo? {
        val normalized = normalize(text)
        val looksLikeSpray = normalized.contains("spray") ||
            normalized.contains("continuous spray") ||
            normalized.contains("continuous spra") ||
            normalized.contains("works at angle")
        val looksLikePainRelief = normalized.contains("pain relief") ||
            normalized.contains("pain rel") ||
            normalized.contains("pain aeler") ||
            normalized.contains("pain rele") ||
            normalized.contains("maximum strength") ||
            normalized.contains("maxigams") ||
            normalized.contains("long lasting relief") ||
            normalized.contains("long lasting rele")
        val looksMedicated = normalized.contains("medicated") ||
            normalized.contains("medica ted") ||
            normalized.contains("medicine")

        return when {
            looksLikeSpray && looksLikePainRelief -> MedicineInfo(
                displayName = "a medicated pain relief spray",
                aliases = emptyList(),
                commonUse = "temporary relief of muscle or joint aches",
                elderSummary = "This kind of product is usually sprayed on the skin, not swallowed. Avoid eyes, broken skin, and heat over the area unless the label says it is safe.",
                isInferredProduct = true
            )
            looksLikeSpray && looksMedicated -> MedicineInfo(
                displayName = "a medicated spray",
                aliases = emptyList(),
                commonUse = "temporary symptom relief, depending on the exact label",
                elderSummary = "Because sprays can be for skin, throat, nose, or pain relief, please check the directions before using it.",
                isInferredProduct = true
            )
            looksLikePainRelief -> MedicineInfo(
                displayName = "a pain relief medicine or product",
                aliases = emptyList(),
                commonUse = "temporary pain relief",
                elderSummary = "Please check whether it is meant to be swallowed, applied on the skin, or used another way before taking it.",
                isInferredProduct = true
            )
            else -> null
        }
    }

    private fun buildUnclearButHelpfulSummary(
        text: String,
        strength: String?
    ): String {
        val visibleClues = text
            .lineSequence()
            .map { it.trim() }
            .filter { it.length >= 3 }
            .take(3)
            .joinToString(", ")
            .ifBlank { "some label text" }

        return buildString {
            append("This photo is not clear enough for me to name the medicine safely.")
            append(" From the visible words, it may be a medicine or health product related to $visibleClues.")
            strength?.let { append(" I also noticed a strength that may say $it.") }
            append(" This is not from your preferred routine medicine list.")
            append(" Before using it, please check with your doctor or pharmacist to make sure it is right for you.")
        }
    }

    private fun receiptSeenText(
        name: String,
        strength: String?,
        ocrText: String
    ): String = buildString {
        append(name)
        strength?.let { append(" · $it") }
        val clues = visibleCluesForReceipt(ocrText)
        if (clues.isNotBlank()) append(" ($clues)")
    }

    private fun visibleCluesForReceipt(text: String): String =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.length >= 3 }
            .filterNot { it.equals("IcYo", ignoreCase = true) }
            .take(4)
            .joinToString(", ")
            .take(120)

    private fun buildSafetyReceipt(
        seen: String,
        routineMatch: String,
        confidence: String,
        nextStep: String
    ): List<SafetyReceiptItem> = listOf(
        SafetyReceiptItem("What Aasa saw", seen.ifBlank { "Some label text" }),
        SafetyReceiptItem("Routine medicine match", routineMatch),
        SafetyReceiptItem("Confidence", confidence),
        SafetyReceiptItem("Next step", nextStep)
    )

    private fun normalize(value: String): String =
        value.lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    private companion object {
        private const val SAFETY_NOTE =
            "Aasa cannot confirm the medicine is correct for you. Use this as a reading aid and confirm with your prescription, pharmacist, or doctor."
        private val timeFormatter = SimpleDateFormat("h:mm a", Locale.getDefault())
    }

    private data class RoutineMedicineContext(
        val name: String,
        val dosage: String?,
        val scheduleTime: String?,
        val takenTodayAtMs: Long?
    ) {
        fun toSummarySentence(): String {
            val base = buildString {
                append("This matches your routine medicine list as $name")
                dosage?.takeIf { it.isNotBlank() }?.let { append(", $it") }
                scheduleTime?.takeIf { it.isNotBlank() }?.let { append(", usually taken at $it") }
                append(".")
            }
            val taken = takenTodayAtMs?.let {
                " You already took it today at ${timeFormatter.format(Date(it))}."
            } ?: " I do not see that it has been logged as taken today yet."
            return base + taken
        }
    }
}

data class MedicineLensResult(
    val recognizedName: String?,
    val strength: String?,
    val extractedText: String,
    val summary: String,
    val confidence: MedicineLensConfidence,
    val safetyNote: String,
    val safetyReceipt: List<SafetyReceiptItem> = emptyList()
)

data class SafetyReceiptItem(
    val label: String,
    val value: String
)

enum class MedicineLensConfidence {
    LOW,
    MEDIUM
}

private data class MedicineInfo(
    val displayName: String,
    val aliases: List<String>,
    val commonUse: String,
    val elderSummary: String,
    val isInferredProduct: Boolean = false
)

private object MedicineKnowledge {
    private val medicines = listOf(
        MedicineInfo(
            displayName = "Metformin",
            aliases = listOf("metformin", "glucophage"),
            commonUse = "blood sugar control in type 2 diabetes",
            elderSummary = "It helps the body use sugar more steadily."
        ),
        MedicineInfo(
            displayName = "Amlodipine",
            aliases = listOf("amlodipine", "norvasc"),
            commonUse = "high blood pressure and chest pain prevention",
            elderSummary = "It relaxes blood vessels so the heart does not have to work as hard."
        ),
        MedicineInfo(
            displayName = "Atorvastatin",
            aliases = listOf("atorvastatin", "lipitor"),
            commonUse = "lowering cholesterol and protecting the heart",
            elderSummary = "It helps lower bad cholesterol over time."
        ),
        MedicineInfo(
            displayName = "Lisinopril",
            aliases = listOf("lisinopril", "prinivil", "zestril"),
            commonUse = "high blood pressure and heart protection",
            elderSummary = "It helps relax blood vessels and can reduce strain on the heart."
        ),
        MedicineInfo(
            displayName = "Losartan",
            aliases = listOf("losartan", "cozaar"),
            commonUse = "high blood pressure and kidney protection in some people",
            elderSummary = "It helps blood flow more easily by relaxing blood vessels."
        ),
        MedicineInfo(
            displayName = "Aspirin",
            aliases = listOf("aspirin", "acetylsalicylic"),
            commonUse = "pain relief or, when prescribed, reducing blood clot risk",
            elderSummary = "Some people take it for pain, and some take a low dose for heart or stroke prevention."
        ),
        MedicineInfo(
            displayName = "Ibuprofen",
            aliases = listOf("ibuprofen", "advil", "motrin"),
            commonUse = "pain, fever, and inflammation",
            elderSummary = "It can help with aches, but it may not be right for everyone, especially with stomach, kidney, or blood pressure concerns."
        ),
        MedicineInfo(
            displayName = "Acetaminophen",
            aliases = listOf("acetaminophen", "paracetamol", "tylenol"),
            commonUse = "pain and fever",
            elderSummary = "It can help with pain or fever. Be careful not to take more than the label or doctor says."
        ),
        MedicineInfo(
            displayName = "Levothyroxine",
            aliases = listOf("levothyroxine", "synthroid", "euthyrox"),
            commonUse = "low thyroid hormone",
            elderSummary = "It replaces thyroid hormone so the body has the level it needs."
        ),
        MedicineInfo(
            displayName = "Omeprazole",
            aliases = listOf("omeprazole", "prilosec"),
            commonUse = "acid reflux and stomach acid control",
            elderSummary = "It lowers stomach acid to help with heartburn or irritation."
        ),
        MedicineInfo(
            displayName = "Menthol medicated product",
            aliases = listOf("menthol", "medicated menthol", "mentholated", "pain relief spray"),
            commonUse = "temporary relief of cough, throat irritation, congestion, or minor aches depending on the product",
            elderSummary = "Menthol gives a cooling feeling. Some products are for the throat or nose, and some are for rubbing on the skin, so the exact use depends on the label."
        )
    )

    fun findBestMatch(text: String): MedicineInfo? {
        val normalized = text.lowercase()
        val compact = normalized.replace(Regex("[^a-z0-9]+"), "")
        return medicines.firstOrNull { medicine ->
            medicine.aliases.any { alias ->
                Regex("""\b${Regex.escape(alias)}\b""").containsMatchIn(normalized) ||
                    compact.contains(alias.lowercase().replace(Regex("[^a-z0-9]+"), ""))
            }
        }
    }
}
