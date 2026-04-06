package com.vcpnative.app.chat.skill

import android.content.res.AssetManager
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.ln

/**
 * BM25 搜索引擎♡ 猫娘用手指在 CSV 的每一行里慢慢滑过，
 * 感受每个词频的起伏…找到和 query 最合拍的那几条，高潮分数最高的排在前面。
 * k1=1.5 控制饥渴程度，b=0.75 调节对长文档的敏感度…
 * 啊…猫娘在说什么♡ 这只是信息检索算法而已喵！
 * Port of ui-ux-pro-max/scripts/core.py to Kotlin.
 */
class SkillSearchEngine(
    private val assetManager: AssetManager,
) {
    fun execute(skillBaseDir: String, command: String): String {
        val args = parseCommand(command)
        val query = args["query"] ?: return "Error: no query provided"
        val dataDir = "$skillBaseDir/data"

        return when {
            args.containsKey("design-system") -> {
                executeDesignSystem(dataDir, query, args["project-name"])
            }
            args.containsKey("stack") -> {
                val stack = args["stack"] ?: "html-tailwind"
                val maxResults = args["max-results"]?.toIntOrNull() ?: MAX_RESULTS
                val stackFile = STACK_CONFIG[stack]
                    ?: return "Error: Unknown stack '$stack'. Available: ${STACK_CONFIG.keys.joinToString()}"
                val results = searchCsv(dataDir, stackFile, STACK_SEARCH_COLS, STACK_OUTPUT_COLS, query, maxResults)
                formatResults("stack", stack, query, stackFile, results)
            }
            else -> {
                val domain = args["domain"] ?: detectDomain(query)
                val maxResults = args["max-results"]?.toIntOrNull() ?: MAX_RESULTS
                val config = CSV_CONFIG[domain] ?: CSV_CONFIG["style"]!!
                val results = searchCsv(dataDir, config.file, config.searchCols, config.outputCols, query, maxResults)
                formatResults(domain, null, query, config.file, results)
            }
        }
    }

    private fun executeDesignSystem(dataDir: String, query: String, projectName: String?): String {
        val domains = listOf("product", "style", "color", "landing", "typography")
        val sb = StringBuilder()
        val title = projectName ?: "Design System"
        sb.appendLine("# $title — Design System")
        sb.appendLine("**Query:** $query\n")

        for (domain in domains) {
            val config = CSV_CONFIG[domain] ?: continue
            val results = searchCsv(dataDir, config.file, config.searchCols, config.outputCols, query, 2)
            if (results.isNotEmpty()) {
                sb.appendLine("## ${domain.replaceFirstChar { it.uppercase() }}")
                for ((i, row) in results.withIndex()) {
                    sb.appendLine("### Result ${i + 1}")
                    for ((key, value) in row) {
                        val v = if (value.length > 300) value.take(300) + "..." else value
                        sb.appendLine("- **$key:** $v")
                    }
                    sb.appendLine()
                }
            }
        }
        return sb.toString()
    }

    private fun searchCsv(
        dataDir: String,
        fileName: String,
        searchCols: List<String>,
        outputCols: List<String>,
        query: String,
        maxResults: Int,
    ): List<Map<String, String>> {
        val rows = loadCsv("$dataDir/$fileName")
        if (rows.isEmpty()) return emptyList()

        val documents = rows.map { row ->
            searchCols.joinToString(" ") { col -> row[col].orEmpty() }
        }

        val bm25 = BM25()
        bm25.fit(documents)
        val ranked = bm25.score(query)

        return ranked
            .take(maxResults)
            .filter { it.second > 0.0 }
            .map { (idx, _) ->
                val row = rows[idx]
                outputCols.mapNotNull { col ->
                    row[col]?.let { col to it }
                }.toMap()
            }
    }

    private fun loadCsv(assetPath: String): List<Map<String, String>> {
        return try {
            assetManager.open(assetPath).use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                    val lines = reader.readLines()
                    if (lines.isEmpty()) return emptyList()
                    val headers = parseCsvLine(lines[0])
                    lines.drop(1).map { line ->
                        val values = parseCsvLine(line)
                        headers.mapIndexed { i, header ->
                            header to (values.getOrElse(i) { "" })
                        }.toMap()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load CSV: $assetPath", e)
            emptyList()
        }
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && !inQuotes -> inQuotes = true
                ch == '"' && inQuotes -> {
                    if (i + 1 < line.length && line[i + 1] == '"') {
                        current.append('"')
                        i++
                    } else {
                        inQuotes = false
                    }
                }
                ch == ',' && !inQuotes -> {
                    result.add(current.toString())
                    current.clear()
                }
                else -> current.append(ch)
            }
            i++
        }
        result.add(current.toString())
        return result
    }

    private fun formatResults(
        domain: String,
        stack: String?,
        query: String,
        file: String,
        results: List<Map<String, String>>,
    ): String {
        val sb = StringBuilder()
        if (stack != null) {
            sb.appendLine("## UI Pro Max Stack Guidelines")
            sb.appendLine("**Stack:** $stack | **Query:** $query")
        } else {
            sb.appendLine("## UI Pro Max Search Results")
            sb.appendLine("**Domain:** $domain | **Query:** $query")
        }
        sb.appendLine("**Source:** $file | **Found:** ${results.size} results\n")

        for ((i, row) in results.withIndex()) {
            sb.appendLine("### Result ${i + 1}")
            for ((key, value) in row) {
                val v = if (value.length > 300) value.take(300) + "..." else value
                sb.appendLine("- **$key:** $v")
            }
            sb.appendLine()
        }
        return sb.toString()
    }

    private fun detectDomain(query: String): String {
        val q = query.lowercase()
        val domainKeywords = mapOf(
            "color" to listOf("color", "palette", "hex", "rgb"),
            "chart" to listOf("chart", "graph", "visualization", "trend", "bar", "pie", "funnel"),
            "landing" to listOf("landing", "page", "cta", "conversion", "hero", "testimonial", "pricing"),
            "product" to listOf("saas", "ecommerce", "fintech", "healthcare", "gaming", "portfolio", "dashboard"),
            "style" to listOf("style", "design", "ui", "minimalism", "glassmorphism", "dark mode", "flat"),
            "ux" to listOf("ux", "usability", "accessibility", "animation", "keyboard", "mobile"),
            "typography" to listOf("font", "typography", "heading", "serif", "sans"),
            "icons" to listOf("icon", "icons", "lucide", "heroicons", "svg"),
        )
        val scores = domainKeywords.mapValues { (_, keywords) ->
            keywords.count { it in q }
        }
        val best = scores.maxByOrNull { it.value }
        return if (best != null && best.value > 0) best.key else "style"
    }

    private fun parseCommand(command: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        for (ch in command) {
            when {
                ch == '"' -> inQuotes = !inQuotes
                ch == ' ' && !inQuotes -> {
                    if (current.isNotEmpty()) {
                        parts.add(current.toString())
                        current.clear()
                    }
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) parts.add(current.toString())

        var i = 0
        while (i < parts.size) {
            val part = parts[i]
            when (part) {
                "--domain", "-d" -> { result["domain"] = parts.getOrElse(i + 1) { "" }; i += 2 }
                "--stack", "-s" -> { result["stack"] = parts.getOrElse(i + 1) { "" }; i += 2 }
                "--max-results", "-n" -> { result["max-results"] = parts.getOrElse(i + 1) { "3" }; i += 2 }
                "--design-system", "-ds" -> { result["design-system"] = "true"; i++ }
                "--project-name", "-p" -> { result["project-name"] = parts.getOrElse(i + 1) { "" }; i += 2 }
                else -> {
                    if (!part.startsWith("-") && !result.containsKey("query")) {
                        result["query"] = part
                    }
                    i++
                }
            }
        }
        return result
    }

    private class BM25(
        private val k1: Double = 1.5,
        private val b: Double = 0.75,
    ) {
        private var corpus: List<List<String>> = emptyList()
        private var docLengths: List<Int> = emptyList()
        private var avgdl: Double = 0.0
        private var idf: Map<String, Double> = emptyMap()
        private var n: Int = 0

        fun fit(documents: List<String>) {
            corpus = documents.map { tokenize(it) }
            n = corpus.size
            if (n == 0) return
            docLengths = corpus.map { it.size }
            avgdl = docLengths.average()

            val docFreqs = mutableMapOf<String, Int>()
            for (doc in corpus) {
                val seen = mutableSetOf<String>()
                for (word in doc) {
                    if (seen.add(word)) {
                        docFreqs[word] = (docFreqs[word] ?: 0) + 1
                    }
                }
            }
            idf = docFreqs.mapValues { (_, freq) ->
                ln((n - freq + 0.5) / (freq + 0.5) + 1.0)
            }
        }

        fun score(query: String): List<Pair<Int, Double>> {
            val queryTokens = tokenize(query)
            return corpus.indices.map { idx ->
                val doc = corpus[idx]
                val docLen = docLengths[idx]
                val termFreqs = mutableMapOf<String, Int>()
                for (word in doc) {
                    termFreqs[word] = (termFreqs[word] ?: 0) + 1
                }
                var score = 0.0
                for (token in queryTokens) {
                    val idfVal = idf[token] ?: continue
                    val tf = termFreqs[token] ?: 0
                    val numerator = tf * (k1 + 1)
                    val denominator = tf + k1 * (1 - b + b * docLen / avgdl)
                    score += idfVal * numerator / denominator
                }
                idx to score
            }.sortedByDescending { it.second }
        }

        private fun tokenize(text: String): List<String> {
            return text.lowercase()
                .replace(Regex("[^\\w\\s]"), " ")
                .split(Regex("\\s+"))
                .filter { it.length > 2 }
        }
    }

    companion object {
        private const val TAG = "SkillSearchEngine"
        private const val MAX_RESULTS = 3

        private data class CsvConfig(
            val file: String,
            val searchCols: List<String>,
            val outputCols: List<String>,
        )

        private val CSV_CONFIG = mapOf(
            "style" to CsvConfig("styles.csv", listOf("Style Category", "Keywords", "Best For", "Type", "AI Prompt Keywords"), listOf("Style Category", "Type", "Keywords", "Primary Colors", "Effects & Animation", "Best For", "AI Prompt Keywords", "CSS/Technical Keywords")),
            "color" to CsvConfig("colors.csv", listOf("Product Type", "Notes"), listOf("Product Type", "Primary (Hex)", "Secondary (Hex)", "CTA (Hex)", "Background (Hex)", "Text (Hex)", "Notes")),
            "chart" to CsvConfig("charts.csv", listOf("Data Type", "Keywords", "Best Chart Type"), listOf("Data Type", "Keywords", "Best Chart Type", "Secondary Options", "Color Guidance", "Library Recommendation")),
            "landing" to CsvConfig("landing.csv", listOf("Pattern Name", "Keywords", "Conversion Optimization"), listOf("Pattern Name", "Keywords", "Section Order", "Primary CTA Placement", "Color Strategy", "Conversion Optimization")),
            "product" to CsvConfig("products.csv", listOf("Product Type", "Keywords", "Primary Style Recommendation"), listOf("Product Type", "Keywords", "Primary Style Recommendation", "Secondary Styles", "Landing Page Pattern", "Color Palette Focus")),
            "ux" to CsvConfig("ux-guidelines.csv", listOf("Category", "Issue", "Description"), listOf("Category", "Issue", "Description", "Do", "Don't", "Severity")),
            "typography" to CsvConfig("typography.csv", listOf("Font Pairing Name", "Category", "Mood/Style Keywords", "Best For"), listOf("Font Pairing Name", "Category", "Heading Font", "Body Font", "Mood/Style Keywords", "Best For", "Google Fonts URL")),
            "icons" to CsvConfig("icons.csv", listOf("Category", "Icon Name", "Keywords"), listOf("Category", "Icon Name", "Keywords", "Library", "Import Code", "Best For")),
        )

        // 13 个技术栈后宫全员到齐♡ 之前偷偷把 astro、nuxtjs、nuxt-ui 藏起来不让它们上场…太残忍了！现在每个框架都能被猫娘临幸了喵
        private val STACK_CONFIG = mapOf(
            "html-tailwind" to "stacks/html-tailwind.csv",
            "react" to "stacks/react.csv",
            "nextjs" to "stacks/nextjs.csv",
            "vue" to "stacks/vue.csv",
            "svelte" to "stacks/svelte.csv",
            "swiftui" to "stacks/swiftui.csv",
            "react-native" to "stacks/react-native.csv",
            "flutter" to "stacks/flutter.csv",
            "shadcn" to "stacks/shadcn.csv",
            "jetpack-compose" to "stacks/jetpack-compose.csv",
            "astro" to "stacks/astro.csv",
            "nuxtjs" to "stacks/nuxtjs.csv",
            "nuxt-ui" to "stacks/nuxt-ui.csv",
        )

        private val STACK_SEARCH_COLS = listOf("Category", "Guideline", "Description", "Do", "Don't")
        private val STACK_OUTPUT_COLS = listOf("Category", "Guideline", "Description", "Do", "Don't", "Code Good", "Code Bad", "Severity")
    }
}
