package de.ble1st.warden.domain.appmanagement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SuspiciousAppScanDecisionTest {

    private val ownPackage = "de.ble1st.warden"
    private val suitePackages = setOf("com.example.protected1", "com.example.protected2")

    private fun evaluate(
        admins: Set<String> = emptySet(),
        accessibility: Set<String> = emptySet(),
        overlay: Set<String> = emptySet(),
        notificationListener: Set<String> = emptySet(),
        unknownInstallSource: Set<String> = emptySet(),
        signingCertChanged: Set<String> = emptySet(),
        deviceAdminNewlyActivated: Set<String> = emptySet(),
        accessibilityNewlyActivated: Set<String> = emptySet(),
        versionDowngraded: Set<String> = emptySet(),
        system: Set<String> = emptySet(),
        trusted: Set<String> = emptySet(),
    ) = SuspiciousAppScanDecision.evaluate(
        deviceAdminPackageNames = admins,
        accessibilityPackageNames = accessibility,
        overlayPackageNames = overlay,
        notificationListenerPackageNames = notificationListener,
        unknownInstallSourcePackageNames = unknownInstallSource,
        signingCertChangedPackageNames = signingCertChanged,
        deviceAdminNewlyActivatedPackageNames = deviceAdminNewlyActivated,
        accessibilityNewlyActivatedPackageNames = accessibilityNewlyActivated,
        versionDowngradedPackageNames = versionDowngraded,
        ownPackageName = ownPackage,
        protectedPackageNames = suitePackages,
        systemPackageNames = system,
        trustedPackageNames = trusted,
    )

    @Test
    fun noSignalsProduceNoFindings() {
        assertTrue(evaluate().isEmpty())
    }

    @Test
    fun extraDeviceAdminIsFlagged() {
        val findings = evaluate(admins = setOf("com.example.malware"))

        assertEquals(1, findings.size)
        assertEquals("com.example.malware", findings[0].packageName)
        assertEquals(setOf(SuspiciousSignal.EXTRA_DEVICE_ADMIN), findings[0].signals)
    }

    @Test
    fun declaredAccessibilityServiceIsFlagged() {
        val findings = evaluate(accessibility = setOf("com.example.overlay"))

        assertEquals(1, findings.size)
        assertEquals(setOf(SuspiciousSignal.ACCESSIBILITY_SERVICE_DECLARED), findings[0].signals)
    }

    @Test
    fun bothSignalsForTheSamePackageMergeIntoOneFinding() {
        val findings = evaluate(
            admins = setOf("com.example.malware"),
            accessibility = setOf("com.example.malware"),
        )

        assertEquals(1, findings.size)
        assertEquals(
            setOf(SuspiciousSignal.EXTRA_DEVICE_ADMIN, SuspiciousSignal.ACCESSIBILITY_SERVICE_DECLARED),
            findings[0].signals,
        )
    }

    @Test
    fun ownPackageIsNeverFlagged() {
        // Warden ist immer sein eigener Device-Admin (WardenDeviceAdminReceiver) — ohne diesen
        // Ausschluss wäre jeder Scan garantiert ein Falsch-Fund.
        assertTrue(evaluate(admins = setOf(ownPackage)).isEmpty())
    }

    @Test
    fun suitePackagesAreNeverFlagged() {
        assertTrue(evaluate(admins = suitePackages, accessibility = suitePackages).isEmpty())
    }

    @Test
    fun systemAppsAreNeverFlagged() {
        assertTrue(evaluate(accessibility = setOf("com.samsung.accessibility"), system = setOf("com.samsung.accessibility")).isEmpty())
    }

    @Test
    fun trustedPackagesAreExcluded() {
        assertTrue(evaluate(admins = setOf("com.example.knowngood"), trusted = setOf("com.example.knowngood")).isEmpty())
    }

    @Test
    fun findingsAreSortedByPackageName() {
        val findings = evaluate(admins = setOf("z.pkg", "a.pkg"))

        assertEquals(listOf("a.pkg", "z.pkg"), findings.map { it.packageName })
    }

    @Test
    fun overlayPermissionIsFlagged() {
        val findings = evaluate(overlay = setOf("com.example.overlay"))

        assertEquals(1, findings.size)
        assertEquals(setOf(SuspiciousSignal.OVERLAY_PERMISSION_DECLARED), findings[0].signals)
    }

    @Test
    fun notificationListenerIsFlagged() {
        val findings = evaluate(notificationListener = setOf("com.example.spy"))

        assertEquals(setOf(SuspiciousSignal.NOTIFICATION_LISTENER_DECLARED), findings[0].signals)
    }

    @Test
    fun unknownInstallSourceIsFlagged() {
        val findings = evaluate(unknownInstallSource = setOf("com.example.sideloaded"))

        assertEquals(setOf(SuspiciousSignal.UNKNOWN_INSTALL_SOURCE), findings[0].signals)
    }

    @Test
    fun signingCertChangedIsFlagged() {
        val findings = evaluate(signingCertChanged = setOf("com.example.hijacked"))

        assertEquals(setOf(SuspiciousSignal.SIGNING_CERT_CHANGED), findings[0].signals)
    }

    @Test
    fun versionDowngradeIsFlagged() {
        val findings = evaluate(versionDowngraded = setOf("com.example.rollback"))

        assertEquals(setOf(SuspiciousSignal.VERSION_DOWNGRADED), findings[0].signals)
    }

    @Test
    fun newlyActivatedAdminAndAccessibilityAreFlaggedIndependently() {
        val findings = evaluate(
            deviceAdminNewlyActivated = setOf("com.example.a"),
            accessibilityNewlyActivated = setOf("com.example.b"),
        )

        assertEquals(2, findings.size)
        assertEquals(setOf(SuspiciousSignal.DEVICE_ADMIN_NEWLY_ACTIVATED), findings[0].signals)
        assertEquals(setOf(SuspiciousSignal.ACCESSIBILITY_SERVICE_NEWLY_ACTIVATED), findings[1].signals)
    }

    @Test
    fun exclusionSetsApplyToAllSignalsUniformly() {
        // Dieselben vier Ausschlussmengen (own/protected/system/trusted) müssen für jedes neue
        // Signal genauso greifen wie für die ursprünglichen zwei — ownPackage stellvertretend
        // geprüft.
        assertTrue(
            evaluate(
                overlay = setOf(ownPackage),
                notificationListener = setOf(ownPackage),
                unknownInstallSource = setOf(ownPackage),
                signingCertChanged = setOf(ownPackage),
                deviceAdminNewlyActivated = setOf(ownPackage),
                accessibilityNewlyActivated = setOf(ownPackage),
            ).isEmpty(),
        )
    }
}
