package com.vcpnative.app.app

import android.content.Context
import com.vcpnative.app.chat.compiler.ChatRequestCompiler
import com.vcpnative.app.chat.compiler.VcpCompatChatRequestCompiler
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
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

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

    val requestCompiler: ChatRequestCompiler by lazy {
        VcpCompatChatRequestCompiler(
            settingsRepository = settingsRepository,
            workspaceRepository = workspaceRepository,
            fileStore = fileStore,
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

    // ── Phase 2: Active request tracker (ported from VCPMobile ActiveRequests DashMap) ──

    val activeRequestTracker: ActiveRequestTracker by lazy {
        ActiveRequestTracker()
    }

    // ── Phase 2: Dual-channel event bus (ported from VCPMobile Tauri Event System) ──

    val eventBus: EventBus by lazy {
        EventBus()
    }

    // ── Phase 1: Lifecycle manager (ported from VCPMobile lifecycle_manager.rs) ──

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

    // ── HTTP clients ──

    val okHttpClient: OkHttpClient by lazy {
        defaultVcpHttpClient()
    }

    /** No read timeout — supports long-thinking models (o1, gemini-2.5-pro, etc.).
     *  Ported from VCPMobile which uses no hard timeout for streaming. */
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
