package io.github.gamesbyian.chunks.create

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri

/**
 * Android Share → "Create torrent" (a separately labelled share target). Only
 * intents addressed to that target are handled here, so sharing a .torrent or
 * a magnet to Chunks itself keeps its existing Add review. One granted
 * content:// file is admitted; nothing is read until the user reviews it.
 */
internal object SharedCreateDocument {
    const val ALIAS = "io.github.gamesbyian.chunks.CreateShareAlias"

    sealed class Request {
        data class File(val uri: Uri) : Request()
        /** Shared to Create, but not one usable file (text, several files, or no grant). */
        data class Unusable(val reason: String) : Request()
    }

    fun fromIntent(intent: Intent?): Request? {
        if (intent == null || intent.component?.className != ALIAS) return null
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) return Request.Unusable("Share one file at a time, or choose a folder below.")
        if (intent.action != Intent.ACTION_SEND) return null
        @Suppress("DEPRECATION")
        val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        val clip = intent.clipData
        if (clip != null && clip.itemCount > 1) return Request.Unusable("Share one file at a time, or choose a folder below.")
        val clipUri = clip?.getItemAt(0)?.uri
        if (stream != null && clipUri != null && stream != clipUri) return Request.Unusable("The sharing app sent conflicting files.")
        val uri = stream ?: clipUri ?: return Request.Unusable("Nothing to create from: share a file, not text.")
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return Request.Unusable("Only files shared by Android apps can be used.")
        return Request.File(uri)
    }
}
