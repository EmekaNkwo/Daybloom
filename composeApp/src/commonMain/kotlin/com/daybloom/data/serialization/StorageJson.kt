package com.daybloom.data.serialization

import com.daybloom.domain.models.RecurrenceRule
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * JSON used for columns that hold lists or small value objects. Lenient on read so that a malformed or
 * future-format value degrades to a safe default instead of crashing (see [decodeOr]).
 */
object StorageJson {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
        explicitNulls = false
    }

    private val stringList = ListSerializer(String.serializer())
    private val intList = ListSerializer(Int.serializer())

    fun encodeStrings(values: List<String>): String = json.encodeToString(stringList, values)
    fun decodeStrings(raw: String?): List<String> = decodeOr(emptyList()) { json.decodeFromString(stringList, raw.orEmpty()) }

    fun encodeInts(values: List<Int>): String = json.encodeToString(intList, values)
    fun decodeInts(raw: String?): List<Int> = decodeOr(emptyList()) { json.decodeFromString(intList, raw.orEmpty()) }

    fun encodeRecurrence(rule: RecurrenceRule?): String? = rule?.let { json.encodeToString(RecurrenceRule.serializer(), it) }
    fun decodeRecurrence(raw: String?): RecurrenceRule? =
        if (raw.isNullOrBlank()) null else decodeOr(null) { json.decodeFromString(RecurrenceRule.serializer(), raw) }

    inline fun <T> decodeOr(fallback: T, block: () -> T): T =
        try {
            block()
        } catch (e: IllegalArgumentException) {
            // SerializationException and value validation errors are both IllegalArgumentException.
            InvalidDataLog.record(e.message ?: "invalid stored value")
            fallback
        }
}

/** Keeps a bounded record of recovered invalid values so the app can surface "some data was repaired". */
object InvalidDataLog {
    private const val MAX = 50
    private val entries = ArrayDeque<String>()

    fun record(message: String) {
        if (entries.size >= MAX) entries.removeFirst()
        entries.addLast(message)
    }

    fun count(): Int = entries.size
}
