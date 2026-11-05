package io.github.gamesbyian.chunks.media.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.ChunksColors
import io.github.gamesbyian.chunks.media.MediaAssignmentState
import io.github.gamesbyian.chunks.media.MediaCandidate
import io.github.gamesbyian.chunks.media.MediaKind
import io.github.gamesbyian.chunks.media.MediaPrivacySettings
import io.github.gamesbyian.chunks.media.MediaProviderInfo
import io.github.gamesbyian.chunks.media.ProviderAttempt
import io.github.gamesbyian.chunks.media.TransferMediaUiState
import io.github.gamesbyian.chunks.media.MediaTransferKey
import io.github.gamesbyian.chunks.media.providerOn

/**
 * Square transfer artwork: real art only for a confident automatic match or a user choice,
 * otherwise bundled category art. Fixed size, so loading never shifts row layout. Decorative by
 * default (the transfer name is the label); pass [description] to expose it to TalkBack.
 */
@Composable
fun TransferMediaArtwork(state: TransferMediaUiState, size: Dp, modifier: Modifier = Modifier,
                         showArtwork: Boolean = true, description: String? = null) {
    val shape = RoundedCornerShape(size * 0.18f)
    val bitmap = state.artwork?.takeIf { showArtwork && state.mayShowRealArt }
    val semantics = if (description != null) Modifier.semantics { contentDescription = description } else Modifier.clearAndSetSemantics {}
    Box(modifier.size(size).then(semantics)) {
        if (bitmap != null) {
            val image = remember(bitmap) { bitmap.asImageBitmap() }
            Image(image, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(shape).border(1.dp, ChunksColors.Outline.copy(alpha = 0.6f), shape))
        } else {
            MediaCategoryTile(state.displayKind, size)
        }
    }
}

