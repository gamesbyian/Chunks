package io.github.gamesbyian.chunks.model

import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Android instrumentation-only legacy journal fixture. This path-based
 * append adapter is intentionally NOT part of production's classpath.
 * The single fixture it manages is created exclusively by the test.
 */
class FileZipExtractionJournal(private val file: File) : ZipExtractionJournal {
    override fun append(fields: List<String>) {
        val line = fields.joinToString("\t") { URLEncoder.encode(it, "UTF-8") } + "\n"
        FileOutputStream(file, true).use { output ->
            output.write(line.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
    }

    override fun read(): List<List<String>> {
        if (!file.isFile) return emptyList()
        val text = file.readText(Charsets.UTF_8)
        val complete = if (text.endsWith("\n")) text else text.substringBeforeLast("\n", "")
        return complete.lineSequence().filter { it.isNotEmpty() }.map { line ->
            line.split("\t").map { URLDecoder.decode(it, "UTF-8") }
        }.toList()
    }
}
