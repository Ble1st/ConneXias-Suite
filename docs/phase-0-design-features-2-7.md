# Phase 0: Design-Dokumente für Features 2-7

**Version:** 1.0  
**Datum:** 2026-08-29  
**Status:** In Arbeit (Phase 0, Tag 1-2)  
**Verantwortlich:** Mistral Vibe  

---

## 📌 Überblick

Dieses Dokument enthält die **technischen Design-Dokumente** für Features 2-7:

1. **IMSI-Catcher Detection** (Feature 2)
2. **App Behavioral Analysis** (Feature 3)
3. **Permission Auto-Block** (Feature 4)
4. **Storage Encryption Verification** (Feature 5)
5. **Security Score Dashboard** (Feature 6)
6. **Quick-Action Widgets** (Feature 7)

Alle Features folgen den **Warden Design-Guidelines** (CLAUDE.md).

---

## 🎯 Feature 2: IMSI-Catcher Detection

### Architektur
```
domain/threat/
├── IImsiCatcherDetector.kt    # Interface
├── ImsiCatcherDetectorImpl.kt # Implementierung
├── SignalMonitor.kt          # Signal-Daten
├── CellDatabase.kt          # Zell-Datenbank
└── ThreatIndicator.kt       # Datenklassen
```

### Hauptkomponenten

#### `IImsiCatcherDetector` (Interface)
```kotlin
package de.ble1st.warden.domain.threat

interface IImsiCatcherDetector {
    fun detect(): ThreatLevel
    fun addListener(listener: (ThreatLevel) -> Unit)
    fun removeListener(listener: (ThreatLevel) -> Unit)
}

enum class ThreatLevel { NONE, LOW, MEDIUM, HIGH }
```

#### `ImsiCatcherDetectorImpl` (domain)
- Kombiniert SignalMonitor und CellDatabase
- Berechnet ThreatLevel basierend auf 4 Indikatoren
- Benachrichtigt Listener bei Änderungen

#### Detektionslogik
```kotlin
fun detect(): ThreatLevel {
    val indicators = listOf(
        signalMonitor.hasSuddenSignalJump(),
        signalMonitor.hasFrequentCellChanges(),
        !cellDatabase.isKnownCell(currentCell),
        !cellDatabase.isValidLacTac(currentLacTac)
    ).count { it }
    
    return when {
        indicators >= 3 -> ThreatLevel.HIGH
        indicators >= 2 -> ThreatLevel.MEDIUM
        indicators >= 1 -> ThreatLevel.LOW
        else -> ThreatLevel.NONE
    }
}
```

### Integration
- **ThreatDetectionEngine:** Ergebnisse werden weitergeleitet
- **Barbican:** Bei HIGH → Netzwerk-Sperre
- **Benachrichtigungen:** Warnung bei MEDIUM/HIGH

