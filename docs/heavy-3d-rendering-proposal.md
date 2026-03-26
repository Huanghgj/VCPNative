# 重型 3D 渲染方案探讨

更新时间：2026-03-26

## 问题定义

AI 生成的内容中有时包含重型 3D 场景（CSS 3D transforms、WebGL、Three.js、canvas 动画等）。
当前全部走 WebView 渲染，等于在聊天气泡里开浏览器跑 3D — 性能约为原生的 1/3~1/5。

**核心矛盾**：AI 输出的是 HTML/CSS/JS，但原生 3D 需要 OpenGL/Vulkan 指令。

---

## 当前 3D 内容分类

根据 `shouldUseBrowserHtmlRenderer()` 检测到的内容，大致分三档：

| 档位 | 内容类型 | 典型例子 | 当前方案 | GPU 开销 |
|------|---------|---------|---------|----------|
| 轻量 | CSS transform + transition | 卡片翻转、hover 效果 | WebView | 低 |
| 中等 | CSS @keyframes + SVG 动画 | 加载动画、数据可视化 | WebView | 中 |
| 重型 | WebGL / Canvas 2D / Three.js | 3D 模型查看、粒子系统、游戏 | WebView | 极高 |

---

## 方案对比

### 方案 1：WebView 维持现状 + 深度优化

保持 WebView 渲染一切，在现有 A+B 暂停方案基础上继续压榨：

- **WebView 池化**（方案 C）— 消除创建/销毁开销
- **硬件层配置** — 对重型内容设 `LAYER_TYPE_HARDWARE`
- **离屏 WebView 完全回收** — 滑出时 `loadUrl("about:blank")` 释放 GPU 资源，滑回重新加载
- **限制同时活跃 WebView 数** — 最多 2 个 WebView 在运行，其余暂停

```
优点：零改动 AI 输出格式，完全兼容现有内容
缺点：性能天花板就是 Chromium，3D 仍然是双 GPU 管线
适合：绝大多数场景（CSS 3D、SVG 动画、轻度 Canvas）
```

### 方案 2：Canvas/WebGL 内容提升为全屏原生渲染

检测到 WebGL/Canvas 重型内容时，不在气泡内渲染，改为提供一个"全屏查看"按钮，
点击后进入专用的全屏 Activity，用单个 WebView 或 GLSurfaceView 渲染。

```
聊天气泡中：
┌─────────────────────┐
│ [3D 场景预览截图]     │
│ 📐 点击查看 3D 场景   │
└─────────────────────┘

点击后：
┌─────────────────────┐
│                     │
│   全屏 WebView      │
│   或 GLSurfaceView  │
│                     │
└─────────────────────┘
```

**检测策略**：
```kotlin
private fun isHeavy3DContent(html: String): Boolean {
    return Regex("""(?is)<canvas\b""").containsMatchIn(html) &&
        (html.contains("getContext('webgl", ignoreCase = true) ||
         html.contains("getContext(\"webgl", ignoreCase = true) ||
         html.contains("THREE.", ignoreCase = false) ||
         html.contains("babylon", ignoreCase = true))
}
```

```
优点：气泡列表不被重型内容拖慢；全屏时单 WebView 独占 GPU，性能最优
缺点：交互需要多一步点击；需要生成预览截图
适合：WebGL 场景、Three.js 模型、复杂游戏
```

### 方案 3：原生 OpenGL/Vulkan 渲染管线

为特定 3D 场景类型（如模型查看器）建立原生渲染路径。

```
AI 输出结构化 3D 描述 → 解析 → 原生 OpenGL ES 渲染

例如：
AI 输出 glTF/OBJ URL → app 下载模型 → SceneView 库渲染
AI 输出 SVG path data → app 解析 → Compose Canvas 绘制
AI 输出 chart data → app 解析 → MPAndroidChart 渲染
```

**可用的 Android 3D 渲染库**：
| 库 | 用途 | 性能 | 集成难度 |
|---|------|------|---------|
| SceneView (Filament) | glTF 模型查看 | 原生级 | 中 |
| Rajawali | 通用 OpenGL ES | 原生级 | 高 |
| Compose Canvas | 2D 矢量/简单动画 | 原生级 | 低 |
| Unity as a Library | 完整 3D 引擎 | 最佳 | 极高 |

```
优点：真正的原生性能，无双 GPU 管线损耗
缺点：需要 AI 输出结构化格式而非 HTML；每种场景类型都要专门适配
适合：有明确 3D 需求的场景（产品模型展示、地图、数据 3D 可视化）
```

### 方案 4：混合路由架构（推荐长期方向）

根据内容类型自动路由到最佳渲染管线：

