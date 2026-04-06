package com.vcpnative.app.terminal

import com.vcpnative.app.bridge.IpcDispatcher
import org.json.JSONArray
import org.json.JSONObject

/**
 * Registers terminal-related IPC channels on the given [dispatcher].
 *
 * Channels:
 * - `terminal:execute`  — run a shell command, streams output via eventEmitter.
 * - `terminal:interrupt` — kill the running process.
 * - `terminal:get-env`  — return terminal environment info.
 */
fun registerTerminalHandlers(
    dispatcher: IpcDispatcher,
    executor: TerminalExecutor,
) {
    // ── terminal:execute ─────────────────────────────────────────────
    dispatcher.register("terminal:execute", IpcDispatcher.Handler { args ->
        val command = args.optString(0, "")
        if (command.isBlank()) {
            return@Handler JSONObject().apply {
                put("error", true)
                put("message", "No command provided")
            }
        }

        val exitCode = executor.execute(command) { text, stream ->
            dispatcher.eventEmitter?.invoke(
                "terminal:output",
                JSONObject().apply {
                    put("text", text)
                    put("stream", stream)
                },
            )
        }

        JSONObject().apply {
            put("exitCode", exitCode)
        }
    })

    // ── terminal:interrupt ───────────────────────────────────────────
    dispatcher.register("terminal:interrupt", IpcDispatcher.Handler {
        executor.interrupt()
        JSONObject().apply {
            put("success", true)
        }
    })

    // ── terminal:get-env ─────────────────────────────────────────────
    dispatcher.register("terminal:get-env", IpcDispatcher.Handler {
        JSONObject().apply {
            put("home", executor.terminalHome.absolutePath)
            put("hasTermux", false)
            put("pyodideAvailable", true)
            put("platform", "android")
            put("skills", JSONArray()) // populated by SkillRegistry later
        }
    })
}
