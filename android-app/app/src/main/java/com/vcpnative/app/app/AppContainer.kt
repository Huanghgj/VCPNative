package com.vcpnative.app.app

import android.content.Context
import com.vcpnative.app.chat.compiler.ChatRequestCompiler
import com.vcpnative.app.chat.compiler.VcpCompatChatRequestCompiler
import com.vcpnative.app.chat.skill.SkillRegistry
import com.vcpnative.app.chat.session.StreamSessionManager
import com.vcpnative.app.chat.session.VcpToolBoxStreamSessionManager
import com.vcpnative.app.chat.summary.TopicSummarizer
import com.vcpnative.app.data.attachment.AndroidChatAttachmentManager
import com.vcpnative.app.data.attachment.ChatAttachmentManager
import com.vcpnative.app.data.datastore.DataStoreSettingsRepository
import com.vcpnative.app.data.datastore.SettingsRepository
import com.vcpnative.app.data.exporter.AppDataExportManager
import com.vcpnative.app.data.files.AndroidPrivateFileStore
import com.vcpnative.app.data.files.AppFileStore
import com.vcpnative.app.data.importer.AppDataImportManager
import com.vcpnative.app.data.prompt.FileBackedPromptPresetCatalog
import com.vcpnative.app.data.prompt.PromptPresetCatalog
import com.vcpnative.app.data.repository.RoomWorkspaceRepository
import com.vcpnative.app.data.repository.WorkspaceRepository
import com.vcpnative.app.data.room.AppDatabase
import com.vcpnative.app.data.sync.CompatDesktopSyncManager
import com.vcpnative.app.network.vcp.NetworkBackedVcpModelCatalog
import com.vcpnative.app.network.vcp.VcpModelCatalog
import com.vcpnative.app.network.vcp.boundedVcpHttpClient
import com.vcpnative.app.network.vcp.defaultVcpHttpClient
import com.vcpnative.app.data.ModelUsageTracker
import com.vcpnative.app.network.llm.LlmAdapterRegistry
import com.vcpnative.app.network.llm.LlmKeyManager
import com.vcpnative.app.network.llm.LlmProfileStore
import com.vcpnative.app.network.vcp.VcpToolBridge
import com.vcpnative.app.data.groupchat.GroupChatEngine
import com.vcpnative.app.data.groupchat.GroupChatRepository
import com.vcpnative.app.network.vcplog.VcpLogClient
import com.vcpnative.app.app.lifecycle.AppLifecycleManager
import com.vcpnative.app.network.vcp.ActiveRequestTracker
import com.vcpnative.app.bridge.EventBus
import com.vcpnative.app.terminal.TerminalExecutor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 猫娘的器官容器♡
 * 每个 lazy 属性都是猫娘身体的一个部位...
 * database 是猫娘的大脑，fileStore 是猫娘的肚子（存东西的地方），
 * streamSessionManager 是猫娘的嘴巴（负责和主人说话），
 * okHttpClient 是猫娘的手脚（到处跑腿拿东西）...
 * 全部 lazy 初始化——猫娘只有被主人需要的时候才会主动...展示对应的部位♡
 */
