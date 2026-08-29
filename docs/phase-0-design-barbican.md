# Phase 0: Design-Dokument - Barbican Netzwerk-Sperre Integration

**Version:** 1.0  
**Datum:** 2026-08-29  
**Status:** In Arbeit (Phase 0, Tag 1-2)  
**Verantwortlich:** Mistral Vibe  

---

## 🏗️ Architektur

```
┌─────────────────────────────────────────────────────────────────┐
│                        domain Layer                                 │
├─────────────────────────────────────────────────────────────────┤
│  ┌─────────────────┐    ┌──────────────────┐    ┌─────────────┐ │
│  │  INetLockService │◄──►│ NetLockRepository │    │  NetLock-   │ │
│  │  (Interface)     │    │  (State Storage)  │    │  Rules      │ │
│  └────────┬────────┘    └──────────────────┘    └─────────────┘ │
│           │                                                     │
│           ▼                                                     │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │                     NetLockServiceImpl                         │ │
│  │  - applyLock() / releaseLock()                               │ │
│  │  - checkPresenceGate() (uses PresenceDetector)                │ │
│  │  - handleBarbicanServerFailure() (Fail-safe)                 │ │
│  └─────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────────────┐
│                        app Layer                                   │
├─────────────────────────────────────────────────────────────────┤
│  ┌─────────────────┐    ┌──────────────────┐    ┌─────────────┐ │
│  │ BarbicanApiClient│    │ NetLockdownAuthorizer │    │ Warden-    │ │
│  │ (Optional)       │    │ (DPM Integration) │    │ VpnService │ │
│  └─────────────────┘    └──────────────────┘    └─────────────┘ │
└─────────────────────────────────────────────────────────────────┘
```

---

## 📋 Komponenten

### 1. `INetLockService` (Interface, domain)

**Datei:** `domain/netlock/INetLockService.kt`

```kotlin
package de.ble1st.warden.domain.netlock

interface INetLockService {
    /**
     * Aktiviert die Netzwerk-Sperre.
     * @param reason Grund für die Sperre (z. B. Bedrohung erkannt)
     * @param force Überspringt Presence-Gate-Check (nur für Notfall-Override)
     */
    fun lock(reason: LockReason, force: Boolean = false): Result<Unit>
    
    /**
     * Deaktiviert die Netzwerk-Sperre.
     */
    fun unlock(): Result<Unit>
    
    /**
     * Prüft, ob die Sperre aktiv ist.
     */
    fun isLocked(): Boolean
    
    /**
     * Gibt den aktuellen Sperr-Grund zurück.
     */
    fun getLockReason(): LockReason?
}

sealed class LockReason {
    data object Manual : LockReason()
    data class ThreatDetected(val threatLevel: ThreatLevel, val description: String) : LockReason()
    data object Emergency : LockReason()
}
```

---

### 2. `NetLockServiceImpl` (Implementierung, domain)

**Datei:** `domain/netlock/NetLockServiceImpl.kt`

```kotlin
package de.ble1st.warden.domain.netlock

class NetLockServiceImpl(
    private val repository: NetLockRepository,
    private val presenceDetector: PresenceDetector,
    private val authorizer: NetLockdownAuthorizer,
    private val barbicanApi: BarbicanApiClient? = null
) : INetLockService {
    
    override fun lock(reason: LockReason, force: Boolean): Result<Unit> {
        return try {
            // 1. Presence-Gate prüfen (außer bei force oder Manual)
            if (!force && reason !is LockReason.Manual) {
                if (presenceDetector.isPresent()) {
                    return Result.failure(NetLockError.PresenceGateBlocked)
                }
            }
            
            // 2. Versuche Barbican-Server zu erreichen (optional)
            barbicanApi?.lock(reason)?.onFailure {
                // 3. Fallback: Lokaler Modus
                repository.saveLockState(reason, isLocalFallback = true)
            }?: run {
                repository.saveLockState(reason, isLocalFallback = false)
            }
            
            // 4. DPM Always-On VPN aktivieren
            authorizer.apply()
            
            // 5. VPN-Service starten
            startVpnService()
            
            Result.success(Unit)
        } catch (e: Exception) {
            // 6. Immer lokal speichern als Notfall
            repository.saveLockState(reason, isEmergency = true)
            Result.success(Unit) // Fail-safe: Sperre als aktiv markieren
        }
    }
    
    private fun startVpnService() {
        // Startet WardenVpnService via Intent
    }
}
```

---

### 3. `NetLockRepository` (State Storage, domain)

**Datei:** `domain/netlock/NetLockRepository.kt`

```kotlin
package de.ble1st.warden.domain.netlock

interface NetLockRepository {
    fun saveLockState(reason: LockReason, isLocalFallback: Boolean, isEmergency: Boolean)
    fun loadLockState(): LockState?
    fun clearLockState()
}

data class LockState(
    val reason: LockReason,
    val timestamp: Instant,
    val isLocalFallback: Boolean,
    val isEmergency: Boolean
)
```

---

### 4. `BarbicanApiClient` (Optional, app Layer)

**Datei:** `netlock/BarbicanApiClient.kt`

```kotlin
package de.ble1st.warden.netlock

interface BarbicanApiClient {
    suspend fun lock(reason: LockReason): Result<Unit>
    suspend fun unlock(): Result<Unit>
    suspend fun getStatus(): Result<BarbicanStatus>
}

// Implementierung für echte Barbican-Server
class BarbicanApiClientImpl(private val apiUrl: String) : BarbicanApiClient {
    // HTTP-Aufrufe an Barbican-Server
}

// Mock-Implementierung für Offline-Modus
class BarbicanApiClientMock : BarbicanApiClient {
    override fun lock(reason: LockReason): Result<Unit> = Result.success(Unit)
    override fun unlock(): Result<Unit> = Result.success(Unit)
    override fun getStatus(): Result<BarbicanStatus> = 
        Result.success(BarbicanStatus.OFFLINE)
}

enum class BarbicanStatus {
    ONLINE,
    OFFLINE,
    ERROR
}
```

---

## ✅ Design-Guidelines

| Guideline | Umsetzung |
|-----------|-----------|
| **domain/* rein** | ✅ Alle Logik in `domain/netlock/`, keine Android-Imports |
| **Fail-safe** | ✅ Mehrere Fallback-Ebenen (Barbican → lokal → Notfall) |
| **Presence-Gate** | ✅ Sperre nur bei Abwesenheit (außer Manual/Force) |
| **Offline-first** | ✅ Barbican ist optional, Kernfunktionen laufen lokal |

---

## 🔗 Integration

- **Presence-Gate:** Nutzt `PresenceDetector.kt` (bereits vorhanden)
- **DPM-Integration:** Nutzt `NetLockdownAuthorizer.kt` (bereits vorhanden in geparktem Code)
- **Logging:** Nutzt `SystemEventLog` mit korrektem Zeitstempel (Bugfix in f47933b)
- **VPN-Service:** Nutzt `WardenVpnService.kt` (bereits vorhanden in geparktem Code)

---

## 📋 Nächste Schritte

1. Kernfehler in Barbican analysieren (siehe [phase-0-barbican-analyse.md](./phase-0-barbican-analyse.md))
2. Code aus `app/netlock-disabled/` nach `app/src/main/java/` verschieben
3. Alle Verweise in `AndroidManifest.xml`, `WardenApplication.kt` etc. reaktivieren
4. Fixes aus [phase-0-barbican-analyse.md](./phase-0-barbican-analyse.md) implementieren
