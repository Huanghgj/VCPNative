package com.vcpnative.app.data.sync

import org.json.JSONObject

/**
 * Intelligent JSON merger for settings synchronization.
 *
 * Ported from VCPMobile `sync_handlers.rs`.
 * Rule: remote values take precedence, but mobile-only fields are preserved.
 */
object JsonMerger {

    /** Fields that exist only on mobile and should never be overwritten by sync. */
    private val MOBILE_ONLY_FIELDS = setOf(
        "overlayApiUrl",
        "overlayApiKey",
        "overlayModel",
        "enableFloatingWindow",
        "lastAgentId",
        "lastTopicId",
        "lastOpenItemId",
        "lastOpenTopicId",
    )

    /**
     * Merge remote settings into local, preserving mobile-only fields.
     *
     * @param remote The settings from the desktop/server
     * @param local  The current mobile settings
     * @return Merged settings (remote as base, mobile-only from local)
     */
    fun mergeSettings(remote: JSONObject, local: JSONObject): JSONObject {
        // Start with remote as the base
        val result = JSONObject(remote.toString())

        // Restore mobile-only fields from local
        for (field in MOBILE_ONLY_FIELDS) {
            if (local.has(field)) {
                result.put(field, local.get(field))
            }
        }

        return result
    }
}
