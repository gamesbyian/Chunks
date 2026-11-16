package io.github.gamesbyian.chunks

/**
 * Declarative metadata for existing preferences. This catalog does not own values, defaults,
 * persistence or remote operations. Domain controllers remain the single authority.
 *
 * This is the incremental foundation described in docs/SETTINGS-REGISTRY.md. Do not wire a
 * generic renderer until typed domain bindings can preserve the actual effective value.
 */
internal enum class SettingScope { GLOBAL, CLIENT, TRANSFER }
internal enum class SettingControl { SWITCH, CHOICE, SLIDER, TEXT, ACTION }

internal data class SettingDescriptor(
    val id: String,
    val label: String,
    val control: SettingControl,
    val scope: SettingScope,
    val canonicalHome: OptionsCategory,
    val searchAliases: Set<String> = emptySet()
) {
    init {
        require(id.matches(Regex("[a-z][a-z0-9]*(\\.[a-z][a-zA-Z0-9]*)+"))) {
            "Setting IDs must be stable dotted identifiers: $id"
        }
        require(label.isNotBlank())
    }
}

internal data class SettingsPageDefinition(
    val category: OptionsCategory,
    val settingIds: List<String>
)

/** Only register settings confirmed to exist. No speculative/inactive controls. */
internal object SettingsRegistry {
    val descriptors: List<SettingDescriptor> = listOf(
        SettingDescriptor(
            "artwork.automaticLookup", "Automatically identify transfers",
            SettingControl.SWITCH, SettingScope.GLOBAL, OptionsCategory.ARTWORK,
            setOf("metadata", "online lookup")
        ),
        SettingDescriptor(
            "artwork.showArtwork", "Show artwork",
            SettingControl.SWITCH, SettingScope.GLOBAL, OptionsCategory.ARTWORK,
            setOf("posters", "covers")
        ),
        SettingDescriptor(
            "artwork.downloadArtwork", "Download artwork",
            SettingControl.SWITCH, SettingScope.GLOBAL, OptionsCategory.ARTWORK,
            setOf("images", "posters")
        )
    )

    private val byId: Map<String, SettingDescriptor> = descriptors.associateBy { it.id }

    /** Ordered IDs only. A future page may reference an ID already shown in another page. */
    val pages: List<SettingsPageDefinition> = listOf(
        SettingsPageDefinition(
            OptionsCategory.ARTWORK,
            listOf("artwork.automaticLookup", "artwork.showArtwork", "artwork.downloadArtwork")
        )
    )

    fun definition(id: String): SettingDescriptor? = byId[id]

    fun pageSettings(page: SettingsPageDefinition): List<SettingDescriptor> =
        page.settingIds.map { id ->
            requireNotNull(definition(id)) { "Unknown setting reference: $id" }
        }

    init {
        require(byId.size == descriptors.size) { "Duplicate setting descriptor IDs" }
        require(pages.map { it.category }.distinct().size == pages.size) {
            "Duplicate page definitions; combine ordered entries for a category"
        }
        pages.forEach { page ->
            require(page.settingIds.distinct().size == page.settingIds.size) {
                "Duplicate setting within page: ${page.category}"
            }
            pageSettings(page)
        }
        val placed = pages.flatMap { it.settingIds }.toSet()
        require(descriptors.all { it.id in placed }) { "Unplaced registered setting" }
        require(descriptors.all { descriptor ->
            pages.any { it.category == descriptor.canonicalHome && descriptor.id in it.settingIds }
        }) { "Missing canonical setting home" }
    }
}