/** Honest provenance label for the current assignment. */
@Composable
fun MediaStateChip(state: MediaAssignmentState, modifier: Modifier = Modifier) {
    val color = when (state) {
        MediaAssignmentState.CHOSEN_BY_USER, MediaAssignmentState.CATEGORY_ART_BY_USER -> ChunksColors.Green
        MediaAssignmentState.AUTOMATIC -> ChunksColors.Cyan
        MediaAssignmentState.SUGGESTED -> ChunksColors.Yellow
        MediaAssignmentState.COULDNT_IDENTIFY, MediaAssignmentState.NOT_LOOKED_UP -> ChunksColors.Outline
    }
    Text(state.label, modifier.border(1.dp, color, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
        color = ChunksColors.Text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
}

/** Facts read from the transfer name itself (edition, episode range), labelled as such. */
internal fun recognizedFacts(state: TransferMediaUiState): String? {
    val r = state.recognition
    val parts = buildList {
        addAll(r.editions)
        if (r.season != null && r.episode != null && r.lastEpisode != null) add("S%02dE%02d–E%02d".format(r.season, r.episode, r.lastEpisode))
        if (r.kind == MediaKind.TV_SEASON && r.season != null && r.episode == null) add("Season ${r.season} pack")
        r.yearRange?.let { add("${it.first}–${it.last} collection") }
        r.issue?.let { add("Issue #$it") }
    }
    return if (parts.isEmpty()) null else "From name: " + parts.joinToString(" · ")
}

internal fun candidateSubtitle(c: MediaCandidate): String = listOfNotNull(
    c.kind.label,
    c.year?.toString(),
    if (c.season != null && c.episode != null) "S%02dE%02d".format(c.season, c.episode) else c.season?.let { "Season $it" },
    c.episodeTitle?.let { "“$it”" },
    c.creators.take(2).joinToString(", ").ifEmpty { null }
).joinToString(" · ")

/**
 * Detail header block: larger art, identified title, provenance chip, attribution and one obvious
 * "Change" action. The raw transfer name is shown by the host separately and is never replaced.
 */
@Composable
fun MediaMetadataSummary(state: TransferMediaUiState, onChange: () -> Unit, modifier: Modifier = Modifier,
                         showArtwork: Boolean = true, onRequestPoster: (() -> Unit)? = null) {
    val match = state.match
    if (onRequestPoster != null) androidx.compose.runtime.LaunchedEffect(match?.ref) { if (match != null) onRequestPoster() }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
        val poster = state.poster?.takeIf { showArtwork && state.mayShowRealArt && state.userArtwork == null }
        if (poster != null) {
            val image = remember(poster) { poster.asImageBitmap() }
            val shape = RoundedCornerShape(12.dp)
            // Original aspect, never stretched; width fixed so text layout is stable.
            // Clamp extreme aspects to 1:2..2:1 so a tall strip never dominates the header.
            val aspect = (poster.width.toFloat() / poster.height).coerceIn(0.5f, 2f)
            Image(image, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.width(96.dp).aspectRatio(aspect).clip(shape)
                    .border(1.dp, ChunksColors.Outline.copy(alpha = 0.6f), shape).clearAndSetSemantics {})
        } else TransferMediaArtwork(state, 96.dp, showArtwork = showArtwork)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (match != null) {
                Text(match.title, style = MaterialTheme.typography.titleMedium, color = ChunksColors.Text, fontWeight = FontWeight.Bold,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(candidateSubtitle(match), style = MaterialTheme.typography.bodySmall, color = ChunksColors.TextSecondary)
            } else {
                val r = state.recognition
                Text(r.title?.let { "Looks like: $it" } ?: "Not identified", style = MaterialTheme.typography.titleSmall,
                    color = ChunksColors.Text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(state.displayKind.label + (r.year?.let { " · $it" } ?: "") +
                    (if (r.season != null && r.episode != null) " · S%02dE%02d".format(r.season, r.episode) else ""),
                    style = MaterialTheme.typography.bodySmall, color = ChunksColors.TextSecondary)
            }
            MediaStateChip(state.state)
            recognizedFacts(state)?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = ChunksColors.TextSecondary)
            }
            match?.let {
                val art = state.artworkSource?.provider?.takeIf { p -> p != it.ref.provider }
                Text("Source: ${it.attribution.providerName}" + (art?.let { " · art: $it" } ?: ""),
                    style = MaterialTheme.typography.labelSmall, color = ChunksColors.TextSecondary)
            }
            TextButton(onClick = onChange, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (match == null) "Identify…" else "Change match or artwork…")
            }
        }
    }
}

