package com.vcpnative.app.network.vcp

import com.vcpnative.app.model.AppSettings
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class VcpServiceConfig(
    val baseUrl: String,
    val apiKey: String,
) {
    fun chatUrl(enableVcpToolInjection: Boolean): String {
        val endpointPath = if (enableVcpToolInjection) {
            "chatvcp/completions"
        } else {
            "chat/completions"
        }
        return buildEndpointUrl(endpointPath)
    }

    val apiRootUrl: String
        get() = parsedApiRootUrl()
            ?.toString()
            ?.trimEnd('/')
            ?: baseUrl.trimEnd('/')

    val modelsUrl: String
        get() = buildEndpointUrl("models")

    val interruptUrl: String
        get() = buildEndpointUrl("interrupt")

    private fun buildEndpointUrl(endpointPath: String): String {
        val apiRoot = parsedApiRootUrl() ?: return baseUrl.trim()
        return apiRoot.newBuilder()
            .encodedPath(appendEndpointPath(apiRoot.encodedPath, endpointPath))
            .query(null)
            .fragment(null)
            .build()
            .toString()
    }

    private fun parsedApiRootUrl(): HttpUrl? {
        val rawUrl = baseUrl.trim()
        val url = rawUrl.toHttpUrlOrNull() ?: return null
        return url.newBuilder()
            .encodedPath(normalizeApiRootPath(url.encodedPath))
            .query(null)
            .fragment(null)
            .build()
    }

    private fun appendEndpointPath(
        apiRootPath: String,
        endpointPath: String,
    ): String {
        val normalizedRoot = apiRootPath.removeSuffix("/").ifBlank { "" }
        return if (normalizedRoot.isBlank()) {
            "/$endpointPath"
        } else {
            "$normalizedRoot/$endpointPath"
        }
    }

    private fun normalizeApiRootPath(path: String): String {
        val trimmedPath = path.trimEnd('/').ifBlank { "/" }
        val strippedPath = KNOWN_API_ENDPOINT_SUFFIXES.firstNotNullOfOrNull { suffix ->
            trimmedPath
                .takeIf { it.endsWith(suffix, ignoreCase = true) }
                ?.removeSuffix(suffix)
                ?.ifBlank { "/" }
        } ?: trimmedPath

        if (strippedPath == "/" || strippedPath.isBlank()) {
            return "/v1"
        }
        if (strippedPath.endsWith("/v1", ignoreCase = true)) {
            return strippedPath
        }
        return "$strippedPath/v1"
    }

    private companion object {
        val KNOWN_API_ENDPOINT_SUFFIXES = listOf(
            "/chatvcp/completions",
            "/chat/completions",
            "/models",
            "/interrupt",
        )
    }
}

fun AppSettings.toServiceConfig(): VcpServiceConfig =
    VcpServiceConfig(
        baseUrl = vcpServerUrl.trim(),
        apiKey = vcpApiKey.trim(),
    )

fun defaultVcpHttpClient(): OkHttpClient =
    OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
