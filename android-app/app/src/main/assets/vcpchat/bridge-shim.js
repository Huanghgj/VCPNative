/**
 * VCPChat Electron → Android Bridge Shim
 *
 * Replaces window.electronAPI / window.electronPath / window.electron
 * with calls to Android's JavascriptInterface (VcpBridge).
 *
 * Communication protocol:
 *   JS → Kotlin:  VcpBridge.postMessage(JSON.stringify({ id, channel, args }))
 *   Kotlin → JS:  window.__vcpBridge.resolve(id, resultJson)
 *                  window.__vcpBridge.emit(channel, dataJson)
 */
(function () {
    'use strict';

    // Pending invoke promises: id → { resolve, reject }
    var pending = {};
    var nextId = 1;

    // Event listeners: channel → [callback, ...]
    var listeners = {};

    // ---- Internal helpers ----

    function generateId() {
        return '__vcp_' + (nextId++);
    }

    /**
     * Send a request-reply message to Kotlin side.
     * Returns a Promise that resolves when Kotlin calls resolve().
     */
    function invoke(channel, args) {
        return new Promise(function (resolve, reject) {
            var id = generateId();
            pending[id] = { resolve: resolve, reject: reject };
            try {
                VcpBridge.postMessage(JSON.stringify({
                    id: id,
                    type: 'invoke',
                    channel: channel,
                    args: args || []
                }));
            } catch (e) {
                delete pending[id];
                reject(e);
            }
        });
    }

    /**
     * Send a fire-and-forget message to Kotlin side.
     */
    function send(channel, args) {
        try {
            VcpBridge.postMessage(JSON.stringify({
                type: 'send',
                channel: channel,
                args: args || []
            }));
        } catch (e) {
            console.warn('[VcpBridge] send failed:', channel, e);
        }
    }

    /**
     * Register a listener for events pushed from Kotlin side.
     */
    function on(channel, callback) {
        if (!listeners[channel]) {
            listeners[channel] = [];
        }
        listeners[channel].push(callback);
        return function unsubscribe() {
            var arr = listeners[channel];
            if (arr) {
                var idx = arr.indexOf(callback);
                if (idx !== -1) arr.splice(idx, 1);
            }
        };
    }

    // ---- Bridge endpoint (called by Kotlin) ----

    window.__vcpBridge = {
        /**
         * Resolve a pending invoke call.
         * @param {string} id - The request ID
         * @param {string} resultJson - JSON-encoded result (or "null")
         */
        resolve: function (id, resultJson) {
            var entry = pending[id];
            if (entry) {
                delete pending[id];
                try {
                    entry.resolve(JSON.parse(resultJson));
                } catch (e) {
                    entry.resolve(resultJson);
                }
            }
        },

        /**
         * Reject a pending invoke call.
         */
        reject: function (id, errorMessage) {
            var entry = pending[id];
            if (entry) {
                delete pending[id];
                entry.reject(new Error(errorMessage || 'Bridge call failed'));
            }
        },

        /**
         * Emit an event from Kotlin to all registered JS listeners.
         * @param {string} channel
         * @param {string} dataJson - JSON-encoded event data
         */
        emit: function (channel, dataJson) {
            var cbs = listeners[channel];
            if (!cbs || cbs.length === 0) return;
            var data;
            try {
                data = JSON.parse(dataJson);
            } catch (e) {
                data = dataJson;
            }
            for (var i = 0; i < cbs.length; i++) {
                try {
                    cbs[i](data);
                } catch (e) {
                    console.error('[VcpBridge] listener error on', channel, e);
                }
            }
        }
    };

    // ---- electronPath shim ----

    window.electronPath = {
        dirname: function (p) {
            var idx = p.lastIndexOf('/');
            return idx > 0 ? p.substring(0, idx) : '.';
        },
        extname: function (p) {
            var idx = p.lastIndexOf('.');
            return idx > 0 ? p.substring(idx) : '';
        },
        basename: function (p) {
            var idx = p.lastIndexOf('/');
            return idx >= 0 ? p.substring(idx + 1) : p;
        }
    };

    // ---- electron (music legacy) shim ----

    window.electron = {
        send: function (channel, data) { send(channel, [data]); },
        invoke: function (channel, data) { return invoke(channel, [data]); },
        on: function (channel, func) { on(channel, func); }
    };

    // ---- electronAPI shim ----
    // Build dynamically from channel definitions.
    // invoke() channels return Promises; send() channels are fire-and-forget;
    // on*() channels register listeners.

    var api = {};

    // Helper to define an invoke method
    function defInvoke(name, channel, argCount) {
        api[name] = function () {
            var args = Array.prototype.slice.call(arguments, 0, argCount || arguments.length);
            return invoke(channel, args);
        };
    }

    // Helper to define a send method
    function defSend(name, channel, argCount) {
        api[name] = function () {
            var args = Array.prototype.slice.call(arguments, 0, argCount || arguments.length);
            send(channel, args);
        };
    }

    // Helper to define an event listener registration
    function defOn(name, channel) {
        api[name] = function (callback) {
            return on(channel, callback);
        };
    }

    // ---- Settings ----
    defInvoke('loadSettings', 'load-settings');
    defInvoke('saveSettings', 'save-settings', 1);
    defInvoke('saveUserAvatar', 'save-user-avatar', 1);
    defInvoke('saveAvatarColor', 'save-avatar-color', 1);

    // ---- Agents ----
    defInvoke('getAgents', 'get-agents');
    defInvoke('getAgentConfig', 'get-agent-config', 1);
    defInvoke('saveAgentConfig', 'save-agent-config', 2);
    defInvoke('selectAvatar', 'select-avatar');
    defInvoke('saveAvatar', 'save-avatar', 2);
    defInvoke('createAgent', 'create-agent', 2);
    defInvoke('deleteAgent', 'delete-agent', 1);
    defInvoke('getCachedModels', 'get-cached-models');
    defSend('refreshModels', 'refresh-models');
    defInvoke('getHotModels', 'get-hot-models');
    defInvoke('getFavoriteModels', 'get-favorite-models');
    defInvoke('toggleFavoriteModel', 'toggle-favorite-model', 1);
    defOn('onModelsUpdated', 'models-updated');
    defInvoke('getAllItems', 'get-all-items');
    defInvoke('importRegexRules', 'import-regex-rules', 1);
    defInvoke('updateAgentConfig', 'update-agent-config', 2);
    defInvoke('getGlobalWarehouse', 'get-global-warehouse');
    defInvoke('saveGlobalWarehouse', 'save-global-warehouse', 1);

    // ---- Prompt ----
    defInvoke('loadPresetPrompts', 'load-preset-prompts', 1);
    defInvoke('loadPresetContent', 'load-preset-content', 1);
    defInvoke('selectDirectory', 'select-directory');
    defInvoke('getActiveSystemPrompt', 'get-active-system-prompt', 1);
    defInvoke('programmaticSetPromptMode', 'programmatic-set-prompt-mode', 2);
    defOn('onReloadAgentSettings', 'reload-agent-settings');

    // ---- Topics ----
    defInvoke('getAgentTopics', 'get-agent-topics', 1);
    defInvoke('createNewTopicForAgent', 'create-new-topic-for-agent', 4);
    defInvoke('saveAgentTopicTitle', 'save-agent-topic-title', 3);
    defInvoke('deleteTopic', 'delete-topic', 2);
    defInvoke('getUnreadTopicCounts', 'get-unread-topic-counts');
    defInvoke('toggleTopicLock', 'toggle-topic-lock', 2);
    defInvoke('setTopicUnread', 'set-topic-unread', 3);
    defOn('onCreateUnlockedTopic', 'create-unlocked-topic');

    // ---- Chat History ----
    defInvoke('getChatHistory', 'get-chat-history', 2);
    defInvoke('saveChatHistory', 'save-chat-history', 3);
    defInvoke('getOriginalMessageContent', 'get-original-message-content', 4);

    // ---- Files ----
    defInvoke('handleFilePaste', 'handle-file-paste', 3);
    defInvoke('selectFilesToSend', 'select-files-to-send', 2);
    defInvoke('getFileAsBase64', 'get-file-as-base64', 1);
    defInvoke('getTextContent', 'get-text-content', 2);
    defInvoke('handleTextPasteAsFile', 'handle-text-paste-as-file', 3);
    defInvoke('handleFileDrop', 'handle-file-drop', 3);
    defOn('onAddFileToInput', 'add-file-to-input');

    // ---- Notes ----
    defInvoke('readNotesTree', 'read-notes-tree');
    defInvoke('writeTxtNote', 'write-txt-note', 1);
    defInvoke('deleteItem', 'delete-item', 1);
    defInvoke('createNoteFolder', 'create-note-folder', 1);
    defInvoke('renameItem', 'rename-item', 1);
    api['notes:move-items'] = function (data) { return invoke('notes:move-items', [data]); };
    defInvoke('savePastedImageToFile', 'save-pasted-image-to-file', 2);
    defInvoke('getNotesRootDir', 'get-notes-root-dir');
    defInvoke('copyNoteContent', 'copy-note-content', 1);
    defSend('scanNetworkNotes', 'scan-network-notes');
    defOn('onNetworkNotesScanned', 'network-notes-scanned');
    defInvoke('getCachedNetworkNotes', 'get-cached-network-notes');
    defInvoke('searchNotes', 'search-notes', 1);
    defInvoke('openNotesWindow', 'open-notes-window', 1);
    defInvoke('openNotesWithContent', 'open-notes-with-content', 1);
    defOn('onSharedNoteData', 'shared-note-data');
    defSend('notesRendererReady', 'notes-renderer-ready');
    defSend('sendNotesWindowReady', 'notes-window-ready');

    // ---- Orders ----
    defInvoke('saveAgentOrder', 'save-agent-order', 1);
    defInvoke('saveTopicOrder', 'save-topic-order', 2);
    defInvoke('saveCombinedItemOrder', 'save-combined-item-order', 1);

    // ---- VCP Communication ----
    defInvoke('sendToVCP', 'send-to-vcp', 7);
    defOn('onVCPStreamEvent', 'vcp-stream-event');
    defOn('onVCPStreamChunk', 'vcp-stream-chunk');
    defInvoke('interruptVcpRequest', 'interrupt-vcp-request', 1);

    // ---- Group Chat ----
    defInvoke('createAgentGroup', 'create-agent-group', 2);
    defInvoke('getAgentGroups', 'get-agent-groups');
    defInvoke('getAgentGroupConfig', 'get-agent-group-config', 1);
    defInvoke('saveAgentGroupConfig', 'save-agent-group-config', 2);
    defInvoke('deleteAgentGroup', 'delete-agent-group', 1);
    defInvoke('saveAgentGroupAvatar', 'save-agent-group-avatar', 2);
    defInvoke('getGroupTopics', 'get-group-topics', 2);
    defInvoke('createNewTopicForGroup', 'create-new-topic-for-group', 2);
    defInvoke('deleteGroupTopic', 'delete-group-topic', 2);
    defInvoke('saveGroupTopicTitle', 'save-group-topic-title', 3);
    defInvoke('getGroupChatHistory', 'get-group-chat-history', 2);
    defInvoke('saveGroupChatHistory', 'save-group-chat-history', 3);
    defInvoke('sendGroupChatMessage', 'send-group-chat-message', 3);
    defOn('onVCPGroupTopicUpdated', 'vcp-group-topic-updated');
    defOn('onHistoryFileUpdated', 'history-file-updated');
    defInvoke('saveGroupTopicOrder', 'save-group-topic-order', 2);
    defInvoke('searchTopicsByContent', 'search-topics-by-content', 3);
    defInvoke('inviteAgentToSpeak', 'inviteAgentToSpeak', 3);
    defInvoke('redoGroupChatMessage', 'redo-group-chat-message', 4);
    defInvoke('interruptGroupRequest', 'interrupt-group-request', 1);

    // ---- Export ----
    defInvoke('exportTopicAsMarkdown', 'export-topic-as-markdown', 1);

    // ---- VCPLog ----
    defSend('connectVCPLog', 'connect-vcplog', 1);
    defSend('disconnectVCPLog', 'disconnect-vcplog');
    defOn('onVCPLogMessage', 'vcp-log-message');
    defOn('onVCPLogStatus', 'vcp-log-status');
    defSend('sendVCPLogMessage', 'send-vcplog-message', 1);

    // ---- Clipboard ----
    api.readImageFromClipboard = function () { return invoke('read-image-from-clipboard-main', []); };
    api.readTextFromClipboard = function () { return invoke('read-text-from-clipboard-main', []); };

    // ---- Window Controls (mapped to Android navigation) ----
    defSend('minimizeWindow', 'minimize-window');
    defSend('maximizeWindow', 'maximize-window');
    defSend('unmaximizeWindow', 'unmaximize-window');
    defSend('closeWindow', 'close-window');
    defSend('hideWindow', 'hide-window');
    defSend('sendToggleNotificationsSidebar', 'toggle-notifications-sidebar');
    defOn('onDoToggleNotificationsSidebar', 'do-toggle-notifications-sidebar');
    defOn('onWindowMaximized', 'window-maximized');
    defOn('onWindowUnmaximized', 'window-unmaximized');
    defSend('openDevTools', 'open-dev-tools');
    defInvoke('openAdminPanel', 'open-admin-panel');
    defSend('minimizeToTray', 'minimize-to-tray');
    defSend('closeApp', 'close-app');

    // ---- Theme ----
    defOn('onThemeUpdated', 'theme-updated');
    defInvoke('getCurrentTheme', 'get-current-theme');
    defSend('setTheme', 'set-theme', 1);
    defSend('setThemeMode', 'set-theme-mode', 1);
    defInvoke('getPlatform', 'get-platform');
    defSend('openThemesWindow', 'open-themes-window');
    defInvoke('getThemes', 'get-themes');
    defSend('applyTheme', 'apply-theme', 1);
    defInvoke('getWallpaperThumbnail', 'get-wallpaper-thumbnail', 1);

    // ---- Image / Text Viewer ----
    defSend('showImageContextMenu', 'show-image-context-menu', 1);
    defSend('openImageViewer', 'open-image-viewer', 1);
    api.openTextInNewWindow = function (text, title, theme) {
        return invoke('display-text-content-in-viewer', [text, title, theme]);
    };
    defSend('sendOpenExternalLink', 'open-external-link', 1);

    // ---- Translator ----
    defInvoke('openTranslatorWindow', 'open-translator-window', 1);

    // ---- Dice ----
    defInvoke('openDiceWindow', 'open-dice-window');
    defOn('onRollDice', 'roll-dice');
    defSend('sendDiceModuleReady', 'dice-module-ready');
    defSend('sendDiceRollComplete', 'dice-roll-complete', 1);

    // ---- Sovits TTS ----
    defInvoke('sovitsGetModels', 'sovits-get-models', 1);
    defSend('sovitsSpeak', 'sovits-speak', 1);
    defSend('sovitsStop', 'sovits-stop');
    defOn('onPlayTtsAudio', 'play-tts-audio');
    defOn('onStopTtsAudio', 'stop-tts-audio');

    // ---- Emoticons ----
    defInvoke('getEmoticonLibrary', 'get-emoticon-library');

    // ---- Voice Chat ----
    defSend('openVoiceChatWindow', 'open-voice-chat-window', 1);
    defOn('onVoiceChatData', 'voice-chat-data');
    defSend('startSpeechRecognition', 'start-speech-recognition');
    defSend('stopSpeechRecognition', 'stop-speech-recognition');
    defOn('onSpeechRecognitionResult', 'speech-recognition-result');

    // ---- Forum / Memo ----
    defSend('openForumWindow', 'open-forum-window');
    defSend('openMemoWindow', 'open-memo-window');
    defInvoke('loadForumConfig', 'load-forum-config');
    defInvoke('saveForumConfig', 'save-forum-config', 1);
    defInvoke('loadAgentsList', 'load-agents-list');
    defInvoke('loadUserAvatar', 'load-user-avatar');
    defInvoke('loadAgentAvatar', 'load-agent-avatar', 1);
    defInvoke('loadMemoConfig', 'load-memo-config');
    defInvoke('saveMemoConfig', 'save-memo-config', 1);

    // ---- Canvas ----
    defInvoke('openCanvasWindow', 'open-canvas-window');
    defSend('canvasReady', 'canvas-ready');
    defSend('createNewCanvas', 'create-new-canvas');
    defSend('loadCanvasFile', 'load-canvas-file', 1);
    defSend('saveCanvasFile', 'save-canvas-file', 1);
    defOn('onCanvasLoadData', 'canvas-load-data');
    defOn('onCanvasFileChanged', 'canvas-file-changed');
    defOn('onExternalFileChanged', 'external-file-changed');
    defOn('onCanvasContentUpdate', 'canvas-content-update');
    defOn('onLoadCanvasFileByPath', 'load-canvas-file-by-path');
    defOn('onCanvasWindowClosed', 'canvas-window-closed');
    defInvoke('renameCanvasFile', 'rename-canvas-file', 1);
    defSend('copyCanvasFile', 'copy-canvas-file', 1);
    defSend('deleteCanvasFile', 'delete-canvas-file', 1);
    defInvoke('getLatestCanvasContent', 'get-latest-canvas-content');
    defInvoke('watcherStart', 'watcher:start', 3);
    defInvoke('watcherStop', 'watcher:stop');

    // ---- Flowlock ----
    defOn('onFlowlockCommand', 'flowlock-command');
    defSend('sendFlowlockResponse', 'flowlock-response', 1);

    // ---- Desktop Push (canvas widgets) ----
    defSend('desktopPush', 'desktop-push', 1);
    defOn('onDesktopPush', 'desktop-push-to-canvas');
    defOn('onDesktopStatus', 'desktop-status');
    defInvoke('openDesktopWindow', 'open-desktop-window');

    // ---- Stream chunk listener removal ----
    api.removeVcpStreamChunkListener = function (callback) {
        var arr = listeners['vcp-stream-chunk'];
        if (arr) {
            var idx = arr.indexOf(callback);
            if (idx !== -1) arr.splice(idx, 1);
        }
    };

    // ---- Assistant (skip on mobile, stub out) ----
    api.toggleSelectionListener = function () {};
    api.getSelectionListenerStatus = function () { return Promise.resolve(false); };
    api.suspendAssistantListener = function () { return Promise.resolve(); };
    api.getAssistantRuntimeStatus = function () { return Promise.resolve(null); };
    api.getRustAssistantConfig = function () { return Promise.resolve({}); };
    api.saveRustAssistantConfig = function () { return Promise.resolve(); };
    api.assistantAction = function () {};
    api.closeAssistantBar = function () {};
    api.onAssistantBarData = function () { return function () {}; };
    api.getAssistantBarInitialData = function () { return Promise.resolve(null); };
    api.onAssistantData = function () { return function () {}; };

    // ---- RAG Overlay (skip on mobile, stub out) ----
    api.ragOverlayShow = function () {};
    api.ragOverlayHide = function () {};
    api.ragOverlaySetEnabled = function () {};
    api.ragOverlaySetOpacity = function () {};
    api.ragOverlaySetPassThrough = function () {};
    api.ragOverlayResize = function () {};
    api.ragOverlayGetBounds = function () { return Promise.resolve(null); };
    api.ragOverlayGetState = function () { return Promise.resolve(null); };
    api.sendRagOverlayApprovalAction = function () {};
    api.onRagOverlayPayload = function () { return function () {}; };
    api.onRagOverlayPassThroughChanged = function () { return function () {}; };
    api.onRagOverlayApprovalAction = function () { return function () {}; };

    // ---- Python execution (skip on mobile) ----
    api.executePythonCode = function () {
        return Promise.resolve({ error: 'Python execution is not available on mobile.' });
    };

    // ---- Music command ----
    defOn('onMusicCommand', 'music-command');

    // Expose
    window.electronAPI = api;

    console.log('[VcpBridge] Electron API shim loaded (' + Object.keys(api).length + ' methods)');
})();
