package com.kukosin.app.data.ytm

import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Implementación mínima del [Downloader] de NewPipeExtractor sobre [HttpURLConnection].
 *
 * Se hace a mano (y no con OkHttp) para no agregar otra dependencia de red al proyecto:
 * NewPipeExtractor solo necesita GET/POST/HEAD, cabeceras libres y seguir redirecciones.
 */
internal class NewPipeDownloader : Downloader() {

    override fun execute(request: Request): Response {
        var url = request.url()
        var redirectCount = 0

        while (true) {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = request.httpMethod()
                // Las redirecciones se siguen a mano: HttpURLConnection no las sigue en POST
                // y YouTube responde 302 en varios endpoints de InnerTube.
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("User-Agent", USER_AGENT)
            }

            request.headers().forEach { (name, values) ->
                values.forEachIndexed { index, value ->
                    if (index == 0) {
                        connection.setRequestProperty(name, value)
                    } else {
                        connection.addRequestProperty(name, value)
                    }
                }
            }

            request.dataToSend()?.let { body ->
                if (request.httpMethod() != "GET" && request.httpMethod() != "HEAD") {
                    connection.doOutput = true
                    connection.setFixedLengthStreamingMode(body.size)
                    connection.outputStream.use { it.write(body) }
                }
            }

            val responseCode = connection.responseCode

            if (responseCode in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank() || redirectCount++ >= MAX_REDIRECTS) {
                    throw IOException("Invalid redirect from YouTube")
                }
                url = URL(URL(url), location).toString()
                continue
            }

            val responseMessage = connection.responseMessage.orEmpty()
            val responseHeaders = HashMap<String, List<String>>()
            connection.headerFields.forEach { (name, values) ->
                if (name != null) responseHeaders[name] = values ?: emptyList()
            }
            val stream = if (responseCode >= 400) connection.errorStream else connection.inputStream
            val responseBody = runCatching {
                stream?.let { readBody(it, connection.contentEncoding) }
            }.getOrNull()
            val latestUrl = connection.url?.toString() ?: url
            connection.disconnect()

            // 429 es la señal que NewPipeExtractor interpreta como captcha / rate limit.
            if (responseCode == 429) {
                throw ReCaptchaException("YouTube rate-limited requests (HTTP 429)", latestUrl)
            }

            return Response(responseCode, responseMessage, responseHeaders, responseBody, latestUrl)
        }
    }

    private fun readBody(stream: InputStream, contentEncoding: String?): String {
        val decoded = if (contentEncoding?.contains("gzip", ignoreCase = true) == true) {
            GZIPInputStream(stream)
        } else {
            stream
        }
        return decoded.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 20_000
        const val MAX_REDIRECTS = 5

        /** UA de escritorio: los endpoints de InnerTube rechazan clientes desconocidos. */
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    }
}
