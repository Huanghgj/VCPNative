# Android Chat Renderer Status

日期：2026-03-26

## 本轮完成

- Android 聊天渲染继续对照以下桌面实现补齐语义：
  - `/root/VCPChat/modules/renderer/contentProcessor.js`
  - `/root/VCPChat/modules/renderer/streamManager.js`
  - `/root/VCPChat/modules/messageRenderer.js`
- 当前渲染方向改为混合架构：
  - 普通 markdown / 轻量 html 仍优先走 Android 原生 `Markwon` / `TextView/Spannable`
  - 浏览器级 HTML 文档改为走隔离 `WebView`
  - `HtmlDocument` 会按内容复杂度自动分流，而不是强行全部走同一条原生链路
- `ChatMessageRenderer.kt` 当前主策略：
  - 流式与最终态继续沿用 `/root/VCPChat` 那套预处理思路
  - 普通 markdown/html 不再默认送进自写块级解析器
  - 带 `<style>` / `<svg>` / CSS 动画 / JS 事件 / 大量内联样式的浏览器级 HTML，直接交给 `WebView`
  - `WebView` 高度回调已切主线程，并对流式更新做了延迟提交，避免每个 chunk 都整页重载
  - 只对 VCP 专有块保留分流渲染
  - 段内 `[[点击按钮:...]]` 和 `{{VCPChatCanvas}}` 仍可拆块处理
  - 纯按钮 HTML 继续支持 `data-send` / `onclick="input('...')"`
  - Markdown 图片、裸图片 URL、`<img ...>` 继续支持 Android 原生显示
  - ToolResult 里的图片 URL 字段继续直接显示图片
- 额外修复：
  - `ensureNewlineAfterCodeBlock` 不再错误拆坏 ` ```mermaid ` / ` ```python ` 这类合法 fenced code block
  - 新增解析单测，覆盖 HTML fragment、按钮、tool request 分流、mermaid 围栏

## 当前聊天可测范围

- 真正可发单聊，走真实 HTTP + SSE + `/v1/interrupt`
- 流式消息持续重渲染
- VCP 特殊块：
  - `<<<[TOOL_REQUEST]>>>`
  - `[[VCP调用结果信息汇总: ... VCP调用结果结束]]`
  - `<<<DailyNoteStart>>>`
  - `[--- VCP元思考链 ---]`
  - `<think>...</think>`
  - `<<<[DESKTOP_PUSH]>>>`
  - `<<<[ROLE_DIVIDE_*]>>>`
- AI 生成按钮可点击回发
- 图片可直接显示
- 普通 markdown/html 改为 Android 原生渲染

## 仍未做完的点

- Mermaid 仍是源码展示，不是真图形渲染。
- 浏览器级 HTML 现在交给 `WebView`，但长列表里同时存在很多复杂 `WebView` 时，仍可能继续压缩滚动性能。
- Android 原生链路仍然不是桌面 DOM 运行时；只有复杂 HTML 文档才会走 `WebView`。
- 若后续遇到特定流式边界问题，优先继续补：
  - 未闭合 tool result 尾块
  - 未闭合 protected marker 跨行段
  - 超长消息下的增量解析开销

## 构建说明

- 这台机器内存紧，默认 Gradle `-Xmx4g` 在 `compileDebugKotlin` 阶段可能被 OOM kill。
- 当前可稳定使用的构建命令：

```bash
cd /root/VCPNative/android-app
./gradlew :app:assembleDebug --no-daemon \
  -Pkotlin.incremental=false \
  -Pksp.incremental=false \
  -Dorg.gradle.jvmargs='-Xmx2g -Dfile.encoding=UTF-8' \
  -Dkotlin.compiler.execution.strategy=in-process
```

- 最新可测 APK：
  - `/root/VCPNative/android-app/app/build/outputs/apk/debug/app-debug.apk`