/** Explicit, isolated identification/correction surface. Every action is local presentation only. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MediaIdentifyPanel(
    state: TransferMediaUiState,
    settings: MediaPrivacySettings,
    onEnableLookup: () -> Unit,
    onLookup: () -> Unit,
    onSearch: (kind: MediaKind, text: String, year: Int?) -> Unit,
    onChoose: (MediaCandidate) -> Unit,
    onUseCategoryArt: (MediaKind) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    /** Opens the system Photo Picker; null hides the option. Works with online lookup off. */
    onPickImage: (() -> Unit)? = null,
    onRemoveImage: (() -> Unit)? = null,
    /** Explicit image-link import; null hides the field. */
    onImageUrl: ((String) -> Unit)? = null,
    /** Name of a cover file found in this transfer's own completed files; null hides the option. */
    localCoverName: String? = null,
    onUseLocalCover: (() -> Unit)? = null,
    /** Preview of other transfers that could share this choice; empty hides the section. */
    related: List<io.github.gamesbyian.chunks.media.TransferMediaController.RelatedCandidate> = emptyList(),
    onApplyToRelated: ((List<MediaTransferKey>) -> Unit)? = null
) {
    var showWhy by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Identify this transfer", style = MaterialTheme.typography.titleMedium, color = ChunksColors.Text,
            modifier = Modifier.semantics { heading() })
        Text("Changes only how Chunks shows this transfer. Files, torrent data and names are never changed.",
            style = MaterialTheme.typography.bodySmall, color = ChunksColors.TextSecondary)
        MediaMetadataSummaryCompact(state)

        if (!settings.onlineLookupConsent) {
            ConsentCard(onEnableLookup)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onLookup, enabled = !state.busy && state.recognition.kind != MediaKind.ADULT,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text(if (state.assignment == null) "Look up online" else "Look up again") }
                if (state.busy) CircularProgressIndicator(Modifier.size(24.dp).semantics { contentDescription = "Looking up" }, strokeWidth = 2.dp)
            }
        }
        state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = ChunksColors.Text,
            modifier = Modifier.semantics { stateDescription = it }) }

        val suggestions = state.searchResults ?: state.assignment?.suggestions?.takeIf { state.state == MediaAssignmentState.SUGGESTED || it.isNotEmpty() }
        if (!suggestions.isNullOrEmpty()) {
            Text(if (state.searchResults != null) "Search results" else "Possible matches", style = MaterialTheme.typography.titleSmall,
                color = ChunksColors.Text, modifier = Modifier.semantics { heading() })
            CandidateList(suggestions, selected = state.assignment?.displayMatch, onChoose = onChoose)
        }

        if (state.state == MediaAssignmentState.CHOSEN_BY_USER && state.match != null && related.isNotEmpty() && onApplyToRelated != null)
            ApplyToRelated(related, onApplyToRelated)

        if (settings.onlineLookupConsent) ManualSearch(state, onSearch)

        if (onPickImage != null) {
            Text("Your own image", style = MaterialTheme.typography.titleSmall, color = ChunksColors.Text, modifier = Modifier.semantics { heading() })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPickImage, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Choose image from device…") }
                if (state.userArtwork != null && onRemoveImage != null)
                    TextButton(onClick = onRemoveImage, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove my image") }
            }
            if (localCoverName != null && onUseLocalCover != null)
                OutlinedButton(onClick = onUseLocalCover, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Use $localCoverName from this transfer", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            if (onImageUrl != null) ImageUrlField(state.busy, onImageUrl)
        }

        Text("Or use built-in art", style = MaterialTheme.typography.titleSmall, color = ChunksColors.Text, modifier = Modifier.semantics { heading() })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            categoryChoices.forEach { kind ->
                val selected = state.assignment?.categoryOverride == kind && state.state == MediaAssignmentState.CATEGORY_ART_BY_USER
                Column(Modifier.width(72.dp).clip(RoundedCornerShape(12.dp))
                    .selectable(selected, role = Role.RadioButton) { onUseCategoryArt(kind) }
                    .border(if (selected) 2.dp else 0.dp, if (selected) ChunksColors.Green else ChunksColors.Background, RoundedCornerShape(12.dp))
                    .padding(4.dp).semantics { contentDescription = "Use ${kind.label} art" },
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    MediaCategoryTile(kind, 56.dp, showLabel = false)
                    Text(kind.label, style = MaterialTheme.typography.labelSmall, color = ChunksColors.TextSecondary, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onReset, enabled = state.assignment != null, modifier = Modifier.heightIn(min = 48.dp)) { Text("Reset to automatic") }
            if (state.assignment?.attempts?.isNotEmpty() == true) TextButton(onClick = { showWhy = !showWhy }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (showWhy) "Hide details" else "Why this result?")
            }
        }
        if (showWhy) AttemptList(state.assignment?.attempts ?: emptyList(), state.recognition.evidence)
    }
}

