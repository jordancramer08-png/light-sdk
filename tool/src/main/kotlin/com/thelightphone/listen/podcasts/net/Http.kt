package com.thelightphone.listen.podcasts.net

import com.thelightphone.listen.podcasts.PodcastIds
import java.io.Closeable
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** One HTTP response, before any redirect is followed. Close it when done. */
class HttpResponse(
    val code: Int,
    /** The `Location` header (for redirects). */
    val location: String?,
    val contentType: String?,
    /** The `Content-Length` header, or -1 when unknown. */
    val contentLength: Long,
    val body: InputStream,
    private val onClose: () -> Unit = {},
) : Closeable {
    override fun close() {
        try {
            body.close()
        } catch (_: IOException) {
        }
        onClose()
    }
}

/**
 * Sends one GET and returns the raw response **without following redirects**. Listen
 * follows them itself (see [Http.open]) so every hop can be upgraded to https. The real one
 * is [OkHttpTransport]; tests use a fake.
 */
fun interface HttpTransport {
    @Throws(IOException::class)
    fun get(url: String, headers: Map<String, String>): HttpResponse
}

/** Why a fetch failed, in words Listen can show. */
sealed class NetError(message: String) : IOException(message) {
    /** The address only works over plain http, which the phone blocks. */
    class NeedsSecureLink(val url: String) :
        NetError("This link only works without security (http), and the phone blocks those links.")

    /** Couldn't reach the server at all (no connection, or the name doesn't exist). */
    class NoConnection(cause: Throwable?) : NetError("Can't connect. Check Wi-Fi or mobile data.") {
        init {
            cause?.let { initCause(it) }
        }
    }

    class HttpStatus(val code: Int) : NetError(
        when (code) {
            404, 410 -> "Not found (the address may have changed)."
            401, 403 -> "The server refused (it may be a private feed)."
            429 -> "The server is busy. Try again in a minute."
            in 500..599 -> "The server had a problem. Try again later."
            else -> "The server answered with error $code."
        },
    )

    class TooLarge(val limitBytes: Long) : NetError("Too large to read on the phone.")
    class TooManyRedirects : NetError("The address redirects too many times.")
}

/** Where a fetch ended up. [movedTo] is set when the first hop was a permanent redirect. */
class Opened(val url: String, val response: HttpResponse, val movedTo: String?) : Closeable by response

object Http {
    const val MAX_REDIRECTS = 10

    /**
     * GETs [url], following up to [MAX_REDIRECTS] redirects by hand. Every address (the first
     * and each `Location`) is rewritten from http to https first, because plain http is
     * blocked on the phone and tracking redirects (podtrac and friends) often bounce through
     * http. A non-2xx answer throws [NetError.HttpStatus]. When the https version of an
     * http address can't be reached securely, throws [NetError.NeedsSecureLink].
     */
    fun open(transport: HttpTransport, url: String, headers: Map<String, String> = emptyMap()): Opened {
        var current = url.trim()
        var movedTo: String? = null
        var everHttp = current.startsWith("http://", ignoreCase = true)
        repeat(MAX_REDIRECTS + 1) { hop ->
            val secure = PodcastIds.upgradeToHttps(current)
            val response = try {
                transport.get(secure, headers)
            } catch (e: IOException) {
                throw classify(e, wasHttp = everHttp, url = current)
            }
            if (response.code in REDIRECT_CODES) {
                val next = PodcastIds.resolve(secure, response.location)
                response.close()
                if (next == null) throw NetError.HttpStatus(response.code)
                if (hop == 0 && (response.code == 301 || response.code == 308)) movedTo = next
                if (next.startsWith("http://", ignoreCase = true)) everHttp = true
                current = next
                return@repeat
            }
            if (response.code !in 200..299) {
                response.close()
                throw NetError.HttpStatus(response.code)
            }
            return Opened(secure, response, movedTo)
        }
        throw NetError.TooManyRedirects()
    }

    /** The whole body as bytes, refusing more than [maxBytes]. */
    fun readBytes(opened: Opened, maxBytes: Long): ByteArray = opened.use {
        val declared = it.response.contentLength
        if (declared > maxBytes) throw NetError.TooLarge(maxBytes)
        CappedInputStream(it.response.body, maxBytes).readBytes()
    }

    /** The whole body as text (UTF-8 unless the server says otherwise), refusing more than [maxBytes]. */
    fun readText(opened: Opened, maxBytes: Long): String {
        val charset = opened.response.contentType?.let { CHARSET_RE.find(it)?.groupValues?.get(1) }
            ?.let { runCatching { charset(it) }.getOrNull() } ?: Charsets.UTF_8
        return String(readBytes(opened, maxBytes), charset).removePrefix("﻿")
    }

    private fun classify(e: IOException, wasHttp: Boolean, url: String): IOException = when {
        e is NetError -> e
        wasHttp && (e is SSLException || e is ConnectException || isCleartextError(e)) -> NetError.NeedsSecureLink(url)
        e is UnknownHostException || e is ConnectException || e is SocketTimeoutException -> NetError.NoConnection(e)
        else -> e
    }

    /** Android's "CLEARTEXT communication … not permitted" (in case something still tried http). */
    private fun isCleartextError(e: IOException) = e.message?.contains("CLEARTEXT", ignoreCase = true) == true

    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    private val CHARSET_RE = Regex("""charset\s*=\s*"?([A-Za-z0-9._-]+)""", RegexOption.IGNORE_CASE)
}

/** Passes bytes through until [maxBytes]; one more throws [NetError.TooLarge]. Memory never grows. */
class CappedInputStream(input: InputStream, private val maxBytes: Long) : FilterInputStream(input) {
    private var count = 0L

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) add(1)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) add(n.toLong())
        return n
    }

    private fun add(n: Long) {
        count += n
        if (count > maxBytes) throw NetError.TooLarge(maxBytes)
    }
}
