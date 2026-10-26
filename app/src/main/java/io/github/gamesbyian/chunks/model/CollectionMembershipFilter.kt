package io.github.gamesbyian.chunks.model

/** A scoped, non-mutating view filter, never a rule or automatic organizer. */
object CollectionMembershipFilter {
    fun matches(selected: String?, memberships: Collection<String>): Boolean =
        selected == null || memberships.any { it.equals(selected, ignoreCase = true) }
}
