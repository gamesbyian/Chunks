package io.github.gamesbyian.chunks

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Only a one-level folder inside Chunks' existing app-owned POSIX download root.
 * This is NOT Android SAF/public Downloads access and is never a remote path.
 * Creating the directory is a reviewed Add-time action, not a text-entry effect.
 */
internal object LocalOwnedAddFolder {
    const val MAX_NAME_UTF8 = 120

    fun normalizedName(draft: String): String? {
        if (draft.isBlank()) return ""
        val name = draft.trim()
        if (name == "." || name == ".." || name.length > 100 ||
            name.toByteArray(Charsets.UTF_8).size > MAX_NAME_UTF8 ||
            name.startsWith(".") || name.endsWith(".") ||
            name.any {
                it == '/' || it == '\\' || it.code < 32 || it.code == 127 ||
                    it in '\u202A'..'\u202E' || it in '\u2066'..'\u2069'
            }) return null
        return name
    }

    fun proposed(root: File?, draft: String): File? {
        val valid = normalizedName(draft) ?: return null
        val folder = root ?: return null
        return if (valid.isEmpty()) folder else File(folder, valid)
    }

    /** Called on IO lane only after explicit Add confirmation. Existing nonempty
     * or symlinked folders are refused to avoid payload collision/overwrite. */
    fun prepare(root: File?, draft: String): File? {
        val name = normalizedName(draft) ?: return null
        val base = root ?: return null
        if (!base.isDirectory || Files.isSymbolicLink(base.toPath())) return null
        val canonicalBase = try { base.canonicalFile } catch (_: Exception) { return null }
        if (name.isEmpty()) return base.takeIf { it.canWrite() }
        val candidate = File(base, name)
        try {
            val path = candidate.toPath()
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) ||
                    Files.isSymbolicLink(path) || candidate.list()?.isNotEmpty() != false)
                    return null
            } else if (!candidate.mkdir()) return null
            if (candidate.canonicalFile.parentFile != canonicalBase ||
                !candidate.isDirectory || !candidate.canWrite()) return null
        } catch (_: Exception) { return null }
        return candidate
    }
}