@Composable
private fun ApplyToRelated(related: List<io.github.gamesbyian.chunks.media.TransferMediaController.RelatedCandidate>,
                           onApply: (List<MediaTransferKey>) -> Unit) {
    // Default: every listed transfer selected; each can be excluded before applying.
    var excluded by remember(related) { mutableStateOf(emptySet<MediaTransferKey>()) }
    var done by remember(related) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(ChunksColors.Surface, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Also use this for ${related.size} similar transfer" + (if (related.size == 1) "" else "s") + "?",
            style = MaterialTheme.typography.titleSmall, color = ChunksColors.Text, modifier = Modifier.semantics { heading() })
        Text("Matched by the same recognized name. Check each one; only how Chunks shows them changes.",
            style = MaterialTheme.typography.bodySmall, color = ChunksColors.TextSecondary)
        related.take(30).forEach { r ->
            val on = r.key !in excluded
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(on, role = Role.Checkbox) {
                excluded = if (it) excluded - r.key else excluded + r.key },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.Checkbox(checked = on, onCheckedChange = null)
                Text(r.originalName, style = MaterialTheme.typography.bodySmall, color = ChunksColors.Text, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
        if (related.size > 30) Text("…and ${related.size - 30} more", style = MaterialTheme.typography.bodySmall, color = ChunksColors.TextSecondary)
        val chosen = related.map { it.key }.filter { it !in excluded }
        Button(onClick = { onApply(chosen); done = true }, enabled = chosen.isNotEmpty() && !done,
            modifier = Modifier.heightIn(min = 48.dp)) { Text(if (done) "Applied" else "Apply to ${chosen.size}") }
    }
}

@Composable
private fun ImageUrlField(busy: Boolean, onSubmit: (String) -> Unit) {
    var url by rememberSaveable { mutableStateOf("") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(url, { url = it.take(2_000) }, label = { Text("Image link (https://)") }, singleLine = true,
            modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (url.isNotBlank()) onSubmit(url) }))
        Button(onClick = { onSubmit(url) }, enabled = url.isNotBlank() && !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Use") }
    }
    Text("Downloads only this image, only when you tap Use. Make sure you may use it.", style = MaterialTheme.typography.bodySmall,
        color = ChunksColors.TextSecondary)
}

private val categoryChoices = listOf(MediaKind.MOVIE, MediaKind.TV_SERIES, MediaKind.MUSIC_ALBUM, MediaKind.BOOK, MediaKind.AUDIOBOOK,
    MediaKind.COMIC, MediaKind.GAME, MediaKind.SOFTWARE, MediaKind.DOCUMENT, MediaKind.ARCHIVE, MediaKind.IMAGE, MediaKind.UNKNOWN)

@Composable
private fun MediaMetadataSummaryCompact(state: TransferMediaUiState) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        TransferMediaArtwork(state, 64.dp)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(state.match?.title ?: state.recognition.title ?: "Unknown", style = MaterialTheme.typography.bodyLarge,
                color = ChunksColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            MediaStateChip(state.state)
        }
    }
}

@Composable
private fun ConsentCard(onEnable: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(ChunksColors.SurfaceRaised, RoundedCornerShape(14.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Online lookup is off", style = MaterialTheme.typography.titleSmall, color = ChunksColors.Text, fontWeight = FontWeight.Bold)
        Text("If you turn it on, Chunks sends only a cleaned-up title (plus year, season or episode) to the named media " +
            "databases. Torrent names, links, trackers, file paths and server details are never sent. Providers can see " +
            "your network address.", style = MaterialTheme.typography.bodySmall, color = ChunksColors.TextSecondary)
        Button(onClick = onEnable, modifier = Modifier.heightIn(min = 48.dp)) { Text("Turn on online lookup…") }
    }
}

@Composable
private fun CandidateList(items: List<MediaCandidate>, selected: MediaCandidate?, onChoose: (MediaCandidate) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.take(12).forEach { c ->
            val isSelected = selected?.ref == c.ref
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp))
                .background(if (isSelected) ChunksColors.Muted else ChunksColors.Surface)
                .selectable(isSelected, role = Role.RadioButton) { onChoose(c) }
                .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RadioButton(selected = isSelected, onClick = null)
                Column(Modifier.weight(1f)) {
                    Text(c.title, style = MaterialTheme.typography.bodyLarge, color = ChunksColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(candidateSubtitle(c), style = MaterialTheme.typography.bodySmall, color = ChunksColors.TextSecondary,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(c.attribution.providerName, style = MaterialTheme.typography.labelSmall, color = ChunksColors.TextSecondary)
                }
            }
        }
    }
}

