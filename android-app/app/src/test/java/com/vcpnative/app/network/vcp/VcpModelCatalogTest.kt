package com.vcpnative.app.network.vcp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.vcpnative.app.data.datastore.SettingsRepository
import com.vcpnative.app.model.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

class VcpModelCatalogTest {
    @Test
    fun `fetchAvailableModels uses override config instead of saved settings`() = runBlocking {
        val requests = mutableListOf<Pair<String, String?>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/models") { exchange ->
                requests += exchange.requestURI.path to exchange.requestHeaders.getFirst("Authorization")
                respondJson(
                    exchange = exchange,
                    body = """
                        {
                          "data": [
                            { "id": "override-model" }
                          ]
                        }
                    """.trimIndent(),
                )
            }
            start()
        }

        try {
            val catalog = NetworkBackedVcpModelCatalog(
                settingsRepository = FakeSettingsRepository(
                    AppSettings(
                        vcpServerUrl = "http://127.0.0.1:1",
                        vcpApiKey = "saved-key",
                    ),
                ),
                okHttpClient = OkHttpClient(),
            )

            val models = catalog.fetchAvailableModels(
                forceRefresh = true,
                serviceConfigOverride = VcpServiceConfig(
                    baseUrl = "http://127.0.0.1:${server.address.port}/chat/completions",
                    apiKey = "override-key",
                ),
            )

            assertEquals(listOf("override-model"), models.map { it.id })
            assertEquals(1, requests.size)
            assertEquals("/v1/models", requests.single().first)
            assertEquals("Bearer override-key", requests.single().second)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `fetchAvailableModels supports data object and sorts ids`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/models") { exchange ->
                respondJson(
                    exchange = exchange,
                    body = """
                        {
                          "data": [
                            { "id": "z-last" },
                            { "id": "a-first", "owned_by": "tester" }
                          ]
                        }
                    """.trimIndent(),
                )
            }
            start()
        }

        try {
            val catalog = NetworkBackedVcpModelCatalog(
                settingsRepository = FakeSettingsRepository(
                    AppSettings(
                        vcpServerUrl = "http://127.0.0.1:${server.address.port}",
                        vcpApiKey = "key",
                    ),
                ),
                okHttpClient = OkHttpClient(),
            )

            val models = catalog.fetchAvailableModels(forceRefresh = true)

            assertEquals(listOf("a-first", "z-last"), models.map { it.id })
            assertEquals("tester", models.first().ownedBy)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `fetchAvailableModels returns empty list when config is blank`() = runBlocking {
        val catalog = NetworkBackedVcpModelCatalog(
            settingsRepository = FakeSettingsRepository(AppSettings()),
            okHttpClient = OkHttpClient(),
        )

        val models = catalog.fetchAvailableModels(forceRefresh = true)

        assertTrue(models.isEmpty())
    }

    private fun respondJson(
        exchange: HttpExchange,
        body: String,
    ) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}

private class FakeSettingsRepository(
    initial: AppSettings,
) : SettingsRepository {
    private val state = MutableStateFlow(initial)

    override val settings: Flow<AppSettings> = state

    override suspend fun currentSettings(): AppSettings = state.value

    override suspend fun saveConnection(
        serverUrl: String,
        apiKey: String,
    ) {
        state.value = state.value.copy(
            vcpServerUrl = serverUrl,
            vcpApiKey = apiKey,
        )
    }

    override suspend fun saveCompilerOptions(
        enableVcpToolInjection: Boolean,
        enableAgentBubbleTheme: Boolean,
        enableContextFolding: Boolean,
        contextFoldingKeepRecentMessages: Int,
        contextFoldingTriggerMessageCount: Int,
        contextFoldingTriggerCharCount: Int,
        contextFoldingExcerptCharLimit: Int,
        contextFoldingMaxSummaryEntries: Int,
    ) {
        state.value = state.value.copy(
            enableVcpToolInjection = enableVcpToolInjection,
            enableAgentBubbleTheme = enableAgentBubbleTheme,
            enableContextFolding = enableContextFolding,
            contextFoldingKeepRecentMessages = contextFoldingKeepRecentMessages,
            contextFoldingTriggerMessageCount = contextFoldingTriggerMessageCount,
            contextFoldingTriggerCharCount = contextFoldingTriggerCharCount,
            contextFoldingExcerptCharLimit = contextFoldingExcerptCharLimit,
            contextFoldingMaxSummaryEntries = contextFoldingMaxSummaryEntries,
        )
    }

    override suspend fun saveLastSession(
        agentId: String?,
        topicId: String?,
    ) {
        state.value = state.value.copy(
            lastAgentId = agentId,
            lastTopicId = topicId,
        )
    }
}