```
AI 消息内容
    │
    ├─ 纯文本/Markdown    → TextView           （现有）
    ├─ 静态 HTML          → Native HTML Parser  （现有）
    ├─ 轻量动态 HTML      → WebView + 暂停优化  （现有 + A/B 方案）
    ├─ WebGL/Canvas 重型  → 全屏专用 WebView    （方案 2，新增）
    ├─ glTF 模型链接      → SceneView          （方案 3，新增）
    ├─ 结构化图表数据      → Compose Canvas     （方案 3，新增）
    └─ 未知重型内容       → 气泡内 WebView 降级 （兜底）
```

**实施路线**：

```
阶段 1（当前已完成）：
  ✅ 可见性暂停 + 滚动暂停（方案 A+B）

阶段 2（短期，1-2 周）：
  □ WebView 池化（方案 C from 优化文档）
  □ 重型 3D 内容检测 + 全屏查看（方案 2）

阶段 3（中期，按需）：
  □ glTF 模型原生渲染（SceneView 集成）
  □ 结构化图表原生渲染（Compose Canvas）

阶段 4（长期，按需）：
  □ AI 输出格式扩展（结构化 3D 描述协议）
  □ 更多原生渲染路径
```

---

## 关键决策点

### Q1：是否需要在气泡内实时渲染 3D？

- **是** → 方案 1（WebView 深度优化）或方案 3（原生渲染）
- **否，全屏即可** → 方案 2（最小成本，最大收益）

### Q2：AI 输出格式是否可以扩展？

- **是** → 方案 3/4 可行，AI 输出 glTF URL 或结构化数据
- **否，必须是 HTML** → 只能走方案 1 或 2

### Q3：目标设备性能档位？

- **旗舰机** → WebView 渲染 WebGL 可接受
- **中低端机** → 必须方案 2（全屏）或方案 3（原生），否则会卡死

---

## 技术验证建议

在做最终决策前，可以用 `_extreme_render_fixture.html` 测试：

1. 在当前 WebView 内渲染 → 记录帧率和内存
2. 全屏单 WebView 渲染 → 对比帧率
3. 如有 glTF 模型 → 用 SceneView demo 对比

这能给出具体的性能数据来支撑方案选择。

---

## 开源生态调研（2026-03-26 更新）

### 替代 WebView 的 HTML/CSS 原生渲染引擎

