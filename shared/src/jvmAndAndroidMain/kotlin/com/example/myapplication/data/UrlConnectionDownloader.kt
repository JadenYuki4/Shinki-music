// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.myapplication.data

import org.schabi.newpipe.extractor.downloader.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.zip.GZIPInputStream

internal class TransportFailure(val status: Int) : IOException("HTTP $status")
internal class ResponseTooLarge : IOException("Source response exceeds budget")

/** No cookie jar, credentials, logging or HTTP client dependency. TLS uses system trust. */
internal class UrlConnectionDownloader : Downloader() {
    override fun execute(request: Request): Response {
        val url = URI(request.url()).toURL()
        require(url.protocol == "https") { "Only HTTPS source requests are supported" }
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 12_000
            connection.readTimeout = 12_000
            connection.instanceFollowRedirects = true
            connection.requestMethod = request.httpMethod()
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; OrbitMusic/0.2)")
            request.headers().forEach { (key, values) ->
                values.forEachIndexed { index, value ->
                    if (index == 0) connection.setRequestProperty(key, value) else connection.addRequestProperty(key, value)
                }
            }
            request.dataToSend()?.let { data ->
                connection.doOutput = true
                connection.outputStream.use { it.write(data) }
            }
            val status = connection.responseCode
            if (status !in 200..299) throw TransportFailure(status)
            val stream = connection.inputStream.let {
                if (connection.contentEncoding.equals("gzip", true)) GZIPInputStream(it) else it
            }
            val body = stream.use { input ->
                val buffer = ByteArray(8192)
                val output = ByteArrayOutputStream()
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 8 * 1024 * 1024) throw ResponseTooLarge()
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }
            return Response(status, connection.responseMessage.orEmpty(),
                connection.headerFields.filterKeys { it != null }, body, connection.url.toString())
        } finally { connection.disconnect() }
    }
}
