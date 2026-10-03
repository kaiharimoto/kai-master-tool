package com.kaiharimoto.mastertool.core.sync

import kotlin.test.Test
import kotlin.test.assertTrue

/** A refused Drive call says what to do, from Google's own reason (1.0.87, kai: "google drive would not let the app list its files"). */
class CloudErrorsTest {
    private fun say(code: Int, body: String) = CloudErrors.explain("Google Drive", code, body, "list its files")

    @Test
    fun theApiSwitchedOffIsSaidAsSuchNotAsASignIn() {
        val body = """{"error":{"code":403,"message":"Google Drive API has not been used in project 123456789012 before or it is disabled. Enable it by visiting https://console.developers.google.com/apis/api/drive.googleapis.com/overview?project=123456789012 then retry.","errors":[{"message":"…","domain":"usageLimits","reason":"accessNotConfigured","extendedHelp":"https://console.developers.google.com"}],"status":"PERMISSION_DENIED","details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"SERVICE_DISABLED","domain":"googleapis.com"}]}}"""
        val s = say(403, body)
        assertTrue(s.contains("switched off"), s)
        assertTrue(s.contains("signing in again will not help"), s)
        assertTrue(s.contains("project=123456789012"), s)
    }

    @Test
    fun anUntickedPermissionSaysWhichBoxToTick() {
        val body = """{"error":{"code":403,"message":"Request had insufficient authentication scopes.","errors":[{"message":"Insufficient Permission","domain":"global","reason":"insufficientPermissions"}],"status":"PERMISSION_DENIED","details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"ACCESS_TOKEN_SCOPE_INSUFFICIENT"}]}}"""
        val s = say(403, body)
        assertTrue(s.contains("tick"), s)
        assertTrue(s.contains(CloudErrors.DRIVE_BOX), s)
    }

    @Test
    fun limitsFullnessAndTheRestKeepGooglesWords() {
        val rate = """{"error":{"code":403,"message":"Rate Limit Exceeded","errors":[{"reason":"userRateLimitExceeded"}]}}"""
        assertTrue(say(403, rate).contains("slow down"))
        assertTrue(say(403, """{"error":{"errors":[{"reason":"storageQuotaExceeded"}]}}""").contains("full"))
        assertTrue(say(401, "").contains("signed this device out"))
        val other = say(403, """{"error":{"code":403,"message":"The user has not granted the app 123 read access to the file."}}""")
        assertTrue(other.contains("has not granted"), other)
        assertTrue(say(500, "not json").contains("(500)"))
    }
}
