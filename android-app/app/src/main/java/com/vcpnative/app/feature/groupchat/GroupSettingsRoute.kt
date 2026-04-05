package com.vcpnative.app.feature.groupchat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vcpnative.app.app.AppContainer
import com.vcpnative.app.data.groupchat.AgentGroup
import com.vcpnative.app.data.room.AgentEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupSettingsRoute(
    appContainer: AppContainer,
    groupId: String,
    onNavigateBack: () -> Unit,
) {
    var group by remember { mutableStateOf<AgentGroup?>(null) }
    val allAgents by appContainer.workspaceRepository.observeAgents().collectAsState(initial = emptyList())
    var name by remember { mutableStateOf("") }
    var selectedMembers by remember { mutableStateOf(setOf<String>()) }
    var mode by remember { mutableStateOf("sequential") }
    var groupPrompt by remember { mutableStateOf("") }
    var invitePrompt by remember { mutableStateOf("") }
    var useUnifiedModel by remember { mutableStateOf(false) }
    var unifiedModel by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(groupId) {
        val g = appContainer.groupChatRepository.getGroupConfig(groupId)
        if (g != null) {
            group = g
            name = g.name
            selectedMembers = g.members.toSet()
            mode = g.mode
            groupPrompt = g.groupPrompt
            invitePrompt = g.invitePrompt
            useUnifiedModel = g.useUnifiedModel
            unifiedModel = g.unifiedModel
        }
    }

    Scaffold(
        modifier = Modifier.statusBarsPadding(),
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                }
                Text(
                    text = "群聊设置",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 群名
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("群聊名称") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
            )

            // 模式选择
            var modeExpanded by remember { mutableStateOf(false) }
            val modes = listOf("sequential" to "顺序发言", "naturerandom" to "自然随机", "invite_only" to "邀请制")
            ExposedDropdownMenuBox(expanded = modeExpanded, onExpandedChange = { modeExpanded = it }) {
                OutlinedTextField(
                    value = modes.find { it.first == mode }?.second ?: mode,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("发言模式") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(modeExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    shape = RoundedCornerShape(12.dp),
                )
                ExposedDropdownMenu(expanded = modeExpanded, onDismissRequest = { modeExpanded = false }) {
                    modes.forEach { (value, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = { mode = value; modeExpanded = false },
                        )
                    }
                }
            }

            // 成员选择
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "成员 (${selectedMembers.size}/${allAgents.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    allAgents.forEach { agent ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedMembers = if (agent.id in selectedMembers) {
                                        selectedMembers - agent.id
                                    } else {
                                        selectedMembers + agent.id
                                    }
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = agent.id in selectedMembers,
                                onCheckedChange = { checked ->
                                    selectedMembers = if (checked) selectedMembers + agent.id else selectedMembers - agent.id
                                },
                            )
                            Text(
                                text = "${agent.name} (${agent.model})",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    if (allAgents.isEmpty()) {
                        Text("没有可用的 Agent，请先创建", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // 群聊 Prompt
            OutlinedTextField(
                value = groupPrompt,
                onValueChange = { groupPrompt = it },
                label = { Text("群聊 System Prompt（附加到每个成员）") },
                modifier = Modifier.fillMaxWidth().height(120.dp),
                shape = RoundedCornerShape(12.dp),
            )

            // 邀请 Prompt
            OutlinedTextField(
                value = invitePrompt,
                onValueChange = { invitePrompt = it },
                label = { Text("邀请发言 Prompt") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )

            // 统一模型
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { useUnifiedModel = !useUnifiedModel },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = useUnifiedModel, onCheckedChange = { useUnifiedModel = it })
                Text("使用统一模型（覆盖所有成员的模型设置）")
            }
            if (useUnifiedModel) {
                OutlinedTextField(
                    value = unifiedModel,
                    onValueChange = { unifiedModel = it },
                    label = { Text("统一模型 ID") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                )
            }

            // 保存按钮
            Button(
                onClick = {
                    scope.launch {
                        val current = group ?: return@launch
                        appContainer.groupChatRepository.saveGroupConfig(
                            current.copy(
                                name = name.trim().ifBlank { current.name },
                                members = selectedMembers.toList(),
                                mode = mode,
                                groupPrompt = groupPrompt,
                                invitePrompt = invitePrompt,
                                useUnifiedModel = useUnifiedModel,
                                unifiedModel = unifiedModel.trim(),
                                updatedAt = System.currentTimeMillis(),
                            )
                        )
                        onNavigateBack()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("保存", fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
