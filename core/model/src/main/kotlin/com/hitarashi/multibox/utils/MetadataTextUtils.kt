package com.hitarashi.multibox.utils

import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.text.Normalizer

private val WINDOWS_1252: Charset = Charset.forName("windows-1252")

/**
 * Attempts to fix incorrectly encoded metadata strings that frequently appear when
 * tags are saved using Windows-1252/ISO-8859-1 but are later read as UTF-8.
 */
fun String?.normalizeMetadataText(): String? {
    if (this == null) return null
    val trimmed = this.trim()
    if (trimmed.isEmpty()) return trimmed

    val candidate = repairUtf8Mojibake(trimmed) ?: trimmed
    val cleaned = candidate.replace("\u0000", "")
    return Normalizer.normalize(cleaned, Normalizer.Form.NFC)
}

private val MOJIBAKE_LEAD_CHARS = charArrayOf('Ã', 'Â', 'â', 'Å', 'Æ', 'Ð', 'ð')

internal fun repairUtf8Mojibake(text: String): String? {
    if (text.none { it in MOJIBAKE_LEAD_CHARS }) return null
    return runCatching {
        val bytes = WINDOWS_1252.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(text))
        val decoded = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(bytes)
            .toString()
            .trim()
        decoded.takeIf { it.isNotEmpty() && it != text }
    }.getOrNull()
}

fun String?.normalizeMetadataTextOrEmpty(): String {
    return normalizeMetadataText() ?: ""
}

private const val ESCAPE_SEQUENCE = "\\\\"
private const val ESCAPE_PLACEHOLDER = "\u0000ESCAPED\u0000"

val DEFAULT_WORD_DELIMITERS = listOf(
    "featuring", "feat.", "feat", "ft.", "ft", "vs.", "vs", "versus", "with", "prod.", "prod"
)

fun String.splitArtistsByDelimiters(
    delimiters: List<String>,
    wordDelimiters: List<String> = DEFAULT_WORD_DELIMITERS
): List<String> {
    if ((delimiters.isEmpty() && wordDelimiters.isEmpty()) || this.isBlank()) {
        return listOf(this.trim()).filter { it.isNotEmpty() }
    }

    val sortedDelimiters = delimiters.sortedByDescending { it.length }
    var working = this

    val escapedMappings = mutableMapOf<String, String>()
    sortedDelimiters.forEachIndexed { index, delimiter ->
        val escapedDelimiter = ESCAPE_SEQUENCE + delimiter
        val placeholder = "${ESCAPE_PLACEHOLDER}${index}${ESCAPE_PLACEHOLDER}"
        escapedMappings[placeholder] = delimiter
        working = working.replace(escapedDelimiter, placeholder)
    }

    val patternParts = mutableListOf<String>()
    val sortedWordDelimiters = wordDelimiters.sortedByDescending { it.length }
    for (wd in sortedWordDelimiters) {
        val escaped = Regex.escape(wd)
        if (wd.length == 1) {
            patternParts.add("\\s+$escaped\\s+")
        } else {
            patternParts.add("\\s+$escaped\\s+|\\s+$escaped$|^$escaped\\s+")
        }
    }

    if (sortedDelimiters.isNotEmpty()) {
        val charPattern = sortedDelimiters.joinToString("|") {
            if (it == "+") """(?<=\s)\+(?=\s)""" else Regex.escape(it)
        }
        patternParts.add(charPattern)
    }

    if (patternParts.isEmpty()) {
        return listOf(this.trim()).filter { it.isNotEmpty() }
    }

    val combinedPattern = patternParts.joinToString("|")
    val regex = Regex(combinedPattern, RegexOption.IGNORE_CASE)

    return working.split(regex)
        .map { part ->
            var restored = part
            escapedMappings.forEach { (placeholder, delimiter) ->
                restored = restored.replace(placeholder, delimiter)
            }
            restored.trim()
        }
        .filter { it.isNotEmpty() }
        .distinct()
        .ifEmpty { if (this.trim().isNotEmpty()) listOf(this.trim()) else emptyList() }
}

fun String.extractArtistsFromTitle(
    delimiters: List<String> = emptyList(),
    wordDelimiters: List<String> = DEFAULT_WORD_DELIMITERS
): Pair<String, List<String>> {
    if (this.isBlank()) return this to emptyList()

    val featureKeywords = listOf("featuring", "feat\\.", "feat", "ft\\.", "ft", "with", "prod\\.", "prod")
    val keywordPattern = featureKeywords.joinToString("|")
    val bracketPattern = Regex(
        """[\(\[]\\?\s*(?:$keywordPattern)\s+(.+?)\s*[\)\]]""",
        RegexOption.IGNORE_CASE
    )

    val extractedArtists = mutableListOf<String>()
    var cleanedTitle = this

    bracketPattern.findAll(this).forEach { match ->
        val artistString = match.groupValues[1]
        val artists = artistString.splitArtistsByDelimiters(delimiters, wordDelimiters)
        extractedArtists.addAll(artists)
        cleanedTitle = cleanedTitle.replace(match.value, "")
    }

    return cleanedTitle.trim() to extractedArtists.distinct()
}
