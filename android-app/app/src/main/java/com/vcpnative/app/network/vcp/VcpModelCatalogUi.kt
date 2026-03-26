package com.vcpnative.app.network.vcp

const val MODEL_CATALOG_LOADING_TEXT = "正在获取模型列表…"

fun buildModelFetchFailureText(
    error: Throwable,
    fallbackHint: String,
): String = buildString {
    append(error.message ?: "模型列表获取失败")
    append(fallbackHint)
}
