# Warden

Android app (`de.ble1st.warden`), single Gradle module.

## Build

```
./gradlew build
```

Debug APK:

```
./gradlew :app:assembleDebug
```

## Test

```
./gradlew test
```

Instrumented tests need an attached device or emulator:

```
./gradlew :app:connectedDebugAndroidTest
```

## Native component

`rust/engine` is a separate Cargo workspace and is not built by Gradle. See
`rust/build-android.sh` for how the bundled native libraries are produced.

## License

See [LICENSE](../LICENSE).

## Security

See [SECURITY.md](SECURITY.md).
