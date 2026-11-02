package io.github.gamesbyian.chunks.model

import java.util.Locale

// These checks also guard public data-class constructors and generated copy(),
// not merely callers that happen to use the normalization helpers below.
private fun isbn13CheckDigit(first12: String): Int {
    val weighted = first12.mapIndexed { index, char -> char.digitToInt() * if (index % 2 == 0) 1 else 3 }.sum()
    return (10 - weighted % 10) % 10
}

private fun validIsbn10(digits: String): Boolean =
    digits.matches(Regex("[0-9]{9}[0-9X]")) && !digits.all { it == '0' } &&
        digits.mapIndexed { index, char ->
            (10 - index) * if (char == 'X') 10 else char.digitToInt()
        }.sum() % 11 == 0

private fun validIsbn13(digits: String): Boolean =
    digits.matches(Regex("(?:978|979)[0-9]{10}")) && !digits.startsWith("9790") &&
        digits.last().digitToInt() == isbn13CheckDigit(digits.take(12))

private fun validIssn8(digits: String): Boolean {
    if (!digits.matches(Regex("[0-9]{7}[0-9X]")) || digits.all { it == '0' }) return false
    val sum = digits.take(7).mapIndexed { index, char -> char.digitToInt() * (8 - index) }.sum()
    val check = if (digits.last() == 'X') 10 else digits.last().digitToInt()
    return (sum + check) % 11 == 0
}

/** Checksum-validated book *product/edition* identifier, never an abstract work identity. */
data class CheckedIsbn(val isbn13: String, val observedIsbn10: String?) {
    init {
        require(validIsbn13(isbn13)) { "ISBN-13 must have a valid 978/979 checksum" }
        require(observedIsbn10 == null ||
            (validIsbn10(observedIsbn10) && isbn13.startsWith("978" + observedIsbn10.take(9)))) {
            "Observed ISBN-10 must be canonical and identify the same 978 ISBN-13 product"
        }
    }
}

/** Checksum-validated continuing-resource identifier; not the ID of a specific journal issue. */
data class CheckedIssn(val canonical: String) {
    init {
        require(canonical.matches(Regex("[0-9]{4}-[0-9]{3}[0-9X]")) &&
            validIssn8(canonical.replace("-", ""))) { "ISSN must be canonical and checksum-valid" }
    }
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
            validIsbn10(digits) -> {
                val base12 = "978" + digits.substring(0, 9)
                CheckedIsbn(base12 + isbn13CheckDigit(base12), digits)
            }
            validIsbn13(digits) -> CheckedIsbn(digits, null)
            else -> null
        }
    }

    /** ISSN is tied to a continuing resource/key title; different formats can have different ISSNs. */
    fun issn(raw: String): CheckedIssn? {
        val input = raw.trim()
        val prefix = issnPrefix.find(input)
        val digits = stripSeparators(input.substring(prefix?.range?.last?.plus(1) ?: 0)) ?: return null
        return if (validIssn8(digits)) CheckedIssn(digits.take(4) + "-" + digits.takeLast(4)) else null
    }
}
