# Worked design sketch: reusable typed Kotlin settings and Compose projection

**Status:** illustrative architecture sketch, not a drop-in implementation or claim that the host already has these types. Keep host repository/DI/DataStore conventions. Do not add this example as production code without selecting the actual app's data model and supported scopes.

## Domain identity separated from placement

```kotlin
// A single setting's *definition*, without a stored current value.
sealed interface SettingKey<T : Any> {
    val id: String
    val default: T
    fun validate(value: T): Boolean
}

data object AutoFetchArtwork : SettingKey<Boolean> {
    override val id = "artwork.autoFetch"
    override val default = true
    override fun validate(value: Boolean) = true
}

// Choose only the scope kinds actually supported by a given setting.
sealed interface SettingScope {
    data object Global : SettingScope
    data class Client(val immutableProfileId: String) : SettingScope
    data class Transfer(
        val immutableProfileId: String,
        val infoHash: String
    ) : SettingScope
}

data class ResolvedSetting<T : Any>(
    val value: T,
    val source: SettingScope?, // null = built-in default
    val isOverridden: Boolean,
    val isEditable: Boolean,
    val disabledReason: String? = null
)

// Composition only: page does not own value or write data.
data class SettingsGroup(
    val id: String,
    val settingIds: List<String>
)
val artworkPage = listOf(
    SettingsGroup(
        id = "collection",
        settingIds = listOf(AutoFetchArtwork.id)
    )
)
```

## Reactive UI flow (pseudocode wiring)

```kotlin
interface SettingsRepository {
    fun observeAutoFetchArtwork(): kotlinx.coroutines.flow.Flow<ResolvedSetting<Boolean>>
    suspend fun setAutoFetchArtwork(value: Boolean): Result<Unit>
}

// ViewModel wraps the repository's Flow into observable state.
// Do not read/write DataStore inside the Composable itself.
@Composable
fun AutoFetchArtworkRow(
    value: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Switch(
        checked = value,
        enabled = enabled,
        onCheckedChange = onCheckedChange
    )
}
```

A real settings row also needs label, summary, pending/error state, semantics and accessible touch geometry. The state holder must co-ordinate in-flight writes so a delayed first completion cannot overwrite a later valid choice. This sketch intentionally omits the host-specific persistence implementation and coroutines; copying it without plugging into the real owner would create a fake setting.

## How to reuse without shadow state

- Options > Artwork includes the ID once, using an ordinary row component.
- An onboarding panel may render the same definition in a different component, if approved.
- Both observe the *same* repository value; neither writes to an independent mutable preference cache.
- To have a separate client-specific value, use one definition with scope-qualified instances only if permitted; never key instances by display name.

## Nonexample

```kotlin
// WRONG: two independent values merely because the control is on two screens
var artworkInOptions by remember { mutableStateOf(true) }
var artworkInAdd by remember { mutableStateOf(false) }
```

More sophisticated abstractions are optional. A few explicit composables and one repository may already be the clearest implementation.
