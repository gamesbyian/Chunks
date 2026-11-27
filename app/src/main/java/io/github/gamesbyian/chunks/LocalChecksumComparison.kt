package io.github.gamesbyian.chunks

/**
 * Interpret explicitly supplied checksum text as data only. No filesystem
 * lookup, path rewriting, basename guessing, command execution or network
 * access. A manifest row must name the exact reviewed relative torrent path.
 */
internal object LocalChecksumComparison {
    enum class Algorithm(val label: String, val digits: Int) {
        SHA256("SHA-256", 64), SHA1("SHA-1 (weak)", 40),
        MD5("MD5 (weak)", 32), CRC32("CRC-32 (non-cryptographic)", 8)
    }
    enum class State { NONE, INVALID, WRONG_FILE, MATCHES, DIFFERS }
    const val MAX_INPUT = 4096

    fun compare(algorithm: Algorithm, observed: String, supplied: String, exactPath: String): State {
        if (supplied.isBlank()) return State.NONE
        if (supplied.length > MAX_INPUT || supplied.any { it == '\r' || it == '\n' ||
                it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() })
            return State.INVALID
        val hex = "[0-9a-fA-F]{" + algorithm.digits + "}"
        val value = supplied.trim()
        if (Regex(hex).matches(value)) return match(observed, value)
        // GNU checksum form: digest + space + text/binary marker + exact name.
        // Escaped GNU filenames, multiple lines and BSD syntax are unsupported.
        val row = if (algorithm == Algorithm.CRC32)
            Regex("(.+) ($hex)").matchEntire(supplied)?.let { it.groupValues[2] to it.groupValues[1] }
        else
            Regex("($hex) [ *](.+)").matchEntire(supplied)?.let { it.groupValues[1] to it.groupValues[2] }
        if (row == null) return State.INVALID
        if (!safeRelativePath(exactPath) || row.second != exactPath) return State.WRONG_FILE
        return match(observed, row.first)
    }

    private fun match(observed: String, expected: String) =
        if (observed.equals(expected, ignoreCase = true)) State.MATCHES else State.DIFFERS

    private fun safeRelativePath(path: String) =
        path.isNotEmpty() && !path.startsWith('/') && !path.contains('\\') &&
            path.none { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() } &&
            path.split('/').none { it.isEmpty() || it == "." || it == ".." }
}
