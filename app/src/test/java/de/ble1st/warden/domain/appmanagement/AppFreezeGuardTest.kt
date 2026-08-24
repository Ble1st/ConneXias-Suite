package de.ble1st.warden.domain.appmanagement

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppFreezeGuardTest {

    private val ownPackage = "de.ble1st.warden"
    // Warden hat keine Geschwister-Suite-APKs mehr (SUITE_PACKAGE_NAMES ist leer, s.
    // AppManagementController-Klassendoc) — dieses Set testet die generische
    // protectedPackageNames-Logik trotzdem mit ein paar willkürlichen Beispielpaketen, unabhängig
    // vom (jetzt leeren) Produktivwert.
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