#### 1. Blitz — Rust 模块化 HTML/CSS 渲染引擎
- **仓库**：[DioxusLabs/blitz](https://github.com/DioxusLabs/blitz)
- **原理**：用 Rust 实现独立的 HTML/CSS 渲染，不含 JS 引擎。用 Stylo（Firefox 的 CSS 引擎）做样式计算，Taffy 做 Flexbox/Grid 布局，Parley 做文本排版，最终输出到 Skia 或其他绘图后端
- **支持**：Flexbox、Grid、Table、Block/Inline、CSS 变量、媒体查询、复杂选择器
- **不支持**：JavaScript、WebGL、CSS 3D transforms（未提及）、动画（未提及）
- **成熟度**：**Pre-alpha**，作者明确说"不建议用来构建应用"
- **Android**：未提及 Android 支持
- **评估**：架构非常好（模块化，各组件可独立使用），但离可用距离较远。值得长期跟踪。对我们的价值在于 **blitz-dom + Stylo + Taffy 的组合** — 如果未来需要自建轻量 HTML/CSS 布局引擎，这套技术栈可以参考

#### 2. Servo — Rust 完整浏览器引擎
- **仓库**：[servo/servo](https://github.com/servo/servo)
- **原理**：完整的浏览器引擎，支持 HTML、CSS、JavaScript、WebGL、WebGPU，并行渲染
- **Android**：有 Android 构建支持（NDK 28）
- **嵌入 API**：提供 WebView API，可替代系统 WebView 嵌入应用
- **性能**：利用 Rust 的并行能力，多核渲染。理论上比 Chromium WebView 更轻量
- **成熟度**：2025 年 10 月发布 v0.0.1。**大量网站渲染有问题**，性能也不如主流引擎
- **评估**：方向正确但目前**不适合生产使用**。作为 WebView 的中长期替代候选值得关注。v0.0.1 意味着 API 还在剧烈变动

#### 3. Tauri/WRY — Rust 跨平台 WebView 抽象层
- **仓库**：[tauri-apps/wry](https://github.com/tauri-apps/wry)
- **原理**：统一接口封装各平台 WebView（Android 上仍然是系统 WebView）
- **Servo 集成**：有 PoC 分支用 Servo 替换系统 WebView，但未 production-ready
- **评估**：对我们无直接帮助（Android 上仍然是 Chromium WebView），但 WRY 的抽象层设计可参考

### 原生 3D 渲染方案

#### 4. SceneView — Jetpack Compose 3D/AR 渲染
- **仓库**：[SceneView/sceneview-android](https://github.com/SceneView/sceneview-android)
- **底层**：Google Filament 物理渲染引擎 + ARCore
- **2025 重大更新**：完全重写为 Jetpack Compose API，声明式 3D DSL
- **支持**：glTF 2.0 模型、PBR 材质、光照、动画、AR
- **性能**：原生 OpenGL ES / Vulkan，无双 GPU 管线损耗
- **成熟度**：**生产可用**，Google 官方 Filament 驱动
- **评估**：**最现实的原生 3D 方案**。如果 AI 输出 glTF URL，可以直接用 SceneView 渲染

#### 5. Google Filament — 物理渲染引擎
- **仓库**：[google/filament](https://github.com/google/filament)
- **原理**：跨平台 PBR 引擎，支持 Android/iOS/Windows/Linux/macOS/WebGL2
- **评估**：SceneView 的底层。如果需要更精细控制，可以直接使用 Filament API

### 轻量 HTML 原生渲染（文本/简单样式）

#### 6. HtmlSpanner — Android HTML + CSS 渲染
- **仓库**：[NightWhistler/HtmlSpanner](https://github.com/NightWhistler/HtmlSpanner)
- **支持**：HTML 标签 + CSS style 属性/标签，输出 Android Spannable
- **不支持**：布局、动画、3D、JS
- **评估**：我们的 NativeHtmlInlineTextBlock 已经在做类似的事

#### 7. compose-html / HtmlText — Compose HTML 文本
- **仓库**：[ireward/compose-html](https://github.com/ireward/compose-html)、[ch4rl3x/HtmlText](https://github.com/ch4rl3x/HtmlText)
- **评估**：只处理简单 HTML 文本格式，对我们场景太弱

#### 8. Facebook Yoga + Litho
- **仓库**：[facebook/yoga](https://github.com/facebook/yoga)、[facebook/litho](https://github.com/facebook/litho)
- **Yoga**：CSS Flexbox 布局引擎，React Native 的底层
- **Litho**：声明式 Android UI 框架，用 Yoga 做布局
- **评估**：Yoga 作为布局引擎很成熟，但只做布局不做渲染。如果我们要自建 HTML/CSS 原生渲染，Yoga 可以做布局层

---

## 综合评估矩阵

| 方案 | HTML/CSS | JS | 3D/WebGL | 动画 | Android | 成熟度 | 对我们的价值 |
|------|---------|-----|---------|------|---------|--------|------------|
| 系统 WebView（现有） | ✅ 完整 | ✅ | ✅ | ✅ | ✅ | 生产级 | 当前方案，有双 GPU 损耗 |
| Servo | ✅ 大部分 | ✅ | ✅ WebGL+WebGPU | ✅ | ✅ 有构建 | v0.0.1 | 中长期候选，当前不可用 |
| Blitz | ✅ 布局 | ❌ | ❌ | ❌ | ❌ | Pre-alpha | 架构参考，不可直接用 |
| SceneView/Filament | ❌ | ❌ | ✅ glTF/PBR | ✅ | ✅ | 生产级 | **最佳原生 3D 方案** |
| Yoga + 自建渲染 | 部分布局 | ❌ | ❌ | 需自建 | ✅ | 生产级(Yoga) | 重度投入，不推荐 |
| HtmlSpanner 等 | 仅文本 | ❌ | ❌ | ❌ | ✅ | 成熟 | 已有类似方案 |

## 结论与推荐路径

**现实情况**：目前没有一个成熟的、能在 Android 上替代 WebView 做完整 HTML/CSS/JS/3D 渲染的开源项目。Servo 最接近但不可用，Blitz 方向对但太早期。

**推荐的混合策略**：

```
短期（可立即实施）：
  ✅ WebView + 暂停优化（已完成 A+B）
  → 继续用 WebView 处理 AI 生成的 HTML/CSS/JS
  → 通过可见性暂停压缩开销

中期（1-2 个月）：
  □ 集成 SceneView/Filament
  → AI 输出 glTF 模型 URL 时，用原生 3D 渲染
  → 性能提升 5-10 倍（原生 GPU vs WebView 双管线）
  → 需要扩展 AI 输出协议

  □ WebView 池化（方案 C）
  → 消除创建/销毁开销

  □ 重型内容全屏查看（方案 2）
  → Canvas/WebGL 内容点击后全屏渲染

长期（跟踪 Servo/Blitz）：
  □ 当 Servo Android 嵌入 API 稳定后，评估替换系统 WebView
  → Servo 的并行渲染 + Rust 内存安全是理想方案
  □ 关注 Blitz 的 Android 支持进展
  → 如果 Blitz 支持 CSS 动画 + Android，可用于轻量 HTML 内容
```
