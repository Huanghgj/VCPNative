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

    // SECURITY: IDs must be unpredictable to prevent spoofed resolve/reject calls from injected scripts
    function generateId() {
        return '__vcp_' + (nextId++) + '_' + Math.random().toString(36).substr(2, 6) + '_' + Date.now().toString(36);
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

    // ---- Stream rendering optimizer ----
    // Exact port of VCPMobile streamManager.ts:
    // Character-level semantic queue + continuous RAF loop + adaptive step sizing.
    // Ensures smooth 60fps typing animation regardless of backend chunk rate.

    var _streamBuffers = {};
    // messageId -> {
    //   fullText: string,           // accumulated raw text
    //   displayedText: string,      // text shown so far
    //   semanticQueue: char[],      // character queue (NOT chunk queue)
    //   isFinishing: boolean,       // backend sent [DONE]
    //   onCompleteCallback: fn,     // called when queue drained after finish
    //   loopRunning: boolean        // prevent duplicate RAF loops
    // }

    function _emitStreamData(messageId, chunk) {
        var evt = { messageId: messageId, type: 'data', chunk: chunk };
        var cbs = listeners['vcp-stream-event'];
        if (!cbs) return;
        for (var j = 0; j < cbs.length; j++) {
            try { cbs[j](evt); } catch (e) { console.error('[StreamBuf] listener error', e); }
        }
    }

    function _startStreamLoop(messageId) {
        var buf = _streamBuffers[messageId];
        if (!buf || buf.loopRunning) return;
        buf.loopRunning = true;

        function loop() {
            var b = _streamBuffers[messageId];
            if (!b) return;

            if (b.semanticQueue.length > 0) {
                // Adaptive step: more chars per frame when backlog is large
                // Exact VCPMobile formula: Math.max(1, Math.ceil(backlog / 8))
                var backlog = b.semanticQueue.length;
                var step = Math.max(1, Math.ceil(backlog / 8));
                var added = '';
                for (var i = 0; i < step; i++) {
                    var ch = b.semanticQueue.shift();
                    if (ch) added += ch;
                    else break;
                }
                if (added) {
                    b.displayedText += added;
                    _emitStreamData(messageId, added);
                }
            }

            // Termination: queue empty AND backend finished
            if (b.isFinishing && b.semanticQueue.length === 0) {
                b.loopRunning = false;
                if (b.onCompleteCallback) {
                    try { b.onCompleteCallback(); } catch (e) {}
                }
                delete _streamBuffers[messageId];
            } else {
                requestAnimationFrame(loop);
            }
        }
        requestAnimationFrame(loop);
    }

    function _appendStreamChunk(messageId, chunk) {
        if (!_streamBuffers[messageId]) {
            // First chunk: initialize buffer + start RAF loop
            var chars = [];
            for (var i = 0; i < chunk.length; i++) chars.push(chunk[i]);
            _streamBuffers[messageId] = {
                fullText: chunk,
                displayedText: '',
                semanticQueue: chars,
                isFinishing: false,
                onCompleteCallback: null,
                loopRunning: false
            };
            _startStreamLoop(messageId);
        } else {
            var buf = _streamBuffers[messageId];
            buf.fullText += chunk;
            // Reset finishing flag if new chunk arrives after [DONE] (edge case)
            if (buf.isFinishing) buf.isFinishing = false;
            // Push chars one by one (NEVER use push(...chunk) to avoid stack overflow)
            for (var j = 0; j < chunk.length; j++) {
                buf.semanticQueue.push(chunk[j]);
            }
            // Restart loop if it stopped
            if (!buf.loopRunning) _startStreamLoop(messageId);
        }
    }

    function _finalizeStream(messageId, onComplete) {
        var buf = _streamBuffers[messageId];
        if (buf) {
            buf.isFinishing = true;
            buf.onCompleteCallback = onComplete || null;
            // Loop will drain queue then call onComplete and cleanup
        } else {
            // Buffer already gone (e.g. empty stream)
            if (onComplete) try { onComplete(); } catch (e) {}
        }
    }

    // Override emit to intercept vcp-stream-event for character-level buffering
    var _originalEmit = window.__vcpBridge.emit;
    window.__vcpBridge.emit = function (channel, dataJson) {
        if (channel !== 'vcp-stream-event') {
            return _originalEmit.call(window.__vcpBridge, channel, dataJson);
        }
        var data;
        try { data = JSON.parse(dataJson); } catch (e) { data = dataJson; }
        if (!data || !data.messageId) {
            return _originalEmit.call(window.__vcpBridge, channel, dataJson);
        }

        if (data.type === 'data' && data.chunk) {
            // Feed into character-level stream buffer
            _appendStreamChunk(data.messageId, data.chunk);
        } else if (data.type === 'end') {
            // Signal stream completion; RAF loop drains remaining chars first
            var fullText = (_streamBuffers[data.messageId] || {}).fullText || '';
            _finalizeStream(data.messageId, function () {
                // After all chars displayed, dispatch end event to listeners
                // Include fullText so listeners can do post-processing (regex, save, summary)
                data._fullText = fullText;
                var cbs = listeners[channel];
                if (cbs) {
                    for (var i = 0; i < cbs.length; i++) {
                        try { cbs[i](data); } catch (e) {
                            console.error('[VcpBridge] listener error on', channel, e);
                        }
                    }
                }
                // Post-completion chain (ported from VCPMobile chatManager.ts):
                // 1. Emit vcp-stream-complete for any additional processing
                var completeCbs = listeners['vcp-stream-complete'];
                if (completeCbs) {
                    var completeEvt = { messageId: data.messageId, fullText: fullText };
                    for (var k = 0; k < completeCbs.length; k++) {
                        try { completeCbs[k](completeEvt); } catch (e) {}
                    }
                }
            });
        } else {
            // 'error' or other: flush remaining synchronously then dispatch
            var buf = _streamBuffers[data.messageId];
            if (buf) {
                if (buf.loopRunning) { buf.loopRunning = false; }
                if (buf.semanticQueue.length > 0) {
                    _emitStreamData(data.messageId, buf.semanticQueue.join(''));
                }
                delete _streamBuffers[data.messageId];
            }
            var cbs = listeners[channel];
            if (cbs) {
                for (var i = 0; i < cbs.length; i++) {
                    try { cbs[i](data); } catch (e) {
                        console.error('[VcpBridge] listener error on', channel, e);
                    }
                }
            }
        }
    };

    // Expose for external use (messageRenderer.js can check stream state)
    window.__vcpStreamManager = {
        isStreaming: function (messageId) {
            return !!_streamBuffers[messageId];
        },
        getDisplayedText: function (messageId) {
            var buf = _streamBuffers[messageId];
            return buf ? buf.displayedText : null;
        },
        getFullText: function (messageId) {
            var buf = _streamBuffers[messageId];
            return buf ? buf.fullText : null;
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
    defOn('onVCPStreamComplete', 'vcp-stream-complete');
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

    // Mobile-friendly alert: show inline banner if native alert fails
    var _origAlert = window.alert;
    window.alert = function(msg) {
        console.warn('[VcpBridge] alert:', msg);
        try {
            _origAlert.call(window, msg);
        } catch(e) {
            // Fallback: inject visible banner into page
            var banner = document.createElement('div');
            banner.style.cssText = 'position:fixed;top:0;left:0;right:0;z-index:99999;background:#ff3b30;color:#fff;padding:16px 20px;font-size:15px;text-align:center;font-family:system-ui;';
            banner.textContent = msg;
            banner.onclick = function(){ banner.remove(); };
            (document.body || document.documentElement).appendChild(banner);
        }
    };
})();
