package io.github.gamesbyian.chunks.engine

import java.io.File

/**
 * Test-only durability witness. An old path's existence cannot establish a
 * persisted resume after migrating to exclusive immutable event generations.
 * Decode the full chained receipt and demand actual nonempty saved bytes.
 * An in-flight/torn record is never mistaken for successful persistence.
 */
internal fun verifiedSavedResumeEvent(directory: File, infoHash: String): Boolean = runCatching {
    val key = infoHash.lowercase()
    val files = LocalResumeDirectoryInventory.prepare(directory)
    val expected = Regex(Regex.escape(key) + """\.resume-event\.[0-9]{12}""")
    files.any { it.isFile && expected.matches(it.name) } &&
        LocalResumeEventStore.open(directory, files).current(key)?.isNotEmpty() == true
}.getOrDefault(false)
