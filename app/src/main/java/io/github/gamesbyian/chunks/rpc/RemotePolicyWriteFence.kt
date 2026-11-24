package io.github.gamesbyian.chunks.rpc

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Revocable policy authority, checked on the caller and at each wire dispatch. */
internal class RemotePolicyWriteFence(private val ownerCurrent: () -> Boolean) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RemotePolicyWriteFence> {
        suspend fun check() {
            val context = currentCoroutineContext()
            context.ensureActive()
            if (context[Key]?.ownerCurrent?.invoke() == false)
                throw kotlinx.coroutines.CancellationException("Remote policy owner changed")
        }
    }
}
