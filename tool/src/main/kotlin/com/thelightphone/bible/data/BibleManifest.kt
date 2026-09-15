package com.thelightphone.bible.data

import kotlinx.serialization.Serializable

/** Mirrors `bible/<translation>/manifest.json` exactly as the converter writes it (CLAUDE.md 6). */
@Serializable
data class BibleManifest(
    val displayName: String,
    val books: Map<String, BibleManifestBook>,
)

@Serializable
data class BibleManifestBook(
    val name: String,
    val slug: String,
    val chapterCount: Int,
)
