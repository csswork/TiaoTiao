# Third-party notices

The root [MIT license](LICENSE) covers TiaoTiao's original code and documentation. Third-party components retain their own copyright notices, licenses, and terms; they are not relicensed under MIT.

## Components with retained license files

| Component | Source and version | License and retained files |
| --- | --- | --- |
| QingIcon Line icon subset | [asorn/qingicon-open](https://github.com/asorn/qingicon-open), revision `316c8881784d8620fff5a764f84ec7be41ef14c1` | [Apache-2.0](third_party/qingicon/LICENSE), [upstream NOTICE](third_party/qingicon/NOTICE), [provenance and conversion details](third_party/qingicon/README.md) |
| LibADB Android | [MuntashirAkon/libadb-android](https://github.com/MuntashirAkon/libadb-android/tree/3.1.1), version `3.1.1` | Apache-2.0 option of the upstream dual license; [retained license](third_party/libadb/LICENSE), [integration notes](third_party/libadb/README.md) |

QingIcon's original SVGs are retained under `third_party/qingicon/svg/`. The generated Android VectorDrawables preserve their geometry and use a tintable stroke. The upstream license and notice are also bundled in the APK under `assets/third_party/qingicon/`.

LibADB Android is resolved as a Gradle dependency for local wireless ADB pairing and TLS connections. Its retained license is also bundled in the APK under `assets/licenses/libadb/`.

## Dependencies and SDKs

Other libraries, SDKs, plugins, and build tools retain their upstream licenses and terms. Dependency declarations and versions are recorded in:

- [Android application dependencies](app/build.gradle.kts)
- [Core dependencies](core/build.gradle.kts)
- [Emulator fixture dependencies](test-app/build.gradle.kts)
- [Build plugins](build.gradle.kts)
- [Gradle Wrapper configuration](gradle/wrapper/gradle-wrapper.properties)

These include AndroidX / Jetpack Compose, Kotlin libraries, OkHttp, Conscrypt, Bouncy Castle, and Google's bundled ML Kit text-recognition SDK. The project's MIT license does not replace Google SDK terms or the terms of optional cloud services. API accounts and credentials are not distributed with this project.
