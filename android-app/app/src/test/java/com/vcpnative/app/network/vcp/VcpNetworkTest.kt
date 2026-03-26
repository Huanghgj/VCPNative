package com.vcpnative.app.network.vcp

import org.junit.Assert.assertEquals
import org.junit.Test

class VcpNetworkTest {
    @Test
    fun `origin base url defaults to v1 endpoints`() {
        val config = VcpServiceConfig(
            baseUrl = "http://127.0.0.1:8080",
            apiKey = "key",
        )

        assertEquals("http://127.0.0.1:8080/v1", config.apiRootUrl)
        assertEquals("http://127.0.0.1:8080/v1/chat/completions", config.chatUrl(enableVcpToolInjection = false))
        assertEquals("http://127.0.0.1:8080/v1/chatvcp/completions", config.chatUrl(enableVcpToolInjection = true))
        assertEquals("http://127.0.0.1:8080/v1/models", config.modelsUrl)
        assertEquals("http://127.0.0.1:8080/v1/interrupt", config.interruptUrl)
    }

    @Test
    fun `full chat endpoint preserves proxy prefix for related endpoints`() {
        val config = VcpServiceConfig(
            baseUrl = "http://127.0.0.1:8080/proxy/v1/chat/completions",
            apiKey = "key",
        )

        assertEquals("http://127.0.0.1:8080/proxy/v1", config.apiRootUrl)
        assertEquals("http://127.0.0.1:8080/proxy/v1/chat/completions", config.chatUrl(enableVcpToolInjection = false))
        assertEquals("http://127.0.0.1:8080/proxy/v1/chatvcp/completions", config.chatUrl(enableVcpToolInjection = true))
        assertEquals("http://127.0.0.1:8080/proxy/v1/models", config.modelsUrl)
        assertEquals("http://127.0.0.1:8080/proxy/v1/interrupt", config.interruptUrl)
    }

    @Test
    fun `base path without version appends v1`() {
        val config = VcpServiceConfig(
            baseUrl = "http://127.0.0.1:8080/proxy",
            apiKey = "key",
        )

        assertEquals("http://127.0.0.1:8080/proxy/v1", config.apiRootUrl)
        assertEquals("http://127.0.0.1:8080/proxy/v1/chat/completions", config.chatUrl(enableVcpToolInjection = false))
        assertEquals("http://127.0.0.1:8080/proxy/v1/models", config.modelsUrl)
    }
}
