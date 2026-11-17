package io.github.gamesbyian.chunks

import kotlinx.coroutines.CoroutineScope

/**
 * Local and authenticated remote Add use the same execution-lifetime policy:
 * the app-owned ViewModel scope when available, never the disposable Add route.
 *
 * A standalone preview/test without an owner retains its ordinary screen scope.
 * This selects a coroutine lifetime only. The engine-specific one-shot ticket,
 * native/RPC write fence and read-only reconciliation remain authoritative.
 */
internal object UniversalAddExecutionScope {
    fun select(retainedOwner: CoroutineScope?, screen: CoroutineScope): CoroutineScope =
        retainedOwner ?: screen
}
