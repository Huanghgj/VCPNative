# VCPNative 文档索引

更新时间：2026-03-25

当前文档按“先定边界，再定契约，再定范围”的顺序阅读。

## 阅读顺序

1. [REBUILD_PLAN.md](./REBUILD_PLAN.md)
   说明项目现在到底要做什么，不做什么。

2. [VCPCHAT_MIGRATION_MAP.md](./VCPCHAT_MIGRATION_MAP.md)
   说明当前桌面版 `VCPChat` 的真实职责与兼容压力。

3. [APPDATA_SCHEMA.md](./APPDATA_SCHEMA.md)
   固化本地数据目录、配置文件、历史消息、附件对象的结构。

4. [NETWORK_AND_STREAM_CONTRACT.md](./NETWORK_AND_STREAM_CONTRACT.md)
   固化 `VCPChat <-> VCPToolBox` 的请求、中断、流式、日志契约。

5. [DESKTOP_TO_ANDROID_FEATURE_MAP.md](./DESKTOP_TO_ANDROID_FEATURE_MAP.md)
   把桌面能力按“首版必迁 / 后置 / 重设计 / 不迁移”逐项分类。

6. [DECISION_REGISTER.md](./DECISION_REGISTER.md)
   把“这是迁移不是重构”“能抄就抄”这类核心决策正式固定下来。

7. [ANDROID_DOMAIN_MODEL.md](./ANDROID_DOMAIN_MODEL.md)
   给出 Android 端建议的领域模型、持久化模型和流事件模型。

8. [ANDROID_DATA_LAYER_DESIGN.md](./ANDROID_DATA_LAYER_DESIGN.md)
   固化 Android 运行时由 `Room / DataStore / files` 各自负责什么。

9. [APPDATA_IMPORT_EXPORT_STRATEGY.md](./APPDATA_IMPORT_EXPORT_STRATEGY.md)
   固化桌面 `AppData` 的导入、导出、passthrough 和冲突规则。

10. [ANDROID_INFORMATION_ARCHITECTURE.md](./ANDROID_INFORMATION_ARCHITECTURE.md)
   把 Android 端入口、导航、页面层级、状态边界与宿主职责抽清楚。

11. [ANDROID_SKELETON_SELECTION.md](./ANDROID_SKELETON_SELECTION.md)
   固化 Android 工程应该从什么骨架起步，以及哪些 GitHub 项目只借模式不整仓照搬。

12. [ANDROID_SINGLE_CHAT_REQUEST_PIPELINE.md](./ANDROID_SINGLE_CHAT_REQUEST_PIPELINE.md)
   固化 Android 单聊从历史、附件、system 注入到最终 request body 的编译链。

13. [ANDROID_STREAM_STATE_MACHINE.md](./ANDROID_STREAM_STATE_MACHINE.md)
   固化流式消息的事件模型、状态机、落盘时机和中断收尾规则。

14. [ANDROID_PHASE_SCOPE.md](./ANDROID_PHASE_SCOPE.md)
   定义 Android 端各阶段应该做什么、明确不做什么、如何排序。

15. [TASK_PLAN.md](./TASK_PLAN.md)
   给出真正可执行的任务拆分、顺序、交付物和阶段门槛。

## 文档职责

- `REBUILD_PLAN.md`
  项目基线和方向。

- `VCPCHAT_MIGRATION_MAP.md`
  现实校准文档，防止把桌面端误判成“纯 UI 壳”。

- `APPDATA_SCHEMA.md`
  本地持久化规格。

- `NETWORK_AND_STREAM_CONTRACT.md`
  联网兼容规格。

- `DESKTOP_TO_ANDROID_FEATURE_MAP.md`
  桌面能力去留与迁移策略表。

- `DECISION_REGISTER.md`
  项目硬决策清单。

- `ANDROID_DOMAIN_MODEL.md`
  Android 端的数据与状态模型草案。

- `ANDROID_DATA_LAYER_DESIGN.md`
  Android 端的数据层分工与持久化真相来源。

- `APPDATA_IMPORT_EXPORT_STRATEGY.md`
  AppData 导入导出与 passthrough 策略。

- `ANDROID_INFORMATION_ARCHITECTURE.md`
  Android 端的信息架构、页面流和状态归属。

- `ANDROID_SKELETON_SELECTION.md`
  Android 工程骨架选型与开源参考项目取舍。

- `ANDROID_SINGLE_CHAT_REQUEST_PIPELINE.md`
  Android 单聊请求编译规格。

- `ANDROID_STREAM_STATE_MACHINE.md`
  Android 流式消息状态机与落盘规格。

- `ANDROID_PHASE_SCOPE.md`
  开发范围规格。

- `TASK_PLAN.md`
  执行计划规格。

## 当前结论

`VCPNative` 在当前阶段的任务是：

- 做 `VCPChat` 的 Android 客户端
- 保持 `VCPToolBox` 不动
- 文档和兼容契约已冻结到可开工程度
- Android 本地持久化默认放在应用私有目录
- Android 工程骨架固定为原生单 `:app` Compose 轻骨架
- 接下来进入客户端实现
