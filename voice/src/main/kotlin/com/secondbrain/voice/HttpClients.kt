package com.secondbrain.voice

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import org.slf4j.LoggerFactory

/**
 * Ktor clients for the voice services.
 *
 * The Logging plugin is configured at [LogLevel.INFO] rather than the tempting
 * ALL/HEADERS, and every message goes through [SecretRedactor] on the way out.
 * Ktor's default logger prints request headers verbatim, so an unwrapped
 * Logging plugin writes the Gemini key into ~/.secondbrain/logs on the first
 * call -- a hard-prohibition violation shipped by accident.
 */
object HttpClients {

    fun create(requestTimeoutMs: Long, name: String): HttpClient {
        val slf4j = LoggerFactory.getLogger("http.$name")
        return HttpClient(CIO) {
            expectSuccess = false // we inspect status ourselves to give named errors

            install(HttpTimeout) {
                requestTimeoutMillis = requestTimeoutMs
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = requestTimeoutMs
            }

            install(Logging) {
                logger = SecretRedactor.KtorRedactingLogger(
                    object : io.ktor.client.plugins.logging.Logger {
                        override fun log(message: String) = slf4j.debug(message)
                    }
                )
                // INFO logs method + URL + status, not headers or bodies. Bodies
                // would put transcripts and base64 audio in the log for no gain.
                level = LogLevel.INFO
                sanitizeHeader { header ->
                    header.equals("Authorization", ignoreCase = true) ||
                        header.equals("x-goog-api-key", ignoreCase = true) ||
                        header.equals("x-api-key", ignoreCase = true)
                }
            }
        }
    }
}
