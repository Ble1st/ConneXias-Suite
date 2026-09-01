// Erzeugt Kotlin-Bindings aus der kompilierten Library (Library-Mode, kein UDL-File — die
// #[uniffi::export]-Annotationen in src/ sind die alleinige Quelle der Wahrheit für den
// FFI-Contract). Aufruf z. B.:
//   cargo run --bin uniffi-bindgen --features uniffi-cli -- generate \
//       --library target/.../libconnexias_engine.so --language kotlin --out-dir <dir>

fn main() {
    uniffi::uniffi_bindgen_main()
}
