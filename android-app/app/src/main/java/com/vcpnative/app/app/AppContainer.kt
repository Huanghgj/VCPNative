package com.vcpnative.app.app

import android.content.Context
import com.vcpnative.app.chat.compiler.ChatRequestCompiler
import com.vcpnative.app.chat.compiler.VcpCompatChatRequestCompiler
import com.vcpnative.app.chat.session.StreamSessionManager
import com.vcpnative.app.chat.session.VcpToolBoxStreamSessionManager
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
import com.vcpnative.app.network.vcp.NetworkBackedVcpModelCatalog
import com.vcpnative.app.network.vcp.VcpModelCatalog
import com.vcpnative.app.network.vcp.defaultVcpHttpClient
import okhttp3.OkHttpClient

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
            okHttpClient = okHttpClient,
        )
    }

    val promptPresetCatalog: PromptPresetCatalog by lazy {
        FileBackedPromptPresetCatalog(
            fileStore = fileStore,
        )
    }

    val streamSessionManager: StreamSessionManager by lazy {
        VcpToolBoxStreamSessionManager(
            okHttpClient = okHttpClient,
        )
    }

    val okHttpClient: OkHttpClient by lazy {
        defaultVcpHttpClient()
    }
}