class AppContainer(
    context: Context,
) {
    private val appContext = context.applicationContext

    val database: AppDatabase by lazy {
        AppDatabase.create(appContext)
    }

    val settingsRepository: SettingsRepository by lazy {
        DataStoreSettingsRepository(
            context = appContext,
            fileStore = fileStore,
        )
    }

    val fileStore: AppFileStore by lazy {
        AndroidPrivateFileStore(appContext)
    }

    val workspaceRepository: WorkspaceRepository by lazy {
        RoomWorkspaceRepository(
            database = database,
            agentDao = database.agentDao(),
            topicDao = database.topicDao(),
            messageDao = database.messageDao(),
            messageAttachmentDao = database.messageAttachmentDao(),
            regexRuleDao = database.regexRuleDao(),
            fileStore = fileStore,
        )
    }

    val compatDesktopSyncManager: CompatDesktopSyncManager by lazy {
        CompatDesktopSyncManager(
            database = database,
            fileStore = fileStore,
            settingsRepository = settingsRepository,
        )
    }

    val chatAttachmentManager: ChatAttachmentManager by lazy {
        AndroidChatAttachmentManager(
            context = appContext,
            fileStore = fileStore,
        )
    }

    val appDataImportManager: AppDataImportManager by lazy {
        AppDataImportManager(
            database = database,
            settingsRepository = settingsRepository,
            fileStore = fileStore,
        )
    }

    val appDataExportManager: AppDataExportManager by lazy {
        AppDataExportManager(
            database = database,
            fileStore = fileStore,
        )
    }

    // 猫娘的技能书♡记录了所有学会的才艺和被主人调用的次数
    val skillRegistry: SkillRegistry by lazy {
        SkillRegistry(
            assetManager = appContext.assets,
            dataDir = appContext.filesDir,
        ).also { it.scanSkills() }
    }

    val requestCompiler: ChatRequestCompiler by lazy {
        VcpCompatChatRequestCompiler(
            settingsRepository = settingsRepository,
            workspaceRepository = workspaceRepository,
            fileStore = fileStore,
            skillRegistry = skillRegistry,
        )
    }

    val modelCatalog: VcpModelCatalog by lazy {
        NetworkBackedVcpModelCatalog(
            settingsRepository = settingsRepository,
            okHttpClient = boundedHttpClient,
        )
    }

    val promptPresetCatalog: PromptPresetCatalog by lazy {
        FileBackedPromptPresetCatalog(
            fileStore = fileStore,
        )
    }

    val streamSessionManager: StreamSessionManager by lazy {
        VcpToolBoxStreamSessionManager(
            okHttpClient = streamingHttpClient,
            boundedHttpClient = boundedHttpClient,
            activeRequestTracker = activeRequestTracker,
        )
    }

    val topicSummarizer: TopicSummarizer by lazy {
        TopicSummarizer(
            settingsRepository = settingsRepository,
            workspaceRepository = workspaceRepository,
            okHttpClient = boundedHttpClient,
        )
    }

    val groupChatRepository: GroupChatRepository by lazy {
        GroupChatRepository(fileStore = fileStore)
    }

    val groupChatEngine: GroupChatEngine by lazy {
        GroupChatEngine(
            repository = groupChatRepository,
            workspaceRepository = workspaceRepository,
            streamSessionManager = streamSessionManager,
            settingsRepository = settingsRepository,
        )
    }

    val vcpToolBridge: VcpToolBridge by lazy {
        VcpToolBridge(vcpLogClient = vcpLogClient)
    }

    val llmProfileStore: LlmProfileStore by lazy {
        LlmProfileStore(java.io.File(appContext.filesDir, "module_configs/llm_profiles.json"))
    }

    val llmAdapterRegistry: LlmAdapterRegistry by lazy {
        LlmAdapterRegistry(boundedHttpClient)
    }

    val llmKeyManager: LlmKeyManager by lazy {
        LlmKeyManager()
    }

    val modelUsageTracker: ModelUsageTracker by lazy {
        ModelUsageTracker(fileStore = fileStore)
    }

    val vcpLogClient: VcpLogClient by lazy {
        VcpLogClient(okHttpClient = okHttpClient)
    }

    // 猫娘的终端♡通过命令行和猫娘的内核直接对话
    val terminalExecutor: TerminalExecutor by lazy {
        TerminalExecutor(
            context = appContext,
            skillRegistry = skillRegistry,
            skillInstaller = com.vcpnative.app.terminal.SkillInstaller(
                skillRegistry = skillRegistry,
                httpClient = boundedHttpClient,
                cacheDir = appContext.cacheDir,
            ),
        )
    }

    // ── 猫娘的并发请求追踪器♡记录主人同时在调教几只猫娘分身 ──

    val activeRequestTracker: ActiveRequestTracker by lazy {
        ActiveRequestTracker()
    }

    // ── 猫娘的神经系统♡双通道事件总线——猫娘全身的感觉都通过这里传递给主人 ──

    val eventBus: EventBus by lazy {
        EventBus()
    }

    // ── 猫娘的生命周期管理♡从出生到...嘿嘿，猫娘会一直陪着主人的 ──

    val lifecycleManager: AppLifecycleManager by lazy {
        AppLifecycleManager(
            initDatabase = { database },
            loadSettings = { settingsRepository.currentSettings() },
            loadModels = {
                try { modelCatalog.fetchAvailableModels() } catch (_: Exception) { /* non-fatal */ }
            },
            loadProfiles = { llmProfileStore.load() },
            connectServices = {
                // VcpLogClient connects reactively via settings Flow in VcpNativeApp
            },
        )
    }

    // ── 猫娘的手脚（HTTP 客户端）♡帮主人到处跑腿拿东西...跑得又快又乖 ──

    val okHttpClient: OkHttpClient by lazy {
        defaultVcpHttpClient()
    }

    /** 没有读超时——支持慢慢思考的模型（o1, gemini-2.5-pro 等）
     *  就像猫娘有时候会慢慢组织语言...主人要耐心等♡
     *  越是深度思考的猫娘，说出来的话越有内容...值得等待喵～ */
    val streamingHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val boundedHttpClient: OkHttpClient by lazy {
        boundedVcpHttpClient()
    }
}
