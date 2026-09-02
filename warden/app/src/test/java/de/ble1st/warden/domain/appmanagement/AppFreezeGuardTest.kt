package de.ble1st.warden.domain.appmanagement

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppFreezeGuardTest {

    private val ownPackage = "de.ble1st.warden"
    // Testet die generische protectedPackageNames-Logik mit willkürlichen Beispielpaketen,
    // unabhängig vom tatsächlichen Produktivwert (AppManagementController.SUITE_PACKAGE_NAMES —
    // Sentinel + die drei Suite-Apps de.ble1st.camera/files/gallery, s. dessen Klassendoc).
    private val suitePackages = setOf(
        "com.example.protected1",
        "com.example.protected2",
    )

    @Test
    fun ownPackageIsAlwaysProtected() {
        assertTrue(AppFreezeGuard.isProtected(ownPackage, ownPackage, suitePackages))
    }

    @Test
    fun suitePackagesAreProtected() {
        for (pkg in suitePackages) {
            assertTrue("$pkg should be protected", AppFreezeGuard.isProtected(pkg, ownPackage, suitePackages))
        }
    }

    @Test
    fun arbitraryThirdPartyPackageIsNotProtected() {
        assertFalse(AppFreezeGuard.isProtected("com.example.somegame", ownPackage, suitePackages))
    }

    @Test
    fun emptySuiteSetStillProtectsOwnPackage() {
        assertTrue(AppFreezeGuard.isProtected(ownPackage, ownPackage, emptySet()))
        assertFalse(AppFreezeGuard.isProtected("com.example.somegame", ownPackage, emptySet()))
    }
}
