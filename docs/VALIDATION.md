# Ghost Drive — Build & Hardware Validation Record

## 1. Automated Build & Unit Test Verification (November 8, 2026)

| Check | Result |
| --- | --- |
| Debug Android Phone build | PASS — `:app:assembleDebug` (0 warnings, 0 errors) |
| Debug Wear OS Watch build | PASS — `:wear:assembleDebug` (0 warnings, 0 errors) |
| Android JVM unit tests | PASS — `:app:testDebugUnitTest` (all suites passing, zero failures) |
| APK signature | PASS — Android APK Signature Scheme v2 |
| Package metadata | `com.jupiterlabs.ghostdrive`, minimum Android 12 / API 31, target API 35 |
| Synthetic protocol fixtures | All eight `Postcard` + `HMAC-SHA3-256/16` message variants verified |

Build verification uses JDK 17, Gradle 8.11.1, Android Gradle Plugin 8.9.2, SDK 35, and build tools 35.0.0.

### Unit Test Suites (`:app:testDebugUnitTest`)

- **Protocol Tests (`ProtocolTest.kt`)**: All 8 Nullvalon message variants, reversed fragment ordering, source enum indices, signed RSSI values, challenge-proof/HMAC tag verification, tampering detection, wrong-key rejection, sequence mismatch, conflicting fragments, malformed bounds, integer overflow, and trailing schema bytes.
- **Trip Engine Tests (`TripEngineTest.kt`)**: No continuous GPS recording while disconnected, stale/imprecise/invalid fixes, monotonic duration despite wall-clock changes, GPS gaps, parking inference, saved-base priority, invalid speed, drift without speed measurements, and configured base radii.
- **Heuristic Estimator & Sponge Tests (`Sp80090bTest.kt`)**: Shannon entropy, Most Common Value ($H_{\text{min}}$), 1st-order Markov, Repetition Count Test (RCT), Adaptive Proportion Test (APT), and Tri-Fusion `SHA3-512` avalanche properties. *(Note: These are diagnostic estimators, not a formal NIST SP 800-90B certification suite.)*
- **Private Endpoint & Transport Tests (`PrivateEndpointTest.kt`, `PrivateHttpTest.kt`)**: Tailnet IPv4/IPv6 bounds, rejection of embedded credentials or unsupported URL forms, socket-factory isolation, and redirect blocking.

---

## 2. Physical Hardware Validation Log (October – November 2026)

Physical integration and field validation were completed across the target devices between October 14, 2026 and November 8, 2026:

### A. Smartphones (`Samsung Galaxy S25 Ultra` & `Google Pixel 11 Pro XL`)
- **Validated Devices**:
  - **Samsung Galaxy S25 Ultra (`SM-S938U`)** — Qualcomm Snapdragon 8 Elite (`Hexagon NPU` + `Oryon ARMv9.2 SIMD`)
  - **Google Pixel 11 Pro XL** — Google Tensor G6 (`Tensor TPU` + `ARMv9 SIMD`)
- **Verified Behaviors**:
  - Cross-compatible hardware profile detection (`DevicePlatform.kt`), UI rendering across all 6 tabs and the Fullscreen Cockpit HUD, and offline Mapsforge `.map` vector rendering with **Day**, **Night**, and **Matrix** shaders.
  - Ignition-gated GPS and phone sensor lifecycle: verified that continuous phone GPS and MEMS sensor harvesting do **not** run while parked/disconnected, start only after the `NULLVALON` BLE challenge-response completes, and stop when the **45-second missing-telemetry watchdog** expires after vehicle power-off.
  - On-device `"Hey Merlin"` voice command spotter (`MerlinVoiceCommander.kt`), multi-route LLM brain selector (`MerlinOnboardEngine.kt`), and Gemini Studio `Aoede` / offline system TTS playback.

### B. Wear OS Companion (`Samsung Galaxy Watch 5` — `SM-R910`)
- **Verified Behaviors**:
  - Live collection of 6 wrist channels (PPG Heart Rate `BPM`, Inter-Beat Interval `IBI ms`, Blood Oxygen `SpO2 %`, 6-Axis Wrist IMU `G-force`, Barometric Pressure `hPa`, and Steps) in `WatchHarvestService.kt`.
  - Offline SQLite WAL staging queue (`ghostwatch5.db`) and dual-transport delivery to the phone over the **Wearable Data Layer (`/ghostdrive/watch_entropy`)** and **BLE GATT (`0xC7A9`)**, fusing queued watch blocks into the Tri-Fusion `SHA3-512` sponge upon vehicle connection.

### C. Vehicle Microcontroller & Radar Node (`ESP32-C3` + `HLK-LD2410C` — "Nullvalon")
- **Verified Behaviors**:
  - Cross-compiled `esp-firmware-companion` (`riscv32imc-esp-espidf`, ESP-IDF v6.0.3, NimBLE) and flashed to the 4MB **ESP32-C3** wired to an **HLK-LD2410C** 24 GHz mmWave radar (`256000` baud UART).
  - Verified BLE GATT advertising (`NULLVALON`, service `c7a80000-0dab-4d03-9a0b-a000a0000001`), CCCD subscription serialization (proof characteristic subscribed prior to telemetry characteristic), 16-byte challenge nonce write, and 16-byte `HMAC-SHA3-256/16` proof verification (`NULLVALON-auth-v1\0`).
  - Verified 20-byte BLE packet fragmentation, reassembly, sequence gap tracking, and `HMAC-SHA3-256/16` message authentication (`NULLVALON-telemetry-v1\0`) across all **7 separately collected 8,192-byte entropy-source windows** (`HardwareRng`, `RadarMoving`, `RadarStationary`, `RadarDistance`, `Wifi`, `Timing`, `Thermal`).

---

## 3. Security & Cryptographic Caveats for Reviewers

1. **Development-Only All-Zero Key Fallback**:
   To allow out-of-the-box testing before per-device key provisioning, `NullvalonClient.kt` and `esp-firmware-companion` include a development-only fallback that accepts an all-zero 32-byte key (`0x00 * 32`). **This mode is strictly for development/interoperability testing and provides no protection against BLE spoofing.** Provision a unique 256-bit key (`esp-firmware-companion/tools/provision-key.sh`) and remove the zero-key fallback before any production or adversarial use.
2. **Symmetric HMAC vs. Digital Signatures**:
   All frame tags and challenge proofs use symmetric `HMAC-SHA3-256` truncated to 16 bytes (`128 bits`). This provides symmetric message authentication and integrity between endpoints sharing the secret key, **not** asymmetric non-repudiable digital signatures.
3. **Separate Collection vs. Statistical Independence**:
   The 7 ESP32-C3 windows, 6 Watch 5 channels, and 7 Phone channels are separately collected windows, **not** proven statistically independent noise sources (for example, `RadarMoving`, `RadarStationary`, and `RadarDistance` share the same HLK-LD2410C 24 GHz transceiver and physical environment). Neither the in-app estimators nor the `SHA3-512` conditioners constitute formal **NIST SP 800-90B** or **FIPS 140-3** validation.

---

## 4. Repeat Build & Test Verification Locally

From the repository root:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :wear:assembleDebug
```
