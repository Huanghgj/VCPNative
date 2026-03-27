package com.vcpnative.app.chat.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VcpChatMessageParserTest {
    @Test
    fun `contiguous html fragment is parsed as single html block`() {
        val content = """
            <div id="vcp-root" style="padding: 30px;">
            <!-- 装饰背景 -->
            <style>
                .vcp-dot {
                    display: inline-block;
                }
            </style>

            <div>
                <h2><span class="vcp-dot"></span>你好，我的朋友</h2>
                <button onclick="input('我们今天聊点什么？')">开始探索</button>
                <button data-send="你现在是什么状态？">了解我的状态</button>
            </div>
            </div>
        """.trimIndent()

        val blocks = parseBlocks(content)

        assertBlockTypes(
            blocks,
            "HtmlDocument",
        )
        val code = getStringProperty(blocks.single(), "getCode")
        assertTrue(code.contains("</style>"))
        assertTrue(code.contains("input('我们今天聊点什么？')"))
        assertTrue(code.contains("data-send=\"你现在是什么状态？\""))
    }

    @Test
    fun `html block stops before following markdown paragraph`() {
        val content = """
            <div><strong>hello</strong></div>

            这里应该回到普通段落
        """.trimIndent()

        val blocks = parseBlocks(content)

        assertBlockTypes(
            blocks,
            "HtmlDocument",
            "MarkdownDocument",
        )
        val htmlText = getStringProperty(blocks[0], "getCode")
        val markdownText = getStringProperty(blocks[1], "getText")
        assertTrue(htmlText.contains("<div><strong>hello</strong></div>"))
        assertTrue(markdownText.contains("这里应该回到普通段落"))
    }

    @Test
    fun `button only html is converted to interactive buttons block`() {
        val content = """
            <button onclick="input('我们今天聊点什么？')">开始探索</button>
            <button data-send="你现在是什么状态？">了解我的状态</button>
        """.trimIndent()

        val blocks = parseBlocks(content)

        assertBlockTypes(
            blocks,
            "InteractiveButtons",
        )

        @Suppress("UNCHECKED_CAST")
        val buttons = blocks.single().javaClass.getMethod("getButtons").invoke(blocks.single()) as List<Any>
        assertEquals(2, buttons.size)
        assertEquals("开始探索", getStringProperty(buttons[0], "getLabel"))
        assertEquals("我们今天聊点什么？", getStringProperty(buttons[0], "getSendText"))
        assertEquals("了解我的状态", getStringProperty(buttons[1], "getLabel"))
        assertEquals("你现在是什么状态？", getStringProperty(buttons[1], "getSendText"))
    }

    @Test
    fun `generic markdown is preserved around tool request block`() {
        val content = """
            ## 前言

            先输出一段普通 markdown。

            <<<[TOOL_REQUEST]>>>
            tool_name: 「始」Search「末」
            command: 「始」query「末」
            <<<[END_TOOL_REQUEST]>>>

            收尾文本
        """.trimIndent()

        val blocks = parseBlocks(content)

        assertBlockTypes(
            blocks,
            "MarkdownDocument",
            "ToolUse",
            "MarkdownDocument",
        )
        assertTrue(getStringProperty(blocks[0], "getText").contains("## 前言"))
        assertEquals("Search", getStringProperty(blocks[1], "getToolName"))
        assertTrue(getStringProperty(blocks[2], "getText").contains("收尾文本"))
    }

    @Test
    fun `mermaid code fence is isolated from surrounding markdown`() {
        val content = """
            前文

            ```mermaid
            graph TD
            A-->B
            ```

            后文
        """.trimIndent()

        val blocks = parseBlocks(content)

        assertBlockTypes(
            blocks,
            "MarkdownDocument",
            "MermaidDiagram",
            "MarkdownDocument",
        )
        assertEquals("graph TD\nA-->B", getStringProperty(blocks[1], "getCode"))
    }

    @Test
    fun `native html document keeps body content and extracts stylesheet`() {
        val content = """
            <html>
            <head>
                <style>
                    .title { color: #ffffff; }
                </style>
            </head>
            <body>
                <div class="title">Hello</div>
            </body>
            </html>
        """.trimIndent()

        val document = parseNativeHtmlDocument(content)
        val nodes = getListProperty(document, "getNodes")
        val styleSheet = getListProperty(document, "getStyleSheet")

        assertEquals(1, nodes.size)
        assertEquals("div", getStringProperty(nodes.single(), "getTagName"))
        assertEquals(1, styleSheet.size)
    }

    @Test
    fun `native html style merges stylesheet and inline declarations`() {
        val content = """
            <html>
            <head>
                <style>
                    .card { color: #ffffff; background-color: #112233; }
                </style>
            </head>
            <body>
                <div class="card" style="font-size: 32px; font-weight: bold;">Hello</div>
            </body>
            </html>
        """.trimIndent()

        val document = parseNativeHtmlDocument(content)
        val nodes = getListProperty(document, "getNodes")
        val styleSheet = getListProperty(document, "getStyleSheet")
        val style = resolveNativeHtmlStyle(nodes.single(), styleSheet)

        assertNotNull(invokeGetterByPrefix(style, "getTextColor"))
        assertNotNull(invokeGetterByPrefix(style, "getBackgroundColor"))
        assertEquals(32f, style.javaClass.getMethod("getFontSizeSp").invoke(style) as Float, 0.001f)
        assertEquals(true, style.javaClass.getMethod("getBold").invoke(style) as Boolean)
    }

    @Test
    fun `browser grade html uses browser renderer instead of safe fallback`() {
        val content = """
            <div id="vcp-root">
                <svg><filter id="vcp-glitch-filter"></filter></svg>
                <style>
                    @keyframes vcp-spin-3d { from { opacity: 0; } to { opacity: 1; } }
                </style>
                <button onclick="input('测试')">按钮</button>
            </div>
        """.trimIndent()

        assertEquals(true, shouldUseBrowserHtmlRenderer(content))
        assertEquals(false, shouldRenderMessageInSafeMode(content))
        assertEquals(false, shouldFallbackToSafeHtmlRenderer(content))
    }

    @Test
    fun `absurdly large plain html skips browser renderer and still trips safe fallback`() {
        val repeated = "<div style=\"padding: 8px;\">block</div>\n".repeat(2_500)

        assertEquals(false, shouldUseBrowserHtmlRenderer(repeated))
        assertEquals(true, shouldRenderMessageInSafeMode(repeated))
        assertEquals(true, shouldFallbackToSafeHtmlRenderer(repeated))
    }

    @Test
    fun `truncated browser html is detected and repaired`() {
        val content = """
            <div id="vcp-root">
                <div class="panel">ok</div>
                <div style="position: absolute; top: 50%; left: 50%;
        """.trimIndent()

        assertEquals(true, isProbablyTruncatedBrowserHtml(content))

        val repaired = repairBrowserHtmlForRender(content)
        assertEquals(false, repaired.contains("""<div style="position: absolute; top: 50%; left: 50%;"""))
        assertTrue(repaired.contains("""<div class="panel">ok</div>"""))
        assertTrue(repaired.endsWith("</div>"))
    }

    @Test
    fun `isolated closing tag fragments are repaired before browser render`() {
        val content = """
            <div id="vcp-root">
                <div style="margin:12px 0;text-align:center;">
                    <span style="font-size:28px;">/span>
                </div>
            </div>
        """.trimIndent()

        val repaired = repairBrowserHtmlForRender(content)

        assertTrue(repaired.contains("""<span style="font-size:28px;"></span>"""))
        assertEquals(-1, repaired.indexOf("""<span style="font-size:28px;">/span>"""))
    }

    @Test
    fun `inline literal slash text is not mistaken for closing tag`() {
        val content = "<div><p>literal /span> text</p></div>"

        val repaired = repairBrowserHtmlForRender(content)

        assertTrue(repaired.contains("literal /span> text"))
    }

    @Test
    fun `native html parser uses repaired html fragments`() {
        val document = parseNativeHtmlDocument(
            "<div><span style=\"font-size:28px;\">/span><p>ok</p></div>",
        )

        val nodes = getListProperty(document, "getNodes")
        assertEquals(1, nodes.size)
        assertEquals("div", getStringProperty(nodes.single(), "getTagName"))

        val children = getListProperty(nodes.single(), "getChildren")
        assertEquals(2, children.size)
        assertEquals("span", getStringProperty(children[0], "getTagName"))
        assertEquals("p", getStringProperty(children[1], "getTagName"))
    }

    @Test
    fun `suspiciously small browser html height is guarded before first confirmed measurement`() {
        val normalized = normalizeBrowserHtmlMeasuredHeightPx(
            reportedHeightPx = 48,
            estimatedHeightPx = 300,
            minHeightPx = 48,
            htmlLength = 1_400,
            hasConfirmedHeight = false,
        )

        assertEquals(300, normalized)
    }

    @Test
    fun `small browser html height is accepted after content has confirmed layout`() {
        val normalized = normalizeBrowserHtmlMeasuredHeightPx(
            reportedHeightPx = 72,
            estimatedHeightPx = 300,
            minHeightPx = 48,
            htmlLength = 1_400,
            hasConfirmedHeight = true,
        )

        assertEquals(72, normalized)
    }

    @Test
    fun `short browser html is not blocked by height guard`() {
        val normalized = normalizeBrowserHtmlMeasuredHeightPx(
            reportedHeightPx = 48,
            estimatedHeightPx = 80,
            minHeightPx = 48,
            htmlLength = 120,
            hasConfirmedHeight = false,
        )

        assertEquals(48, normalized)
    }

    @Test
    fun `browser html is not paused before first stable height confirmation`() {
        assertEquals(
            false,
            shouldPauseBrowserHtmlWebView(
                pauseDynamicContent = true,
                hasConfirmedHeight = false,
            ),
        )
    }

    @Test
    fun `browser html pause resumes after first stable height confirmation`() {
        assertEquals(
            true,
            shouldPauseBrowserHtmlWebView(
                pauseDynamicContent = true,
                hasConfirmedHeight = true,
            ),
        )
    }

    @Test
    fun `standalone html img tag is parsed as remote image block`() {
        val content =
            """<img src="http://185.200.64.127:6005/pw=huanglei@./images/comfyuigen/41f09722-c269-4d9c-868e-429b84c0591a.png" alt="solo, young girl, extremely petite, flat chest, child-like proportions, small fr..." width="300">"""

        val blocks = parseBlocks(content)

        assertBlockTypes(
            blocks,
            "RemoteImage",
        )
        assertEquals(
            "http://185.200.64.127:6005/pw=huanglei@./images/comfyuigen/41f09722-c269-4d9c-868e-429b84c0591a.png",
            getStringProperty(blocks.single(), "getUrl"),
        )
    }

    private fun parseBlocks(content: String): List<Any> {
        val parserClass = Class.forName("com.vcpnative.app.chat.render.VcpChatMessageParser")
        val instance = parserClass.getField("INSTANCE").get(null)
        val parseMethod = parserClass.getDeclaredMethod(
            "parse",
            String::class.java,
            ChatRenderMode::class.java,
            String::class.java,
        )
        val document = parseMethod.invoke(instance, content, ChatRenderMode.Final, "assistant")
        val blocksMethod = document.javaClass.getDeclaredMethod("getBlocks")
        @Suppress("UNCHECKED_CAST")
        return blocksMethod.invoke(document) as List<Any>
    }

    private fun parseNativeHtmlDocument(content: String): Any {
        val rendererClass = Class.forName("com.vcpnative.app.chat.render.ChatMessageRendererKt")
        val parseMethod = rendererClass.getDeclaredMethod(
            "parseNativeHtmlDocument",
            String::class.java,
        )
        parseMethod.isAccessible = true
        return parseMethod.invoke(null, content)!!
    }

    private fun resolveNativeHtmlStyle(node: Any, styleSheet: List<Any>): Any {
        val rendererClass = Class.forName("com.vcpnative.app.chat.render.ChatMessageRendererKt")
        val resolveMethod = rendererClass.declaredMethods.first {
            it.name == "resolveNativeHtmlStyle" && it.parameterTypes.size == 2
        }
        resolveMethod.isAccessible = true
        return resolveMethod.invoke(null, node, styleSheet)!!
    }

    private fun shouldRenderMessageInSafeMode(content: String): Boolean {
        val rendererClass = Class.forName("com.vcpnative.app.chat.render.ChatMessageRendererKt")
        val method = rendererClass.getDeclaredMethod(
            "shouldRenderMessageInSafeMode",
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(null, content) as Boolean
    }

    private fun shouldUseBrowserHtmlRenderer(content: String): Boolean {
        val rendererClass = Class.forName("com.vcpnative.app.chat.render.ChatMessageRendererKt")
        val method = rendererClass.getDeclaredMethod(
            "shouldUseBrowserHtmlRenderer",
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(null, content) as Boolean
    }

    private fun shouldFallbackToSafeHtmlRenderer(content: String): Boolean {
        val rendererClass = Class.forName("com.vcpnative.app.chat.render.ChatMessageRendererKt")
        val method = rendererClass.getDeclaredMethod(
            "shouldFallbackToSafeHtmlRenderer",
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(null, content) as Boolean
    }

    private fun isProbablyTruncatedBrowserHtml(content: String): Boolean {
        val rendererClass = Class.forName("com.vcpnative.app.chat.render.ChatMessageRendererKt")
        val method = rendererClass.getDeclaredMethod(
            "isProbablyTruncatedBrowserHtml",
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(null, content) as Boolean
    }

    private fun repairBrowserHtmlForRender(content: String): String {
        val rendererClass = Class.forName("com.vcpnative.app.chat.render.ChatMessageRendererKt")
        val method = rendererClass.getDeclaredMethod(
            "repairBrowserHtmlForRender",
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(null, content) as String
    }

    private fun normalizeBrowserHtmlMeasuredHeightPx(
        reportedHeightPx: Int,
        estimatedHeightPx: Int,
        minHeightPx: Int,
        htmlLength: Int,
        hasConfirmedHeight: Boolean,
    ): Int {
        val rendererClass = Class.forName("com.vcpnative.app.chat.render.ChatMessageRendererKt")
        val method = rendererClass.getDeclaredMethod(
            "normalizeBrowserHtmlMeasuredHeightPx",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        )
        method.isAccessible = true
        return method.invoke(
            null,
            reportedHeightPx,
            estimatedHeightPx,
            minHeightPx,
            htmlLength,
            hasConfirmedHeight,
        ) as Int
    }

    private fun shouldPauseBrowserHtmlWebView(
        pauseDynamicContent: Boolean,
        hasConfirmedHeight: Boolean,
    ): Boolean {
        val rendererClass = Class.forName("com.vcpnative.app.chat.render.ChatMessageRendererKt")
        val method = rendererClass.getDeclaredMethod(
            "shouldPauseBrowserHtmlWebView",
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        )
        method.isAccessible = true
        return method.invoke(
            null,
            pauseDynamicContent,
            hasConfirmedHeight,
        ) as Boolean
    }

    private fun assertBlockTypes(blocks: List<Any>, vararg expectedSimpleNames: String) {
        assertEquals(expectedSimpleNames.toList(), blocks.map { it.javaClass.simpleName })
    }

    @Suppress("UNCHECKED_CAST")
    private fun getListProperty(target: Any, getterName: String): List<Any> =
        target.javaClass.getMethod(getterName).invoke(target) as List<Any>

    private fun invokeGetterByPrefix(target: Any, prefix: String): Any? =
        target.javaClass.methods.first { it.name.startsWith(prefix) }.invoke(target)

    private fun getStringProperty(target: Any, getterName: String): String =
        target.javaClass.getMethod(getterName).invoke(target) as String
}
