/**
 * VCPNative Terminal Module
 * xterm.js based terminal with IPC bridge to Kotlin and local Pyodide REPL.
 */
(function () {
    'use strict';

    // ---- Constants ----
    var PROMPT = '\x1b[1;35mvcp\x1b[0m:\x1b[1;36m~\x1b[0m$ ';
    var PROMPT_LEN = 6; // visible chars: "vcp:~$ "
    var PY_PROMPT = '\x1b[1;33m>>>\x1b[0m ';
    var PY_PROMPT_LEN = 4;
    var PY_CONT_PROMPT = '\x1b[1;33m...\x1b[0m ';
    var PY_CONT_PROMPT_LEN = 4;
    var PYODIDE_CDN = 'https://cdn.jsdelivr.net/pyodide/v0.25.1/full/';

    // ---- State ----
    var term;
    var fitAddon;
    var lineBuffer = '';
    var cursorPos = 0;
    var mode = 'shell'; // 'shell' | 'python'
    var pyodide = null;
    var pyodideLoading = false;
    var pyMultilineBuffer = [];
    var pyInContinuation = false;
    var commandHistory = [];
    var historyIndex = -1;
    var tempLine = '';
    var waitingForOutput = false;

    // ---- DOM refs ----
    var statusIndicator = document.getElementById('status-indicator');
    var statusText = document.getElementById('status-text');

    // ---- Terminal setup ----
    function initTerminal() {
        term = new Terminal({
            cursorBlink: true,
            cursorStyle: 'bar',
            fontSize: 14,
            fontFamily: "'Courier New', 'Menlo', 'DejaVu Sans Mono', monospace",
            theme: {
                background: '#1a1520',
                foreground: '#e0d0f0',
                cursor: '#e991cf',
                cursorAccent: '#1a1520',
                selectionBackground: '#e991cf33',
                black: '#1a1520',
                red: '#f87171',
                green: '#4ade80',
                yellow: '#facc15',
                blue: '#60a5fa',
                magenta: '#e991cf',
                cyan: '#67e8f9',
                white: '#e0d0f0',
                brightBlack: '#4a3a5a',
                brightRed: '#fca5a5',
                brightGreen: '#86efac',
                brightYellow: '#fde047',
                brightBlue: '#93c5fd',
                brightMagenta: '#f0abde',
                brightCyan: '#a5f3fc',
                brightWhite: '#ffffff'
            },
            allowTransparency: true,
            scrollback: 5000,
            convertEol: true
        });

        fitAddon = new FitAddon.FitAddon();
        term.loadAddon(fitAddon);
        term.open(document.getElementById('terminal'));
        fitAddon.fit();

        window.addEventListener('resize', function () {
            fitAddon.fit();
        });

        term.onData(handleInput);
        term.focus();

        printBanner();
        fetchEnvInfo();
    }

    // ---- Banner ----
    function printBanner() {
        term.writeln('');
        term.writeln('  \x1b[1;35m\ud83d\udc31 VCPNative Terminal v0.1\x1b[0m');
        term.writeln('  \x1b[36mPython 3.11 (Pyodide)\x1b[0m | \x1b[36mShell (sandbox)\x1b[0m');
        term.writeln('  Type \x1b[1;33mhelp\x1b[0m for commands, \x1b[1;33mpython\x1b[0m for REPL');
        term.writeln('');
        showPrompt();
    }

    // ---- Environment info ----
    function fetchEnvInfo() {
        if (typeof window.electronAPI !== 'undefined' && window.electronAPI.invoke) {
            window.electronAPI.invoke('terminal:get-env').then(function (env) {
                if (env) {
                    setStatus('online', env.hostname || env.device || 'Connected');
                } else {
                    setStatus('online', 'Connected');
                }
            }).catch(function () {
                setStatus('online', 'Local');
            });
        } else {
            setStatus('offline', 'No bridge');
        }
    }

    // ---- Status bar ----
    function setStatus(state, text) {
        statusIndicator.className = 'indicator ' + state;
        statusText.textContent = text;
    }

    // ---- Prompt ----
    function showPrompt() {
        if (mode === 'python') {
            if (pyInContinuation) {
                term.write(PY_CONT_PROMPT);
            } else {
                term.write(PY_PROMPT);
            }
        } else {
            term.write(PROMPT);
        }
        lineBuffer = '';
        cursorPos = 0;
        waitingForOutput = false;
    }

    function currentPromptLen() {
        if (mode === 'python') {
            return pyInContinuation ? PY_CONT_PROMPT_LEN : PY_PROMPT_LEN;
        }
        return PROMPT_LEN;
    }

    // ---- Redraw current line ----
    function redrawLine() {
        // Move cursor to start of input area, clear line, rewrite
        term.write('\r');
        if (mode === 'python') {
            term.write(pyInContinuation ? PY_CONT_PROMPT : PY_PROMPT);
        } else {
            term.write(PROMPT);
        }
        term.write('\x1b[K'); // clear to end of line
        term.write(lineBuffer);
        // Move cursor to correct position
        var moveBack = lineBuffer.length - cursorPos;
        if (moveBack > 0) {
            term.write('\x1b[' + moveBack + 'D');
        }
    }

    // ---- Input handling ----
    function handleInput(data) {
        if (waitingForOutput) return;

        for (var i = 0; i < data.length; i++) {
            var ch = data[i];
            var code = ch.charCodeAt(0);

            // Handle escape sequences (arrows etc)
            if (ch === '\x1b' && i + 2 < data.length && data[i + 1] === '[') {
                var seq = data[i + 2];
                if (seq === 'A') { // Up arrow - history
                    navigateHistory(-1);
                    i += 2;
                    continue;
                } else if (seq === 'B') { // Down arrow - history
                    navigateHistory(1);
                    i += 2;
                    continue;
                } else if (seq === 'C') { // Right arrow
                    if (cursorPos < lineBuffer.length) {
                        cursorPos++;
                        term.write('\x1b[C');
                    }
                    i += 2;
                    continue;
                } else if (seq === 'D') { // Left arrow
                    if (cursorPos > 0) {
                        cursorPos--;
                        term.write('\x1b[D');
                    }
                    i += 2;
                    continue;
                }
                // Skip unknown escape sequences
                i += 2;
                continue;
            }

            if (code === 13 || code === 10) { // Enter
                term.write('\r\n');
                handleCommand(lineBuffer);
                continue;
            }

            if (code === 127 || code === 8) { // Backspace
                if (cursorPos > 0) {
                    lineBuffer = lineBuffer.slice(0, cursorPos - 1) + lineBuffer.slice(cursorPos);
                    cursorPos--;
                    redrawLine();
                }
                continue;
            }

            if (code === 4) { // Ctrl+D
                if (mode === 'python' && lineBuffer.length === 0) {
                    exitPythonMode();
                    continue;
                }
                continue;
            }

            if (code === 3) { // Ctrl+C
                term.write('^C\r\n');
                lineBuffer = '';
                cursorPos = 0;
                pyInContinuation = false;
                pyMultilineBuffer = [];
                showPrompt();
                continue;
            }

            if (code === 12) { // Ctrl+L
                term.clear();
                redrawLine();
                continue;
            }

            if (code === 1) { // Ctrl+A - home
                if (cursorPos > 0) {
                    term.write('\x1b[' + cursorPos + 'D');
                    cursorPos = 0;
                }
                continue;
            }

            if (code === 5) { // Ctrl+E - end
                if (cursorPos < lineBuffer.length) {
                    term.write('\x1b[' + (lineBuffer.length - cursorPos) + 'C');
                    cursorPos = lineBuffer.length;
                }
                continue;
            }

            // Printable character
            if (code >= 32) {
                lineBuffer = lineBuffer.slice(0, cursorPos) + ch + lineBuffer.slice(cursorPos);
                cursorPos++;
                redrawLine();
            }
        }
    }

    // ---- History navigation ----
    function navigateHistory(direction) {
        if (commandHistory.length === 0) return;

        if (historyIndex === -1 && direction === -1) {
            tempLine = lineBuffer;
            historyIndex = commandHistory.length - 1;
        } else if (direction === -1) {
            historyIndex = Math.max(0, historyIndex - 1);
        } else if (direction === 1) {
            historyIndex++;
            if (historyIndex >= commandHistory.length) {
                historyIndex = -1;
                lineBuffer = tempLine;
                cursorPos = lineBuffer.length;
                redrawLine();
                return;
            }
        }

        if (historyIndex >= 0 && historyIndex < commandHistory.length) {
            lineBuffer = commandHistory[historyIndex];
            cursorPos = lineBuffer.length;
            redrawLine();
        }
    }

    // ---- Command dispatch ----
    function handleCommand(cmd) {
        var trimmed = cmd.trim();

        // Push to history (skip empty and duplicates)
        if (trimmed && (commandHistory.length === 0 || commandHistory[commandHistory.length - 1] !== trimmed)) {
            commandHistory.push(trimmed);
            if (commandHistory.length > 200) commandHistory.shift();
        }
        historyIndex = -1;
        tempLine = '';

        if (mode === 'python') {
            handlePythonInput(trimmed, cmd);
            return;
        }

        // Shell mode
        if (trimmed === '') {
            showPrompt();
            return;
        }

        if (trimmed === 'clear') {
            term.clear();
            showPrompt();
            return;
        }

        if (trimmed === 'help') {
            printHelp();
            showPrompt();
            return;
        }

        if (trimmed === 'python') {
            enterPythonMode();
            return;
        }

        if (trimmed === 'exit' || trimmed === 'quit') {
            term.writeln('\x1b[33mUse the back button to close the terminal.\x1b[0m');
            showPrompt();
            return;
        }

        // Send to Kotlin via IPC
        sendCommandToKotlin(trimmed);
    }

    // ---- Help ----
    function printHelp() {
        term.writeln('');
        term.writeln('\x1b[1;35m  VCPNative Terminal Commands\x1b[0m');
        term.writeln('  \x1b[1;36m---------------------------\x1b[0m');
        term.writeln('  \x1b[1;33mhelp\x1b[0m       Show this help message');
        term.writeln('  \x1b[1;33mclear\x1b[0m      Clear the terminal screen');
        term.writeln('  \x1b[1;33mpython\x1b[0m     Enter Python REPL (Pyodide)');
        term.writeln('  \x1b[1;33mexit\x1b[0m       Exit Python REPL (or Ctrl+D)');
        term.writeln('');
        term.writeln('  \x1b[90mOther commands are sent to the Kotlin host.\x1b[0m');
        term.writeln('  \x1b[90mCtrl+C to cancel, Ctrl+L to clear.\x1b[0m');
        term.writeln('');
    }

    // ---- IPC: send command to Kotlin ----
    function sendCommandToKotlin(command) {
        waitingForOutput = true;

        if (typeof window.electronAPI !== 'undefined' && window.electronAPI.invoke) {
            window.electronAPI.invoke('terminal:execute', command).then(function (result) {
                if (result && result.text) {
                    writeOutput(result.text, result.stream || 'stdout');
                }
                waitingForOutput = false;
                showPrompt();
            }).catch(function (err) {
                writeOutput('Error: ' + (err.message || String(err)), 'stderr');
                waitingForOutput = false;
                showPrompt();
            });
        } else {
            writeOutput('Bridge not available. Cannot execute: ' + command, 'stderr');
            waitingForOutput = false;
            showPrompt();
        }
    }

    // ---- IPC: listen for pushed output from Kotlin ----
    function setupOutputListener() {
        if (typeof window.electron !== 'undefined' && window.electron.on) {
            window.electron.on('terminal:output', function (data) {
                if (data && data.text) {
                    writeOutput(data.text, data.stream || 'stdout');
                }
            });
        }
    }

    // ---- Output rendering ----
    function writeOutput(text, stream) {
        if (stream === 'stderr') {
            term.write('\x1b[31m'); // red
        }
        term.write(text);
        if (stream === 'stderr') {
            term.write('\x1b[0m');
        }
        // Ensure we end on a new line
        if (text.length > 0 && text[text.length - 1] !== '\n') {
            term.write('\r\n');
        }
    }

    // ---- Python REPL Mode ----
    function enterPythonMode() {
        if (pyodide) {
            switchToPythonMode();
            return;
        }

        if (pyodideLoading) {
            term.writeln('\x1b[33mPyodide is already loading, please wait...\x1b[0m');
            showPrompt();
            return;
        }

        pyodideLoading = true;
        setStatus('python', 'Loading Python...');
        term.writeln('\x1b[33mLoading Python runtime (Pyodide)...\x1b[0m');
        term.writeln('\x1b[90mFirst load downloads ~7MB WASM (cached afterwards).\x1b[0m');

        var script = document.createElement('script');
        script.src = PYODIDE_CDN + 'pyodide.js';
        script.onload = function () {
            loadPyodide({ indexURL: PYODIDE_CDN }).then(function (py) {
                pyodide = py;
                pyodideLoading = false;

                // Setup stdout/stderr capture
                pyodide.runPython([
                    'import sys',
                    'import io',
                    '',
                    'class _TermOut(io.TextIOBase):',
                    '    def __init__(self, stream_name):',
                    '        self.stream_name = stream_name',
                    '        self._buf = ""',
                    '    def write(self, text):',
                    '        self._buf += text',
                    '        return len(text)',
                    '    def flush(self):',
                    '        pass',
                    '    def drain(self):',
                    '        out = self._buf',
                    '        self._buf = ""',
                    '        return out',
                    '',
                    'sys.stdout = _TermOut("stdout")',
                    'sys.stderr = _TermOut("stderr")',
                ].join('\n'));

                // Install micropip
                pyodide.loadPackage('micropip').then(function () {
                    term.writeln('\x1b[32mPython ' + pyodide.version + ' ready.\x1b[0m');
                    term.writeln('\x1b[90mType exit() or Ctrl+D to return to shell.\x1b[0m');
                    term.writeln('');
                    switchToPythonMode();
                }).catch(function () {
                    term.writeln('\x1b[32mPython ' + pyodide.version + ' ready (micropip unavailable).\x1b[0m');
                    term.writeln('');
                    switchToPythonMode();
                });
            }).catch(function (err) {
                pyodideLoading = false;
                term.writeln('\x1b[31mFailed to load Pyodide: ' + (err.message || String(err)) + '\x1b[0m');
                setStatus('error', 'Pyodide failed');
                showPrompt();
            });
        };
        script.onerror = function () {
            pyodideLoading = false;
            term.writeln('\x1b[31mFailed to download Pyodide. Check network.\x1b[0m');
            setStatus('error', 'Pyodide failed');
            showPrompt();
        };
        document.head.appendChild(script);
    }

    function switchToPythonMode() {
        mode = 'python';
        pyInContinuation = false;
        pyMultilineBuffer = [];
        setStatus('python', 'Python REPL');
        showPrompt();
    }

    function exitPythonMode() {
        mode = 'shell';
        pyInContinuation = false;
        pyMultilineBuffer = [];
        term.writeln('');
        setStatus('online', 'Shell');
        showPrompt();
    }

    function handlePythonInput(trimmed, raw) {
        // exit() or quit()
        if (trimmed === 'exit()' || trimmed === 'quit()') {
            exitPythonMode();
            return;
        }

        if (!pyodide) {
            term.writeln('\x1b[31mPyodide not loaded.\x1b[0m');
            showPrompt();
            return;
        }

        // Multiline detection: line ends with ':'
        if (pyInContinuation) {
            if (trimmed === '') {
                // Empty line ends multiline block
                var code = pyMultilineBuffer.join('\n');
                pyMultilineBuffer = [];
                pyInContinuation = false;
                executePython(code);
            } else {
                pyMultilineBuffer.push(raw);
                showPrompt();
            }
            return;
        }

        // Check if this line starts a block (ends with :)
        if (trimmed.endsWith(':')) {
            pyMultilineBuffer = [raw];
            pyInContinuation = true;
            showPrompt();
            return;
        }

        // Also handle try/except, def, class without trailing colon on first pass
        // (They'll have colon at end anyway, caught above)

        executePython(trimmed);
    }

    function executePython(code) {
        if (!code) {
            showPrompt();
            return;
        }

        try {
            // Handle micropip.install specially for async
            if (code.match(/^\s*(?:await\s+)?micropip\.install\s*\(/)) {
                var installCode = code.replace(/^\s*await\s+/, '');
                pyodide.runPythonAsync(installCode).then(function () {
                    drainPythonOutput();
                    showPrompt();
                }).catch(function (err) {
                    term.writeln('\x1b[31m' + String(err) + '\x1b[0m');
                    showPrompt();
                });
                return;
            }

            // Check if it's an expression (try eval first, then exec)
            var result;
            try {
                result = pyodide.runPython('__builtins__.__import__("ast").parse(' + JSON.stringify(code) + ', mode="eval")');
                // It's an expression - use eval to get the value
                result = pyodide.runPython(code);
                drainPythonOutput();
                if (result !== undefined && result !== null) {
                    var reprStr;
                    try {
                        reprStr = pyodide.runPython('repr(' + JSON.stringify(String(result)) + ')');
                        // Actually just use the JS representation for simple values
                        reprStr = String(result);
                    } catch (e) {
                        reprStr = String(result);
                    }
                    if (reprStr !== 'None' && reprStr !== 'undefined') {
                        term.writeln(reprStr);
                    }
                }
            } catch (parseErr) {
                // Not a simple expression, run as statements
                pyodide.runPython(code);
                drainPythonOutput();
            }
        } catch (err) {
            drainPythonOutput();
            var errStr = String(err);
            // Clean up Pyodide's verbose error format
            var lines = errStr.split('\n');
            for (var i = 0; i < lines.length; i++) {
                term.writeln('\x1b[31m' + lines[i] + '\x1b[0m');
            }
        }

        showPrompt();
    }

    function drainPythonOutput() {
        if (!pyodide) return;
        try {
            var stdout = pyodide.runPython('sys.stdout.drain()');
            if (stdout) {
                term.write(stdout);
                if (stdout[stdout.length - 1] !== '\n') {
                    term.write('\r\n');
                }
            }
        } catch (e) { /* ignore */ }

        try {
            var stderr = pyodide.runPython('sys.stderr.drain()');
            if (stderr) {
                term.write('\x1b[31m' + stderr + '\x1b[0m');
                if (stderr[stderr.length - 1] !== '\n') {
                    term.write('\r\n');
                }
            }
        } catch (e) { /* ignore */ }
    }

    // ---- Init ----
    function init() {
        initTerminal();
        setupOutputListener();
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
