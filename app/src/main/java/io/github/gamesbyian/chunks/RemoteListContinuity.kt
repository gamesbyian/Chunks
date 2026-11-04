package io.github.gamesbyian.chunks

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.saveable.Saver
import java.security.MessageDigest

/**
 * Navigation-only rTorrent list continuity, owned above the route switch.
 *
 * Each exact authenticated account (alias scope: endpoint + user) keeps its
 * own list position while the person visits the phone, All environments,
 * Places or settings, and the most recently shown account's position and
 * search survive Activity recreation. A different account always starts at
 * the top with an empty search. Nothing here holds selection, expansion,
 * confirmation, credentials or command state, and restoring it never
 * connects, refreshes or mutates a server.
 *
 * Saved instance state stores only a one-way digest of the scope, never the
 * endpoint or user name.
 */
internal class RemoteListContinuity(
    private var restoredTag: String? = null,
    private var restoredIndex: Int = 0,
    private var restoredOffset: Int = 0,
    restoredQuery: String = ""
) {
    private val states = mutableMapOf<String, LazyListState>()
    private var lastTag: String? = restoredTag
    private val queries = mutableMapOf<String, String>().apply {
        restoredTag?.let { put(it, restoredQuery) }
    }

    /** The same state object for repeated calls with one account. */
    fun listState(aliasScope: String): LazyListState {
        val tag = tagOf(aliasScope)
        lastTag = tag
        return states.getOrPut(tag) {
            if (tag == restoredTag) LazyListState(restoredIndex, restoredOffset).also {
                restoredTag = null
            } else LazyListState()
        }
    }

    /** Search typed for this exact account, or empty for any other account. */
    fun query(aliasScope: String): String = queries[tagOf(aliasScope)].orEmpty()

    fun rememberQuery(aliasScope: String, query: String) {
        if (aliasScope.isBlank()) return
        queries[tagOf(aliasScope)] = query
    }

    private fun snapshot(): List<Any> {
        val tag = lastTag ?: return emptyList()
        val state = states[tag]
        return listOf(
            tag,
            state?.firstVisibleItemIndex ?: restoredIndex,
            state?.firstVisibleItemScrollOffset ?: restoredOffset,
            queries[tag].orEmpty()
        )
    }

    companion object {
        fun tagOf(aliasScope: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(aliasScope.toByteArray(Charsets.UTF_8))
            return digest.take(12).joinToString("") { "%02x".format(it) }
        }

        val Saver: Saver<RemoteListContinuity, Any> = Saver(
            save = { it.snapshot() },
            restore = { saved ->
                val values = saved as? List<*>
                val tag = values?.getOrNull(0) as? String
                if (tag == null) RemoteListContinuity()
                else RemoteListContinuity(
                    restoredTag = tag,
                    restoredIndex = (values.getOrNull(1) as? Int ?: 0).coerceAtLeast(0),
                    restoredOffset = (values.getOrNull(2) as? Int ?: 0).coerceAtLeast(0),
                    restoredQuery = values.getOrNull(3) as? String ?: ""
                )
            }
        )
    }
}
