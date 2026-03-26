package com.vcpnative.app.network.vcp

import com.vcpnative.app.data.datastore.SettingsRepository
import com.vcpnative.app.model.VcpModelInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

interface VcpModelCatalog {
    suspend fun fetchAvailableModels(
        forceRefresh: Boolean = false,
        serviceConfigOverride: VcpServiceConfig? = null,
    ): List<VcpModelInfo>
}

class NetworkBackedVcpModelCatalog(
    private val settingsRepository: SettingsRepository,
    private val okHttpClient: OkHttpClient,
) : VcpModelCatalog {
    private val mutex = Mutex()
    private var cacheKey: String? = null
    private var cachedModels: List<VcpModelInfo> = emptyList()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun fetchAvailableModels(
        forceRefresh: Boolean,
        serviceConfigOverride: VcpServiceConfig?,
    ): List<VcpModelInfo> =
        mutex.withLock {
            val settings = settingsRepository.currentSettings()
            val serviceConfig = serviceConfigOverride ?: settings.toServiceConfig()
            val nextCacheKey = "${serviceConfig.apiRootUrl}|${serviceConfig.apiKey}"

            if (!forceRefresh && cacheKey == nextCacheKey && cachedModels.isNotEmpty()) {
                return@withLock cachedModels
            }

            if (serviceConfig.baseUrl.isBlank() || serviceConfig.apiKey.isBlank()) {
                cacheKey = nextCacheKey
                cachedModels = emptyList()
                return@withLock emptyList()
            }

            val request = Request.Builder()
                .url(serviceConfig.modelsUrl)
                .header("Authorization", "Bearer ${serviceConfig.apiKey}")
                .get()
                .build()

            withContext(Dispatchers.IO) {
                okHttpClient.newCall(request).execute()
            }.use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) {
                    val errorMessage = extractErrorMessage(body)
                    error(
                        buildString {
                            append("获取模型列表失败: HTTP ${response.code}")
                            if (!errorMessage.isNullOrBlank()) {
                                append(" · ")
                                append(errorMessage)
                            }
                        },
                    )
                }

                val models = parseModels(body)

                cacheKey = nextCacheKey
                cachedModels = models
                return@withLock models
            }
        }

    private fun parseModels(body: String): List<VcpModelInfo> {
        if (body.isBlank()) {
            return emptyList()
        }

        val modelsArray = try {
            extractModelArray(
                json.parseToJsonElement(body.trim()),
            )
        } catch (error: Exception) {
            error("模型列表响应不是可解析的 JSON: ${body.trim().take(240)}")
        }

        return modelsArray.mapNotNull { element ->
            val modelObject = element as? JsonObject ?: return@mapNotNull null
            val id = modelObject.stringValue("id").orEmpty()
            if (id.isBlank()) {
                null
            } else {
                VcpModelInfo(
                    id = id,
                    ownedBy = modelObject.stringValue("owned_by"),
                )
            }
        }.sortedBy { it.id.lowercase() }
    }

    private fun extractErrorMessage(body: String): String? {
        if (body.isBlank()) {
            return null
        }
        return try {
            val root = json.parseToJsonElement(body).jsonObject
            when (val error = root["error"]) {
                is JsonObject -> error.stringValue("message") ?: error.toString()
                is JsonPrimitive -> error.contentOrNull ?: root.stringValue("message") ?: body
                else -> root.stringValue("message") ?: body
            }.trim()
        } catch (_: Exception) {
            body.trim().take(240)
        }
    }

    private fun extractModelArray(root: JsonElement): JsonArray =
        when (root) {
            is JsonArray -> root
            is JsonObject -> when {
                root["data"] is JsonArray -> root["data"] as JsonArray
                root["models"] is JsonArray -> root["models"] as JsonArray
                else -> JsonArray(emptyList())
            }

            else -> JsonArray(emptyList())
        }

    private fun JsonObject.stringValue(key: String): String? =
        this[key]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
            ?.ifBlank { null }
}
