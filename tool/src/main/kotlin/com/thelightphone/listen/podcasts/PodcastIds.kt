package com.thelightphone.listen.podcasts

import java.net.URI
import java.security.MessageDigest

/**
 * Stable ids and web-address helpers. Podcasts.cmd on the PC computes [showIdFor] the same
 * way, so a show followed on both sides gets the same id (PODCAST_PLAN.md §8).
 */
object PodcastIds {

    /**
     * The feed address in one canonical form, for matching: scheme and host lower-cased,
     * http counted as https, no default port, no "#fragment", no trailing "/".
     */
    fun normalizeFeedUrl(url: String): String {
        var u = url.trim()
        u = u.substringBefore('#')
        val schemeEnd = u.indexOf("://")
        if (schemeEnd < 0) return u.trimEnd('/')
        val scheme = u.substring(0, schemeEnd).lowercase().let { if (it == "http" || it == "feed") "https" else it }
        val rest = u.substring(schemeEnd + 3)
        val hostEnd = rest.indexOfAny(charArrayOf('/', '?')).let { if (it < 0) rest.length else it }
        var host = rest.substring(0, hostEnd).lowercase()
        host = host.removeSuffix(":443").removeSuffix(":80")
        val path = rest.substring(hostEnd).trimEnd('/')
        return "$scheme://$host$path"
    }

    /** First 12 hex characters of SHA-1 of the normalized feed address. */
    fun showIdFor(feedUrl: String): String = shortHash(normalizeFeedUrl(feedUrl))

    /** First 12 hex characters of SHA-1 of the guid, or of the enclosure address without one. */
    fun episodeIdFor(guid: String?, enclosureUrl: String): String =
        shortHash(guid?.trim()?.takeIf { it.isNotEmpty() } ?: enclosureUrl.trim())

    fun shortHash(text: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(12)
    }

    /** "http://x" → "https://x" (plain http is blocked on the phone). Other addresses unchanged. */
    fun upgradeToHttps(url: String): String {
        val trimmed = url.trim()
        return when {
            trimmed.startsWith("http://", ignoreCase = true) -> "https://" + trimmed.substring(7)
            trimmed.startsWith("feed://", ignoreCase = true) -> "https://" + trimmed.substring(7)
            trimmed.startsWith("//") -> "https:$trimmed"
            else -> trimmed
        }
    }

    /**
     * A feed address as typed on the phone, tidied: spaces dropped, "https://" added when
     * there's no scheme, and podcast-app schemes (feed://, itpc://, pcast://) made https.
     * Null when it can't be a web address.
     */
    fun cleanTypedFeedAddress(typed: String): String? {
        var s = typed.trim().replace(Regex("""\s+"""), "")
        if (s.isEmpty()) return null
        val scheme = s.substringBefore("://", missingDelimiterValue = "").lowercase()
        s = when (scheme) {
            "" -> "https://$s"
            "http", "https" -> s
            "feed", "itpc", "pcast", "podcast" -> "https://" + s.substringAfter("://")
            else -> return null
        }
        val host = try {
            URI(s).host
        } catch (e: Exception) {
            null
        } ?: return null
        return s.takeIf { '.' in host && !host.startsWith('.') && !host.endsWith('.') }
    }

    /**
     * [href] made absolute against [base] (feeds sometimes use "/episode.mp3"), or null when
     * it isn't a usable web address.
     */
    fun resolve(base: String?, href: String?): String? {
        val h = href?.trim()?.replace(" ", "%20")?.takeIf { it.isNotEmpty() } ?: return null
        if (h.startsWith("http://", true) || h.startsWith("https://", true)) return h
        if (h.startsWith("//")) return "https:$h"
        if (base == null) return null
        return try {
            val resolved = URI(base.trim().replace(" ", "%20")).resolve(h).toString()
            resolved.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
        } catch (e: Exception) {
            null
        }
    }
}
