package com.oas.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class RuntimeUpdaterTest {
    private val shaA = "c6799df710856f5f375c87b9caf885965253812bc5ea9199428439136883e97f"
    private val shaB = "4767bf3dc3086f5f375c87b9caf885965253812bc5ea9199428439136883e97f"

    @Test
    fun manifestMustMatchArchiveShaAndContainNonEmptyTasks() {
        assertTrue(RuntimeUpdater.isManifestArchiveShaMatched(shaA, shaA))
        assertTrue(RuntimeUpdater.isManifestArchiveShaMatched(shaA.uppercase(), shaA))
        assertFalse(RuntimeUpdater.isManifestArchiveShaMatched(shaB, shaA))
        assertFalse(RuntimeUpdater.isManifestArchiveShaMatched("", shaA))
        assertFalse(RuntimeUpdater.isManifestArchiveShaMatched("not-a-sha", shaA))
        assertFalse(RuntimeUpdater.isManifestArchiveShaMatched(null, shaA))

        assertTrue(RuntimeUpdater.isValidManifestMeta(shaA, shaA, listOf("Orochi", "DailyTrifles")))
        assertFalse(RuntimeUpdater.isValidManifestMeta(shaA, shaA, emptyList()))
        assertFalse(RuntimeUpdater.isValidManifestMeta(shaA, shaA, listOf("  ", "")))
        assertFalse(RuntimeUpdater.isValidManifestMeta(shaA, shaA, listOf("Orochi", 42)))
        assertFalse(RuntimeUpdater.isValidManifestMeta(shaA, shaA, listOf("Orochi", null)))
        assertFalse(RuntimeUpdater.isValidManifestMeta(shaA, shaA, listOf("Orochi", " ")))
        assertFalse(RuntimeUpdater.isValidManifestMeta(shaB, shaA, listOf("Orochi")))
    }

    @Test
    fun fallbackProtectionAppliesForBothAutoSwitchAndManualFallbackUrl() {
        assertTrue(RuntimeUpdater.shouldProtectAgainstDowngrade(RuntimeUpdater.FALLBACK_MANIFEST_URL, autoSwitchedToFallback = false))
        assertTrue(RuntimeUpdater.shouldProtectAgainstDowngrade("https://kongdr.bbs0.cc/update.json", autoSwitchedToFallback = false))
        assertTrue(RuntimeUpdater.shouldProtectAgainstDowngrade(RuntimeUpdater.DEFAULT_MANIFEST_URL, autoSwitchedToFallback = true))
        assertFalse(RuntimeUpdater.shouldProtectAgainstDowngrade(RuntimeUpdater.DEFAULT_MANIFEST_URL, autoSwitchedToFallback = false))
        assertFalse(RuntimeUpdater.isFallbackSiteUrl("https://kongdr.bbs0.cc.evil.com/update.json"))
    }

    @Test
    fun fallbackRejectsOlderOrEqualRuntimeVersionWhenLocalIsComparable() {
        assertFalse(RuntimeUpdater.isAcceptableRuntimeVersion("2026.11.27.0800", "2026.11.26.2217", protectDowngrade = true))
        assertFalse(RuntimeUpdater.isAcceptableRuntimeVersion("2026.11.26.2217", "2026.11.26.2217", protectDowngrade = true))
        assertFalse(RuntimeUpdater.isAcceptableRuntimeVersion("2026.11.26.2217", "unknown", protectDowngrade = true))
        assertTrue(RuntimeUpdater.isAcceptableRuntimeVersion("2026.11.26.2217", "2026.11.27.0800", protectDowngrade = true))
        assertTrue(RuntimeUpdater.isAcceptableRuntimeVersion("内置 (1.0.3)", "2026.11.26.2217", protectDowngrade = true))
        assertTrue(RuntimeUpdater.isAcceptableRuntimeVersion("2026.11.27.0800", "2026.11.26.2217", protectDowngrade = false))
    }

    @Test
    fun slowPrimaryFallsBackThenRetriesPrimaryWithoutSlowAbortWhenFallbackFails() {
        val calls = mutableListOf<String>()
        var retryReason = ""
        val usedFallback = RuntimeUpdater.executeDownloadWithFallback(
            canFallback = true,
            tryPrimary = { abortOnSlow ->
                calls += "primary(abortOnSlow=$abortOnSlow)"
                if(abortOnSlow) throw RuntimeUpdater.SlowDownloadException("主源下载过慢（18 KB/s）")
            },
            tryFallback = {
                calls += "fallback"
                throw SecurityException("脚本包 SHA-256 校验失败")
            },
            onSwitchingToFallback = { calls += "switch" },
            onRetryingPrimary = { reason ->
                retryReason = reason
                calls += "retry"
            }
        )
        assertFalse(usedFallback)
        assertEquals(listOf("primary(abortOnSlow=true)", "switch", "fallback", "retry", "primary(abortOnSlow=false)"), calls)
        assertTrue(retryReason.contains("SHA-256"))
    }

    @Test
    fun primaryFailureAndFallbackFailurePreservesBothReasons() {
        try {
            RuntimeUpdater.executeDownloadWithFallback(
                canFallback = true,
                tryPrimary = { throw IOException("GitHub connect timed out") },
                tryFallback = { throw IOException("kongdr.bbs0.cc connection reset") },
                onSwitchingToFallback = {},
                onRetryingPrimary = { fail("Non-slow failure should not retry primary") }
            )
            fail("Expected exception")
        } catch(e: IllegalStateException) {
            val msg = e.message.orEmpty()
            assertTrue(msg.contains("GitHub connect timed out"))
            assertTrue(msg.contains("kongdr.bbs0.cc connection reset"))
        }
    }

    @Test
    fun slowPrimaryFallbackFailureAndRetryFailurePreservesBothReasons() {
        try {
            RuntimeUpdater.executeDownloadWithFallback(
                canFallback = true,
                tryPrimary = { abortOnSlow ->
                    if(abortOnSlow) throw RuntimeUpdater.SlowDownloadException("主源下载过慢（15 KB/s）")
                    else throw IOException("primary reset during retry")
                },
                tryFallback = { throw IOException("fallback 502") },
                onSwitchingToFallback = {},
                onRetryingPrimary = {}
            )
            fail("Expected exception")
        } catch(e: IllegalStateException) {
            val msg = e.message.orEmpty()
            assertTrue(msg.contains("fallback 502"))
            assertTrue(msg.contains("primary reset during retry"))
        }
    }
}