@Composable
private fun ManualSearch(state: TransferMediaUiState, onSearch: (MediaKind, String, Int?) -> Unit) {
    var text by rememberSaveable(state.key.value) { mutableStateOf(state.recognition.title ?: "") }
    var year by rememberSaveable(state.key.value) { mutableStateOf(state.recognition.year?.toString() ?: "") }
    var kind by rememberSaveable(state.key.value) { mutableStateOf(searchKindFor(state.recognition.kind)) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Search by title", style = MaterialTheme.typography.titleSmall, color = ChunksColors.Text, modifier = Modifier.semantics { heading() })
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(MediaKind.MOVIE, MediaKind.TV_SERIES, MediaKind.MUSIC_ALBUM, MediaKind.BOOK).forEach { k ->
                val sel = kind == k
                Text(k.label, Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(10.dp))
                    .border(BorderStroke(1.dp, if (sel) ChunksColors.Cyan else ChunksColors.Outline), RoundedCornerShape(10.dp))
                    .background(if (sel) ChunksColors.Muted else ChunksColors.Background)
                    .selectable(sel, role = Role.RadioButton) { kind = k }.padding(horizontal = 10.dp, vertical = 10.dp),
                    color = ChunksColors.Text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
        OutlinedTextField(text, { text = it.take(100) }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch(kind, text, year.toIntOrNull()) }))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(year, { v -> year = v.filter { it.isDigit() }.take(4) }, label = { Text("Year (optional)") }, singleLine = true,
                modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Button(onClick = { onSearch(kind, text, year.toIntOrNull()) }, enabled = text.isNotBlank() && !state.busy,
                modifier = Modifier.heightIn(min = 48.dp)) { Text("Search") }
        }
    }
}

private fun searchKindFor(k: MediaKind) = when {
    k.isTv -> MediaKind.TV_SERIES
    k == MediaKind.MUSIC_ALBUM || k == MediaKind.AUDIO -> MediaKind.MUSIC_ALBUM
    k == MediaKind.BOOK || k == MediaKind.AUDIOBOOK -> MediaKind.BOOK
    else -> MediaKind.MOVIE
}

@Composable
private fun AttemptList(attempts: List<ProviderAttempt>, evidence: List<String>) {
    Column(Modifier.fillMaxWidth().background(ChunksColors.Surface, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        evidence.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = ChunksColors.TextSecondary) }
        attempts.forEach { a -> Text("${a.provider}: ${a.outcome}", style = MaterialTheme.typography.bodySmall, color = ChunksColors.TextSecondary) }
    }
}

