package io.github.gamesbyian.chunks.storage

import io.github.gamesbyian.chunks.storage.SafCreationCandidate as Gate
import org.junit.Assert.assertEquals
import org.junit.Test

class SafCreationCandidateTest {
    private val before = Gate.Inventory("provider#tree#parent", listOf(Gate.Child("foreign", "keep")), true)
    private val after = Gate.Inventory("provider#tree#parent",
        listOf(Gate.Child("foreign", "keep"), Gate.Child("new-unique-id", "new.bin")), true)

    @Test fun evenConsistentNewUriRemainsUnproven() {
        assertEquals(Gate.Result.UNPROVEN_CANDIDATE,
            Gate.inspect(before, after, before.scope, "new-unique-id", "new.bin"))
    }

    @Test fun providerReturningExistingUriMustBeRejected() {
        assertEquals(Gate.Result.REJECT, Gate.inspect(before, before, before.scope, "foreign", "keep"))
        assertEquals(Gate.Result.REJECT, Gate.inspect(before,
            after.copy(children = listOf(Gate.Child("foreign", "new.bin"))), before.scope,
            "foreign", "new.bin"))
    }

    @Test fun substitutedNameOrAmbiguousUriOrDuplicateNameRefuses() {
        assertEquals(Gate.Result.REJECT, Gate.inspect(before, after, before.scope, "missing", "new.bin"))
        assertEquals(Gate.Result.REJECT, Gate.inspect(before, after, before.scope, "new-unique-id", "other.bin"))
        assertEquals(Gate.Result.REJECT, Gate.inspect(before,
            after.copy(children = after.children + Gate.Child("second", "new.bin")),
            before.scope, "new-unique-id", "new.bin"))
        assertEquals(Gate.Result.REJECT, Gate.inspect(before,
            after.copy(children = after.children + Gate.Child("new-unique-id", "new.bin")),
            before.scope, "new-unique-id", "new.bin"))
    }

    @Test fun incompleteOrDifferentScopeInventoriesRefuse() {
        assertEquals(Gate.Result.REJECT, Gate.inspect(null, after, before.scope, "new-unique-id", "new.bin"))
        assertEquals(Gate.Result.REJECT, Gate.inspect(before.copy(complete = false), after,
            before.scope, "new-unique-id", "new.bin"))
        assertEquals(Gate.Result.REJECT, Gate.inspect(before, after.copy(scope = "other-tree"),
            before.scope, "new-unique-id", "new.bin"))
        assertEquals(Gate.Result.REJECT, Gate.inspect(before, after,
            "other-account", "new-unique-id", "new.bin"))
    }
}