### Guidelines
| Guideline | Umsetzung |
|-----------|-----------|
| domain/* rein | ✅ Keine Android-Imports in domain/ |
| Fail-safe | ✅ Fällt auf letzte bekannte Werte zurück |
| Presence-Gate | ❌ Nicht nötig |
| Offline-first | ✅ Alle Daten lokal verfügbar |

---

## 🎯 Feature 3: App Behavioral Analysis

### Architektur
```
domain/appanalysis/
├── BehaviorMonitor.kt       # Hauptkomponente
├── PermissionTracker.kt    # Permission-Nutzung
├── NetworkTracker.kt       # Netzwerkzugriffe
├── AnomalyDetector.kt      # Anomalie-Erkennung
└── BehaviorMlModel.kt      # ML-Modell (TFLite)
```

### Hauptkomponenten

#### `BehaviorMonitor` (Interface)
```kotlin
package de.ble1st.warden.domain.appanalysis

interface BehaviorMonitor {
    fun start()
    fun stop()
    fun watchApp(packageName: String)
    fun unwatchApp(packageName: String)
    fun getAppStatus(packageName: String): AppBehaviorStatus
    fun addAnomalyListener(listener: (AppAnomaly) -> Unit)
}
```

#### `AnomalyDetector` (domain)
- Kombiniert regelbasierte und ML-basierte Detektion
- Regelbasiert: Vordefinierte Regeln (z. B. Kamera-Zugriff für Nicht-Kamera-Apps)
- ML-basiert: TensorFlow Lite Modell für Anomalie-Erkennung

#### ML-Modell (TensorFlow Lite)
- **Input:** 40 Features (Permissions, Netzwerk, Temporal, Context)
- **Output:** Anomalie-Score (0.0-1.0) + Klassifikation
- **Größe:** < 1MB (quantisiert)
- **Offline:** Funktioniert ohne Internet

### Integration
- **ThreatDetectionEngine:** Anomalien werden als Threat-Events gemeldet
- **Permission Auto-Block:** Bei wiederholten Anomalien → Auto-Block
- **SystemEventLog:** Alle Anomalien werden geloggt

### Guidelines
| Guideline | Umsetzung |
|-----------|-----------|
| domain/* rein | ✅ Keine Android-Imports in domain/ |
| Fail-safe | ✅ Fällt auf regelbasiert zurück |
| Presence-Gate | ❌ Nicht nötig |
| Offline-first | ✅ Lokales Modell, keine Cloud nötig |

---

## 🔐 Feature 4: Permission Auto-Block

### Architektur
```
domain/permission/
├── AutoBlockEngine.kt      # Hauptkomponente
├── BlockRule.kt            # Regel-Datenklasse
├── BlockAction.kt          # Aktions-Datenklasse
├── BlockRuleRepository.kt  # Regel-Speicher
└── AndroidPermissionAdapter.kt  # Android-Integration
```

### Hauptkomponenten

#### `AutoBlockEngine` (Interface)
```kotlin
package de.ble1st.warden.domain.permission

interface AutoBlockEngine {
    fun checkAndBlock(packageName: String, permission: Permission, context: PermissionContext): BlockAction?
    fun executeBlock(action: BlockAction): Result<Unit>
    fun unblock(packageName: String, permission: Permission): Result<Unit>
    fun loadRulesFromJson(json: String): Result<Unit>
}
```

#### Regel-Engine
```kotlin
data class BlockRule(
    val appPackage: String?, // null = alle Apps
    val permission: Permission,
    val condition: BlockCondition,
    val action: BlockActionType, // WARN, TEMPORARY_BLOCK, PERMANENT_BLOCK
    val severity: Severity,
    val description: String
)

sealed class BlockCondition {
    data object Always : BlockCondition()
    data object OnlyBackground : BlockCondition()
    data object OnlyWhenAbsent : BlockCondition()
    data class And(val conditions: List<BlockCondition>) : BlockCondition()
    data class Custom(val check: (PermissionContext) -> Boolean) : BlockCondition()
}
```

#### Presence-Gate-Integration
```kotlin
fun checkAndBlock(...): BlockAction? {
    // Nur sperren, wenn Benutzer NICHT anwesend ist
    if (presenceDetector.isPresent()) {
        return null // Keine automatische Sperre
    }
    // ... Regelprüfung
}
```

### Integration
- **App Behavioral Analysis:** Anomalien können Auto-Block auslösen
- **ThreatDetectionEngine:** Bei Bedrohungen → strengere Regeln
- **SystemEventLog:** Alle Block-Aktionen werden geloggt

### Guidelines
| Guideline | Umsetzung |
|-----------|-----------|
| domain/* rein | ✅ Keine Android-Imports in domain/ |
| Fail-safe | ✅ Fällt auf Default-Regeln zurück |
| Presence-Gate | ✅ Nur bei Abwesenheit aktiv |
| Offline-first | ✅ Regeln lokal gespeichert |

---

## 💾 Feature 5: Storage Encryption Verification

### Architektur
```
domain/encryption/
├── EncryptionVerifier.kt    # Hauptkomponente
├── DeviceInfo.kt           # Geräte-Informationen
├── AppManager.kt           # App-Informationen
└── EncryptionStatus.kt     # Datenklassen
```

### Hauptkomponenten

#### `EncryptionVerifier` (Interface)
```kotlin
package de.ble1st.warden.domain.encryption

interface EncryptionVerifier {
    fun verify(): EncryptionStatus
    fun verifyApp(packageName: String): AppEncryptionStatus
    fun getRecommendations(): List<EncryptionRecommendation>
}

data class EncryptionStatus(
    val fullDiskEncryption: EncryptionState,
    val fileBasedEncryption: Map<String, EncryptionState>,
    val keyStoreIntegrity: KeyStoreState,
    val externalStorage: EncryptionState
)

enum class EncryptionState { ENABLED, DISABLED, NOT_SUPPORTED, UNKNOWN }

enum class KeyStoreState { HARDWARE_BACKED, SOFTWARE, COMPROMISED, UNKNOWN }
```

#### Prüfungen
- **FDE:** Full Disk Encryption Status
- **FBE:** File-Based Encryption für sensible Apps
- **KeyStore:** Hardware-Backed vs Software
- **External Storage:** SD-Karte, USB-OTG

#### Handlungsempfehlungen
```kotlin
data class EncryptionRecommendation(
    val type: RecommendationType, // FDE, FBE, KEYSTORE, EXTERNAL
    val title: String,
    val description: String,
    val action: ActionType, // ONE_CLICK, MANUAL, WARNING
    val severity: Severity
)
```

### Integration
- **Security Score Dashboard:** Verschlüsselungsstatus fließt in Score ein
- **Benachrichtigungen:** Handlungsempfehlungen werden angezeigt
- **Android DevicePolicyManager:** 1-Klick-Aktivierung von FDE

### Guidelines
| Guideline | Umsetzung |
|-----------|-----------|
| domain/* rein | ✅ Keine Android-Imports in domain/ |
| Fail-safe | ✅Graceful Degradation bei alten Geräten |
| Presence-Gate | ❌ Nicht nötig |
| Offline-first | ✅ Alle Prüfungen lokal |

---

## 📊 Feature 6: Security Score Dashboard

### Architektur
```
domain/score/
├── SecurityScoreCalculator.kt   # Hauptkomponente
├── ThreatDetectionScoreCalculator.kt
├── PermissionScoreCalculator.kt
├── EncryptionScoreCalculator.kt
├── NetworkSecurityScoreCalculator.kt
├── DeviceIntegrityScoreCalculator.kt
└── ScoreHistoryManager.kt
```

### Hauptkomponenten

#### `SecurityScoreCalculator` (Interface)
```kotlin
package de.ble1st.warden.domain.score

interface SecurityScoreCalculator {
    fun calculate(): SecurityScore
    fun calculateCategory(category: ScoreCategory): CategoryScore
    fun addListener(listener: (SecurityScore) -> Unit)
}

data class SecurityScore(
    val value: Int, // 0-100
    val level: SecurityLevel, // DANGEROUS, RISKY, MODERATE, SAFE
    val categoryScores: Map<ScoreCategory, CategoryScore>,
    val timestamp: Instant
)

enum class ScoreCategory {
    THREAT_DETECTION,    // 30% Gewicht
    PERMISSIONS,         // 25% Gewicht
    ENCRYPTION,         // 20% Gewicht
    NETWORK_SECURITY,   // 15% Gewicht
    DEVICE_INTEGRITY    // 10% Gewicht
}
```

#### Kategorie-Calculators
- **ThreatDetectionScoreCalculator:** Basierend auf aktiven Bedrohungen
- **PermissionScoreCalculator:** Basierend auf Permission-Nutzung und Blocks
- **EncryptionScoreCalculator:** Basierend auf Verschlüsselungsstatus
- **NetworkSecurityScoreCalculator:** Basierend auf Netzwerk-Sperre und IMSI-Catcher
- **DeviceIntegrityScoreCalculator:** Basierend auf Root-Status, Bootloader, Verified Boot

### UI-Komponenten (app Layer)
- **SecurityScoreCard:** Kreis-Diagramm mit Score
- **CategoryScoresGrid:** 5 Kacheln für jede Kategorie
- **ScoreHistoryChart:** Historischer Verlauf (30 Tage)
- **RecommendationsList:** Handlungsempfehlungen bei Score < 80

### Integration
- **Alle Features:** Jedes Feature trägt zum Score bei
- **Benachrichtigungen:** Warnung bei Score < 50
- **SystemEventLog:** Score-Änderungen werden geloggt
- **Quick-Action Widgets:** Score-Widget zeigt aktuellen Score an

### Guidelines
| Guideline | Umsetzung |
|-----------|-----------|
| domain/* rein | ✅ Score-Berechnung in domain/ |
| Fail-safe | ✅ Kategorie-Score = 0 bei Fehlern |
| Presence-Gate | ❌ Nicht nötig |
| Offline-first | ✅ Berechnung komplett lokal |

---

## ⚡ Feature 7: Quick-Action Widgets

### Architektur
```
app/widget/
├── WardenWidgetProvider.kt    # Basis-Klasse
├── QuickLockWidget.kt         # 1-Tap Sperre
├── NetworkLockWidget.kt       # Netzwerk-Sperre
├── ThreatScanWidget.kt        # Bedrohungs-Scan
├── SecurityScoreWidget.kt     # Score-Anzeige
└── WardenWidgetHelper.kt      # Helper-Methoden
```

### Widget-Typen

| Widget | Größe | Aktion | Beschreibung |
|--------|-------|--------|--------------|
| Schnell-Sperre | 4x1 | Toggle Quick Lock | Sperrt alle sensiblen Permissions |
| Netzwerk-Sperre | 4x1 | Toggle Barbican | Aktiviert Netzwerk-Sperre |
| Bedrohungs-Scan | 2x2 | Start Scan | Manueller Threat-Scan |
| Sicherheits-Score | 2x2 | Show Dashboard | Zeigt aktuellen Score |

### Fail-safe
```kotlin
override fun onUpdate(...) {
    try {
        // Normaler Update-Code
    } catch (e: Exception) {
        // Zeige Standard-View mit Warden-Logo
        val failSafeViews = RemoteViews(..., R.layout.widget_failsafe)
        failSafeViews.setTextViewText(R.id.text, "Warden")
        appWidgetManager.updateAppWidget(appWidgetIds, failSafeViews)
        SystemEventLog.logError("Widget update failed", e)
    }
}
```

### Integration
- **Alle Features:** Widgets zeigen Status/Steuerung für alle Features
- **Benachrichtigungen:** Widgets können Benachrichtigungen auslösen
- **SystemEventLog:** Widget-Interaktionen werden geloggt

### Guidelines
| Guideline | Umsetzung |
|-----------|-----------|
| domain/* rein | ❌ Widgets sind app Layer |
| Fail-safe | ✅ Standard-View bei Crash |
| Presence-Gate | ❌ Nicht nötig |
| Offline-first | ✅ Widgets funktionieren ohne Internet |

---

## ✅ Zusammenfassung: Alle Features

| Feature | domain/* rein | Fail-safe | Presence-Gate | Offline-first |
|---------|---------------|-----------|---------------|---------------|
| Barbican Integration | ✅ | ✅ | ✅ | ✅ |
| IMSI-Catcher Detection | ✅ | ✅ | ❌ | ✅ |
| App Behavioral Analysis | ✅ | ✅ | ❌ | ✅ |
| Permission Auto-Block | ✅ | ✅ | ✅ | ✅ |
| Storage Encryption Verification | ✅ | ✅ | ❌ | ✅ |
| Security Score Dashboard | ✅ | ✅ | ❌ | ✅ |
| Quick-Action Widgets | ❌ | ✅ | ❌ | ✅ |

---

## 📚 Referenzen

- [Haupt-Umsetzungsplan](./umsetzungsplan-7-features.md)
- [Barbican-Kernfehler-Analyse](./phase-0-barbican-analyse.md)
- [Barbican-Design-Dokument](./phase-0-design-barbican.md)
- [CLAUDE.md (Design-Guidelines)](../CLAUDE.md)
- [architektur-review-2026-08.md](./architektur-review-2026-08.md)

---

## 🔄 Version History

| Version | Datum | Änderungen | Autor |
|---------|-------|-----------|-------|
| 1.0 | 2026-08-29 | Erstellung der Design-Dokumente | Mistral Vibe |
