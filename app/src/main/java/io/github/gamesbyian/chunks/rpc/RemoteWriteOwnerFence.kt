package io.github.gamesbyian.chunks.rpc

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Revocable exact-client authority, checked on the caller and at each wire dispatch. */
internal class RemoteWriteOwnerFence(private val ownerCurrent: () -> Boolean) :
    AbstractCoroutineContextElement(Key) {
    fun ensureCurrent() {
        if (!ownerCurrent()) throw kotlinx.coroutines.CancellationException("Remote write owner changed")
    }

    companion object Key : CoroutineContext.Key<RemoteWriteOwnerFence> {
        suspend fun check() {
            val context = currentCoroutineContext()
            context.ensureActive()
            context[Key]?.ensureCurrent()
        }
    }
}
