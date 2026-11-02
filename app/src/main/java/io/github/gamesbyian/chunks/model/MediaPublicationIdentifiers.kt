package io.github.gamesbyian.chunks.model

import java.util.Locale

/** Checksum-validated book *product/edition* identifier, never an abstract work identity. */
data class CheckedIsbn(val isbn13: String, val observedIsbn10: String?) {
    init { require(isbn13.length == 13 && isbn13.all { it.isDigit() }) }
}

/** Checksum-validated continuing-resource identifier; not the ID of a specific journal issue. */
data class CheckedIssn(val canonical: String) {
    init { require(canonical.matches(Regex("[0-9]{4}-[0-9]{3}[0-9X]"))) }
}

/**
 * Pure exact-field interpretation, never text scraping or provider lookup. International ISBN/ISSN
 * control digits detect transcription errors, but a valid checksum does not prove assignment.
 */
object MediaPublicationIdentifiers {
    private val isbnPrefix = Regex("(?i)^ISBN(?:-(10|13))?\\s*:?\\s*")
    private val issnPrefix = Regex("(?i)^ISSN\\s*:?\\s*")

    private fun stripSeparators(value: String): String? {
        if (!value.all { it.isDigit() || it == 'X' || it == 'x' || it == '-' || it.isWhitespace() }) return null
        return value.filterNot { it == '-' || it.isWhitespace() }.uppercase(Locale.ROOT)
    }

    /** Accept exactly a supplied ISBN field, with optional ISBN/ISBN-10/ISBN-13 prefix. */
    fun isbn(raw: String): CheckedIsbn? {
        val input = raw.trim()
        val prefix = isbnPrefix.find(input)
        val declaredLength = prefix?.groupValues?.get(1)?.takeIf { it.isNotBlank() }?.toInt()
        val digits = stripSeparators(input.substring(prefix?.range?.last?.plus(1) ?: 0)) ?: return null
        if (declaredLength != null && declaredLength != digits.length) return null
        return when {
            digits.matches(Regex("[0-9]{9}[0-9X]")) -> {
                val sum = digits.mapIndexed { index, char ->
                    (10 - index) * if (char == 'X') 10 else char.digitToInt()
                }.sum()
                if (sum % 11 != 0 || digits.all { it == '0' }) return null
                val base12 = "978" + digits.substring(0, 9)
                CheckedIsbn(base12 + isbn13CheckDigit(base12), digits)
            }
            digits.matches(Regex("[0-9]{13}")) && (digits.startsWith("978") || digits.startsWith("979")) -> {
                if (digits.all { it == '0' } || digits.last().digitToInt() != isbn13CheckDigit(digits.take(12))) return null
                CheckedIsbn(digits, null)
            }
            else -> null
        }
    }

    private fun isbn13CheckDigit(first12: String): Int {
        val weighted = first12.mapIndexed { index, char -> char.digitToInt() * if (index % 2 == 0) 1 else 3 }.sum()
        return (10 - weighted % 10) % 10
    }

    /** ISSN is tied to a continuing resource/key title; different formats can have different ISSNs. */
    fun issn(raw: String): CheckedIssn? {
        val input = raw.trim()
        val prefix = issnPrefix.find(input)
        val digits = stripSeparators(input.substring(prefix?.range?.last?.plus(1) ?: 0)) ?: return null
        if (!digits.matches(Regex("[0-9]{7}[0-9X]"))) return null
        if (digits.all { it == '0' }) return null
        val sum = digits.take(7).mapIndexed { index, char -> char.digitToInt() * (8 - index) }.sum()
        val check = if (digits.last() == 'X') 10 else digits.last().digitToInt()
        return if ((sum + check) % 11 == 0) CheckedIssn(digits.take(4) + "-" + digits.takeLast(4)) else null
    }
}