/** Media privacy and provider preferences. Each provider shows its licence/attribution. */
@Composable
fun MediaPrivacySettingsPanel(
    settings: MediaPrivacySettings,
    providers: List<MediaProviderInfo>,
    configured: (String) -> Boolean,
    onChange: (MediaPrivacySettings) -> Unit,
    onSetProviderKey: ((String, String?) -> Unit)?,
    cacheSummary: String,
    onClearData: () -> Unit,
    modifier: Modifier = Modifier
) {
    var keyEditor by remember { mutableStateOf<String?>(null) }
    var keyText by remember { mutableStateOf("") }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Media artwork & info", style = MaterialTheme.typography.titleMedium, color = ChunksColors.Text, modifier = Modifier.semantics { heading() })
        SettingSwitch("Online lookup", "Send cleaned-up titles to the providers below. Off by default.", settings.onlineLookupConsent) {
            onChange(settings.copy(onlineLookupConsent = it, automaticLookup = settings.automaticLookup && it))
        }
        SettingSwitch("Identify automatically", "Look up transfers as they appear, on unmetered networks only.",
            settings.automaticLookup, enabled = settings.onlineLookupConsent) { onChange(settings.copy(automaticLookup = it)) }
        SettingSwitch("Show artwork", "Off shows built-in category art everywhere.", settings.showArtwork) { onChange(settings.copy(showArtwork = it)) }
        SettingSwitch("Download artwork", "Metadata can be used without downloading images.", settings.downloadArtwork) { onChange(settings.copy(downloadArtwork = it)) }
        SettingSwitch("Artwork on mobile data", "Download images on metered networks.", settings.artworkOnMeteredNetwork,
            enabled = settings.downloadArtwork) { onChange(settings.copy(artworkOnMeteredNetwork = it)) }
        Text("Adult content is never looked up and always uses neutral art.", style = MaterialTheme.typography.bodySmall, color = ChunksColors.TextSecondary)

        Text("Providers", style = MaterialTheme.typography.titleSmall, color = ChunksColors.Text, modifier = Modifier.semantics { heading() })
        providers.forEach { p ->
            val on = settings.providerOn(p)
            Column(Modifier.fillMaxWidth().background(ChunksColors.Surface, RoundedCornerShape(12.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SettingSwitch(p.displayName, p.kinds.map { it.label }.distinct().joinToString(", ") +
                    (if (p.requiresUserKey) (if (configured(p.id)) " · your key is set" else " · needs your own key") else "") +
                    (if (!p.enabledByDefault) " · off unless you turn it on" else ""), on) {
                    onChange(if (p.enabledByDefault) settings.copy(disabledProviders = if (it) settings.disabledProviders - p.id else settings.disabledProviders + p.id)
                        else settings.copy(enabledProviders = if (it) settings.enabledProviders + p.id else settings.enabledProviders - p.id))
                }
                Text(p.attribution, style = MaterialTheme.typography.labelSmall, color = ChunksColors.TextSecondary)
                if (p.requiresUserKey && onSetProviderKey != null) {
                    if (keyEditor == p.id) {
                        OutlinedTextField(keyText, { keyText = it.trim().take(400) }, label = { Text("${p.displayName} API key or token") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onSetProviderKey(p.id, keyText); keyEditor = null; keyText = "" }, enabled = keyText.isNotBlank(),
                                modifier = Modifier.heightIn(min = 48.dp)) { Text("Save key") }
                            TextButton(onClick = { keyEditor = null; keyText = "" }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
                        }
                    } else Row {
                        TextButton(onClick = { keyEditor = p.id }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (configured(p.id)) "Replace key…" else "Add your key…") }
                        if (configured(p.id)) TextButton(onClick = { onSetProviderKey(p.id, null) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove key") }
                    }
                }
            }
        }
        Text(cacheSummary, style = MaterialTheme.typography.bodySmall, color = ChunksColors.TextSecondary)
        OutlinedButton(onClick = onClearData, modifier = Modifier.heightIn(min = 48.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = ChunksColors.Text)) { Text("Clear artwork, matches & lookup history") }
        Spacer(Modifier.size(4.dp))
    }
}

@Composable
private fun SettingSwitch(title: String, detail: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) ChunksColors.Text else ChunksColors.TextSecondary)
            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = ChunksColors.TextSecondary)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** Compact clickable artwork for a list row; opens identification when tapped. */
@Composable
fun TransferMediaArtworkButton(state: TransferMediaUiState, size: Dp, onClick: () -> Unit, modifier: Modifier = Modifier, showArtwork: Boolean = true) {
    Box(modifier.size(maxOf(size, 48.dp)).clip(RoundedCornerShape(size * 0.18f)).clickable(onClickLabel = "Change artwork", role = Role.Button, onClick = onClick)
        .semantics { contentDescription = "Artwork: ${state.state.label}" }, contentAlignment = Alignment.Center) {
        TransferMediaArtwork(state, size, showArtwork = showArtwork)
    }
}
