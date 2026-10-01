package com.thelightphone.listen.podcasts.net

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * The real [HttpTransport]: OkHttp with redirects turned off (Listen follows them itself,
 * upgrading each hop to https). OkHttp unzips gzip responses by itself. Always call off the
 * main thread.
 */
class OkHttpTransport(
    private val client: OkHttpClient = defaultClient,
) : HttpTransport {

    override fun get(url: String, headers: Map<String, String>): HttpResponse {
        val request = Request.Builder().url(url).apply {
            header("User-Agent", USER_AGENT)
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        val response = client.newCall(request).execute()
        val body = response.body
        return HttpResponse(
            code = response.code,
            location = response.header("Location"),
            contentType = response.header("Content-Type"),
            contentLength = body.contentLength(),
            body = body.byteStream(),
            onClose = { response.close() },
        )
    }

    companion object {
        /** Podcast hosts count listeners by user agent; say plainly who we are. */
        const val USER_AGENT = "Listen/1.0 (Light Phone III podcast player)"

        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }
}
