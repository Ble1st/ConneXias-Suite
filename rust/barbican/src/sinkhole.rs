// ⏸ PAUSIERT (2026-08-27): "Netz-Sperre" ist vorübergehend deaktiviert — Live-Test auf dem
// physischen Testgerät fand nach mehreren echten Bugfixes (siehe Commit 7252396 auf App-Seite und
// das Memo warden-netzsperre-feature-2026-08-27) einen weiterhin ungeklärten Kernfehler: die
// DNS-Blockliste/NAT-Relay verarbeitet auf einem frisch aufgebauten Tunnel keinen Traffic mehr.
// Diese Crate ist deshalb aus rust/Cargo.tomls `members` entfernt — ein `cargo build`/`cargo test`
// direkt in diesem Verzeichnis schlägt bewusst mit einem Workspace-Fehler fehl (nicht in
// `members`, nicht `exclude`d), statt still falsch zu bauen. Zum Reaktivieren: "barbican" wieder
// zu rust/Cargo.tomls `members` hinzufügen, diesen Banner-Kommentar aus jeder Datei entfernen,
// Kernfehler zuerst klären.

//! Portiert unverändert aus dem ConneXias-Framework-Quellprojekt (`rust/barbican/src/sinkhole.rs`,
//! Milestone I.1) — reiner Lese-und-Verwerfen-Pfad. Seit "Netz-Sperre" (2026-08-27) nicht mehr der
//! einzige Modus (s. `nat.rs`/`engine.rs` für den echten DNS-gefilterten NAT-Pfad), bleibt aber als
//! einfacher, unabhängig testbarer Baustein und Fallback erhalten (Build-Reihenfolge im Plan:
//! erst dieser einfache Pfad end-to-end, dann NAT/DNS obendrauf).

use std::fmt;
use std::fs::File;
use std::io::{self, Read};
use std::mem::ManuallyDrop;
use std::os::fd::{FromRawFd, RawFd};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};

static SINKHOLE_RUNNING: AtomicBool = AtomicBool::new(false);

struct SinkholeWorker {
    running: Arc<AtomicBool>,
    handle: Option<JoinHandle<()>>,
}

impl SinkholeWorker {
    fn stop(&mut self) {
        self.running.store(false, Ordering::SeqCst);
        if let Some(handle) = self.handle.take() {
            let _ = handle.join();
        }
    }
}

static WORKER: Mutex<Option<SinkholeWorker>> = Mutex::new(None);

#[derive(Debug, uniffi::Error)]
pub enum SinkholeError {
    AlreadyRunning,
    InvalidTunFd,
    Io { detail: String },
}

impl fmt::Display for SinkholeError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            SinkholeError::AlreadyRunning => write!(f, "sinkhole worker already running"),
            SinkholeError::InvalidTunFd => write!(f, "invalid tun file descriptor"),
            SinkholeError::Io { detail } => write!(f, "io error: {detail}"),
        }
    }
}

/// Start reading from the TUN device and discarding all packets (SINKHOLE mode).
#[uniffi::export]
pub fn start_sinkhole(tun_fd: i32) -> Result<(), SinkholeError> {
    if tun_fd < 0 {
        return Err(SinkholeError::InvalidTunFd);
    }

    let mut guard = WORKER.lock().map_err(|_| SinkholeError::AlreadyRunning)?;
    if guard.is_some() {
        return Err(SinkholeError::AlreadyRunning);
    }

    let worker_flag = Arc::new(AtomicBool::new(true));
    SINKHOLE_RUNNING.store(true, Ordering::SeqCst);

    let handle = thread::Builder::new()
        .name("connexias-barbican-sinkhole".into())
        .spawn({
            let worker_flag = Arc::clone(&worker_flag);
            move || {
                // SAFETY: fd is borrowed from Kotlin's ParcelFileDescriptor — never close here
                // (Android fdsan aborts if native code closes a PFD-owned fd).
                let file = unsafe { File::from_raw_fd(tun_fd as RawFd) };
                let mut file = ManuallyDrop::new(file);
                let mut buffer = [0u8; 32767];
                while worker_flag.load(Ordering::SeqCst) {
                    match file.read(&mut buffer) {
                        Ok(0) => continue,
                        Ok(_) => {}
                        Err(e) if e.kind() == io::ErrorKind::Interrupted => continue,
                        Err(_) => break,
                    }
                }
                SINKHOLE_RUNNING.store(false, Ordering::SeqCst);
            }
        })
        .map_err(|e| SinkholeError::Io {
            detail: e.to_string(),
        })?;

    *guard = Some(SinkholeWorker {
        running: worker_flag,
        handle: Some(handle),
    });
    Ok(())
}

#[uniffi::export]
pub fn stop_sinkhole() {
    if let Ok(mut guard) = WORKER.lock()
        && let Some(mut worker) = guard.take()
    {
        worker.stop();
    }
    SINKHOLE_RUNNING.store(false, Ordering::SeqCst);
}

#[uniffi::export]
pub fn is_sinkhole_running() -> bool {
    SINKHOLE_RUNNING.load(Ordering::SeqCst)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_negative_fd() {
        assert!(matches!(start_sinkhole(-1), Err(SinkholeError::InvalidTunFd)));
    }

    #[test]
    fn is_not_running_initially() {
        stop_sinkhole();
        assert!(!is_sinkhole_running());
    }
}
