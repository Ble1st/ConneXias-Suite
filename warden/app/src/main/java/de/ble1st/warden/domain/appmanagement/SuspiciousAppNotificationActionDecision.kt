package de.ble1st.warden.domain.appmanagement

/**
 * Notification freeze / clear-data / silent DO uninstall must not run from a locked
 * shade, and must not accept a stale package extra after the finding was trusted or
 * otherwise closed.
 */
object SuspiciousAppNotificationActionDecision {

    fun allowDestructiveAction(
        deviceLocked: Boolean,
        isOpenNotifiedFinding: Boolean,
    ): Boolean = !deviceLocked && isOpenNotifiedFinding
}
