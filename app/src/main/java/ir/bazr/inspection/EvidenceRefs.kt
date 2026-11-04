package ir.bazr.inspection

import org.json.JSONArray
import org.json.JSONObject

/**
 * Backward-compatible per-question evidence references.
 * Versions <=0.7 stored one String; 0.8 stores a JSONArray when edited.
 * Files are only ever deleted on explicit user removal or confirmed server receipt.
 */
object EvidenceRefs {
    const val MAX_PER_QUESTION = 4

    fun references(attachments: JSONObject, question: String): List<String> {
        return when (val value = attachments.opt(question)) {
            is JSONArray -> (0 until value.length()).mapNotNull { i ->
                value.optString(i).takeIf { it.isNotBlank() }
            }.distinct()
            is String -> listOfNotNull(value.takeIf { it.isNotBlank() })
            else -> emptyList()
        }
    }

    fun count(attachments: JSONObject): Int {
        val ids = attachments.keys()
        var total = 0
        while (ids.hasNext()) total += references(attachments, ids.next()).size
        return total
    }

    fun add(attachments: JSONObject, question: String, reference: String): Boolean {
        if (question.isBlank() || reference.isBlank()) return false
        val values = references(attachments, question)
        if (reference in values || values.size >= MAX_PER_QUESTION) return false
        write(attachments, question, values + reference)
        return true
    }

    fun remove(attachments: JSONObject, question: String, reference: String): Boolean {
        val previous = references(attachments, question)
        if (reference !in previous) return false
        write(attachments, question, previous.filterNot { it == reference })
        return true
    }

    fun all(attachments: JSONObject): Set<String> {
        val ids = attachments.keys()
        val result = linkedSetOf<String>()
        while (ids.hasNext()) result.addAll(references(attachments, ids.next()))
        return result
    }

    private fun write(attachments: JSONObject, question: String, refs: List<String>) {
        if (refs.isEmpty()) attachments.remove(question)
        else attachments.put(question, JSONArray().also { array -> refs.forEach { array.put(it) } })
    }
}
