package de.ble1st.warden.domain.score

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityScoreDecisionTest {

    @Test
    fun weightsSumToOne() {
        val sum = SecurityScoreDecision.THREAT_WEIGHT + SecurityScoreDecision.PERMISSION_WEIGHT +
            SecurityScoreDecision.INTEGRITY_WEIGHT + SecurityScoreDecision.HARDENING_WEIGHT
        assertEquals(1.0, sum, 0.0001)
    }

    @Test
    fun perfectDeviceScoresMaximumInEveryCategory() {
        val breakdown = SecurityScoreDecision.evaluate(
            warningFindings = 0,
            hasCriticalFinding = false,
            totalApps = 10,
            flaggedApps = 0,
            rootIndicatorCount = 0,
            adbEnabled = false,
            developerOptionsEnabled = false,
            storageEncrypted = true,
            activeSafeguards = 32,
            totalSafeguards = 32,
        )
        assertEquals(100, breakdown.threatScore)
        assertEquals(100, breakdown.permissionScore)
        assertEquals(100, breakdown.integrityScore)
        assertEquals(100, breakdown.hardeningScore)
        assertEquals(100, breakdown.total)
        assertEquals(SecurityScoreLevel.SEHR_GUT, breakdown.level)
    }

    @Test
    fun criticalFindingZeroesThreatScoreRegardlessOfWarningCount() {
        assertEquals(0, SecurityScoreDecision.threatScore(warningFindings = 0, hasCriticalFinding = true))
        assertEquals(0, SecurityScoreDecision.threatScore(warningFindings = 5, hasCriticalFinding = true))
    }

    @Test
    fun warningFindingsDeductProgressivelyWithFloorZero() {
        assertEquals(100, SecurityScoreDecision.threatScore(0, false))
        assertEquals(80, SecurityScoreDecision.threatScore(1, false))
        assertEquals(60, SecurityScoreDecision.threatScore(2, false))
        assertEquals(0, SecurityScoreDecision.threatScore(10, false))
    }

    @Test
    fun permissionScoreReflectsFlaggedRatio() {
        assertEquals(100, SecurityScoreDecision.permissionScore(totalApps = 0, flaggedApps = 0))
        assertEquals(100, SecurityScoreDecision.permissionScore(totalApps = 10, flaggedApps = 0))
        assertEquals(50, SecurityScoreDecision.permissionScore(totalApps = 10, flaggedApps = 5))
        assertEquals(0, SecurityScoreDecision.permissionScore(totalApps = 10, flaggedApps = 10))
    }

    @Test
    fun integrityPenaltiesStackAdditively() {
        val score = SecurityScoreDecision.integrityScore(
            rootIndicatorCount = 1,
            adbEnabled = true,
            developerOptionsEnabled = true,
            storageEncrypted = false,
        )
        // 100 - 50 (root) - 15 (adb) - 10 (dev options) - 25 (keine Verschlüsselung) = 0
        assertEquals(0, score)
    }

    @Test
    fun integrityScoreNeverGoesNegative() {
        // mehr Abzüge als möglich absichtlich nicht erreichbar über die vier Booleans/Root-Zahl,
        // aber coerceIn muss trotzdem greifen falls die Gewichte sich künftig ändern.
        val score = SecurityScoreDecision.integrityScore(
            rootIndicatorCount = 5,
            adbEnabled = true,
            developerOptionsEnabled = true,
            storageEncrypted = false,
        )
        assertTrue(score >= 0)
    }

    @Test
    fun onlyAdbEnabledCostsFifteenPoints() {
        val score = SecurityScoreDecision.integrityScore(
            rootIndicatorCount = 0,
            adbEnabled = true,
            developerOptionsEnabled = false,
            storageEncrypted = true,
        )
        assertEquals(85, score)
    }

    @Test
    fun hardeningScoreReflectsActiveRatio() {
        assertEquals(0, SecurityScoreDecision.hardeningScore(activeCount = 0, totalCount = 0))
        assertEquals(0, SecurityScoreDecision.hardeningScore(activeCount = 0, totalCount = 32))
        assertEquals(50, SecurityScoreDecision.hardeningScore(activeCount = 16, totalCount = 32))
        assertEquals(100, SecurityScoreDecision.hardeningScore(activeCount = 32, totalCount = 32))
    }

    @Test
    fun levelThresholdsAreInclusiveAtTheLowerBound() {
        assertEquals(SecurityScoreLevel.SEHR_GUT, SecurityScoreDecision.levelFor(85))
        assertEquals(SecurityScoreLevel.GUT, SecurityScoreDecision.levelFor(84))
        assertEquals(SecurityScoreLevel.GUT, SecurityScoreDecision.levelFor(65))
        assertEquals(SecurityScoreLevel.VERBESSERUNGSWUERDIG, SecurityScoreDecision.levelFor(64))
        assertEquals(SecurityScoreLevel.VERBESSERUNGSWUERDIG, SecurityScoreDecision.levelFor(40))
        assertEquals(SecurityScoreLevel.KRITISCH, SecurityScoreDecision.levelFor(39))
        assertEquals(SecurityScoreLevel.KRITISCH, SecurityScoreDecision.levelFor(0))
    }

    @Test
    fun worstDeviceScoresZeroOverall() {
        val breakdown = SecurityScoreDecision.evaluate(
            warningFindings = 0,
            hasCriticalFinding = true,
            totalApps = 10,
            flaggedApps = 10,
            rootIndicatorCount = 3,
            adbEnabled = true,
            developerOptionsEnabled = true,
            storageEncrypted = false,
            activeSafeguards = 0,
            totalSafeguards = 32,
        )
        assertEquals(0, breakdown.total)
        assertEquals(SecurityScoreLevel.KRITISCH, breakdown.level)
    }
}
