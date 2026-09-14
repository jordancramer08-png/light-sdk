package com.thelightphone.reader.data

import kotlinx.serialization.Serializable

/** Mirrors meta.json exactly as the converter writes it (CLAUDE.md 5). */
@Serializable
data class BookMeta(
    val slug: String,
    val title: String,
    val author: String,
    val chapters: List<ChapterMeta>,
)

@Serializable
data class ChapterMeta(
    val index: Int,
    val title: String,
    val file: String,
    val chars: Int,
)
