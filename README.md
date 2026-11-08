# Ghost Drive & Nullvalon Tri-Fusion Observatory

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Status](https://img.shields.io/badge/Status-Work_in_Progress_(Research_Prototype)-amber.svg)](#status-cryptographic--legal-disclaimers)
[![Platform](https://img.shields.io/badge/Platform-Android_14%2B_%7C_Wear_OS_%7C_ESP32--C3-teal.svg)](#tested-hardware)

**Ghost Drive** is an experimental, privacy-first native Android journey journal, **Tri-Fusion `SHA3-512` physical entropy observatory**, and **on-device + Tailnet/LAN AI voice co-pilot (`A-007 Merlin`)** developed by **Jupiter Labs**.

> ⚠️ **Work-In-Progress Notice**: This repository is an active **work-in-progress (WIP)** engineering and research prototype. It is **not** production-grade software and should not be deployed in safety-critical automotive, medical, or high-assurance cryptographic environments.

---

## Tested Hardware

This codebase has been built and hardware-tested across the following devices:

- **Smartphones (Cross-Compatible Flagship Builds)**:
  - **Samsung Galaxy S25 Ultra (`SM-S938U`)** — Qualcomm Snapdragon 8 Elite (`Hexagon NPU` + `Oryon ARMv9.2 SIMD`)
  - **Google Pixel 11 Pro XL** — Google Tensor G6 (`Tensor TPU` + `ARMv9 SIMD`)
- **Wear OS Smartwatch**:
  - **Samsung Galaxy Watch 5 (`SM-R910`)** — Optical PPG Heart Rate / IBI, SpO2, 6-Axis Wrist IMU, and Barometer
- **Vehicle Microcontroller & Sensor Node ("Nullvalon")**:
  - **ESP32-C3 (4MB Flash, RISC-V)** + **HLK-LD2410C 24 GHz mmWave Radar** (256,000-baud UART)

---

## What Ghost Drive Does

### 1. Ignition-Gated Journey Journal (`:app`)
- **No Continuous GPS or Sensor Harvesting While Parked**: Phone GPS and continuous phone MEMS sensor harvesting remain disabled while disconnected from the vehicle. (Note: background BLE advertisement scanning and Android foreground service supervision still consume baseline system power when monitoring is armed.)
- **BLE Challenge-Response Ignition Gate**: Tracking starts only after the phone discovers the vehicle's `NULLVALON` BLE GATT peripheral (`0xC7A8`) and verifies its 16-byte `HMAC-SHA3-256/16` challenge-response proof (`NULLVALON-auth-v1\0`). When the vehicle powers down (**45-second missing-telemetry watchdog**), Ghost Drive locks the final GPS fix, resolves the destination against saved user bases or local Mapsforge POI data, and archives the trip locally.
- **Offline Mapsforge Vector Maps & Fullscreen Cockpit HUD**: Renders regional `.map` vector files offline with **Day**, **Night**, and **Matrix** HUD shaders, route polylines, custom recognition radii, and a **Fullscreen Cockpit HUD** (`82dp` speedometer, cardinal compass, trip distance, live Watch 5 BPM/SpO2, and floating voice controls).

### 2. ESP32-C3 Vehicle Node (`esp-firmware-companion` — "Nullvalon")
- Written in **Rust** (`riscv32imc-esp-espidf`, `esp-idf-sys` v6.0.3, NimBLE, and Embassy-style non-blocking embedded task/channel patterns for concurrent radar UART, Wi-Fi passive dwell, thermal polling, and BLE GATT backpressure).
- Collects **7 separately collected 8,192-byte entropy-source windows** (note: separate collection does not imply statistical independence, particularly across the three channels derived from the same physical HLK-LD2410C radar module):
  1. `HardwareRng` — ESP32-C3 SAR ADC / RF thermal noise TRNG
  2. `RadarMoving` — HLK-LD2410C 24 GHz Doppler moving-target energy gates
  3. `RadarStationary` — Micro-motion / breathing stationary energy gates
  4. `RadarDistance` — Millimeter-wave phase/distance variations
  5. `Wifi` — Passive 2.4 GHz channel RSSI, counts, and scan timing jitter (retains zero SSIDs or MAC addresses)
  6. `Timing` — RISC-V cycle counter & RTOS scheduling jitter
  7. `Thermal` — Silicon temperature sensor micro-fluctuations
- Computes per-window diagnostic statistics (Shannon entropy, histogram min-entropy estimates, 1st-order Markov, $\chi^2$, Repetition Count Test, and Adaptive Proportion Test), conditions accepted windows with domain-separated `SHA3-512`, and streams `Postcard`-encoded frames over 20-byte fragmented BLE notifications authenticated with a truncated 16-byte **`HMAC-SHA3-256/16` message authentication code (MAC)** under domain `NULLVALON-telemetry-v1\0`.

### 3. Wear OS Biometric & Inertial Node (`:wear` — "GhostWatch 5")
- Runs on the **Samsung Galaxy Watch 5**, harvesting 6 physical channels: **PPG Heart Rate (`BPM`)**, **Inter-Beat Interval (`IBI ms`)**, **Blood Oxygen (`SpO2 %`)**, **6-Axis Wrist IMU (`G-force`)**, **Barometric Pressure (`hPa`)**, and **Steps**.
- Conditions 512-bit `SHA3-512` biometric blocks (`GHOSTDRIVE-WATCH5-v1\0`) into a local SQLite WAL queue (`ghostwatch5.db`) with optional low-power standby micro-bursts, syncing to the phone over the **Wearable Data Layer (`/ghostdrive/watch_entropy`)** and **BLE GATT (`0xC7A9`)** for **Tri-Fusion (`Car + Watch + Phone`)** sponge mixing.

### 4. AI Inference Mesh, `"Hey Merlin"` Voice Commands & Co-Pilot (`A-007 Merlin`)
- **Character (`A-007 Merlin` — "The Wandering Compass")**: An upbeat, affectionate android pathfinder co-pilot who narrates trip starts/stops, answers live telemetry sitreps, and executes hands-free voice commands.
- **Selectable Multi-Route Brain (`Settings → ✨ Merlin & Look`)**:
  - **Onboard Phone NPU**: Android AICore `Gemini Nano` via ML Kit GenAI (`Hexagon NPU` on S25 Ultra / `Tensor TPU` on Pixel 11 Pro XL).
  - **Phone Local GGUF**: Local `llama-server` loopback (`http://127.0.0.1:18089`) scanning `/sdcard/Download/GhostDrive-Models` for `.gguf` weights.
  - **User-Configurable Tailnet / LAN LLM Mesh**: Add your own Tailscale (`100.64.x.x`) or LAN (`192.168.x.x`) **OpenAI-compatible** (`llama.cpp`, `vLLM`, `LM Studio`) or **Ollama** (`/api/chat`) endpoints directly in Settings, probe live node latency, and test individual nodes with 1-tap voice output.
  - **Gemini Studio TTS (`Aoede`) & Offline System TTS**: Supports expressive cloud voice synthesis with local SHA-256 WAV caching or offline Android TTS.
- **Hands-Free `"Hey Merlin"` Wake-Word & Voice Commands**: Toggleable on-device spotter supporting commands such as *"Hey Merlin, sitrep"*, *"Hey Merlin, roll a d20"*, *"Hey Merlin, save this place as [Name]"*, *"Hey Merlin, open cockpit map"*, *"Hey Merlin, score phone"*, *"Hey Merlin, heart rate"*, *"Hey Merlin, matrix mode"*, plus open conversational queries.

### 5. Standalone Operation & Optional Private Server Sync
- **Standalone by Default**: Ghost Drive stores all journeys, places, telemetry, and conditioned `SHA3-512` entropy blocks locally in app-private SQLite on your phone and supports full JSON/GPX archive import & export. **No external server is required.**
- **Optional Private Sync**: If you wish to sync archives or stream conditioned entropy across devices over Tailscale/Headscale or a local LAN, you can enable forwarding in **Settings → `☁️ Sync & Data`** and point it to your own private receiver implementing the JSON contract documented in [`docs/DATA-CONTRACT.md`](docs/DATA-CONTRACT.md).

---

## Repository Structure

- `app/` — Android phone app (`Samsung Galaxy S25 Ultra` ⇄ `Google Pixel 11 Pro XL`).
- `wear/` — Wear OS companion app & Tile (`Samsung Galaxy Watch 5`).
- `esp-firmware-companion/` — Rust + ESP-IDF v6.0.3 firmware for the `ESP32-C3` + `HLK-LD2410C` vehicle node.
- `docs/` — Wire protocol (`PROTOCOL.md`), optional sync contract (`DATA-CONTRACT.md`), hardware validation record (`VALIDATION.md`), and third-party attributions (`THIRD-PARTY.md`).

---

## Build & Installation

### Prerequisites
- **Android Studio** with **Android SDK 35** (`build-tools 35.0.0`) and **JDK 17**.
- *(Optional, for MCU firmware)* **Rust ESP toolchain** (`espup`, `ldproxy`, `espflash`) targeting `riscv32imc-esp-espidf`.

### Build Phone (`:app`) & Watch (`:wear`) APKs
```powershell
# Windows PowerShell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :wear:assembleDebug
```
```bash
# Linux / macOS
./gradlew :app:assembleDebug :app:testDebugUnitTest :wear:assembleDebug
```
- **Phone APK**: `app/build/outputs/apk/debug/app-debug.apk`
- **Wear OS APK**: `wear/build/outputs/apk/debug/wear-debug.apk`

---

## Status, Cryptographic & Legal Disclaimers

1. **Not Formally NIST Validated or Certified**:
   The physical noise sources (ESP32-C3 SAR ADC/RF, HLK-LD2410C 24 GHz radar, Wi-Fi RSSI, silicon thermal, Galaxy Watch 5 PPG/IMU, and smartphone MEMS sensors), statistical estimators ($H_{\text{shannon}}$, $H_{\text{min}}$, Markov, $\chi^2$, Repetition Count Test, Adaptive Proportion Test), and `SHA3-512` sponge conditioning pipelines in this project are **experimental research and observability implementations**. They have **not** been formally tested, validated, or certified by NIST, CMVP, or any accredited laboratory under **NIST SP 800-90A/B/C** or **FIPS 140-3**.
2. **Statistical Independence & Conditioning Limits**:
   The 7 ESP32-C3 windows, 6 Watch 5 channels, and 7 Phone channels are **separately collected** sensor windows; statistical independence across channels (especially channels sharing physical hardware such as `RadarMoving`, `RadarStationary`, and `RadarDistance` on the HLK-LD2410C, or multi-axis MEMS sensors) is **not** assumed or proven. Hashing or mixing sensor data through `SHA3-512` / `HMAC-SHA3-256` produces uniform-looking output digests but **does not create entropy**.
3. **Symmetric HMAC Authentication vs. Digital Signatures & Development-Only Zero-Key Mode**:
   - Frame integrity and challenge-response verification use symmetric **`HMAC-SHA3-256/16` message authentication codes (MACs)**, which provide symmetric message authentication between peers sharing the secret key—**not** asymmetric non-repudiable digital signatures.
   - **Development-Only All-Zero Key Fallback**: For out-of-the-box bench and simulator testing without hardware provisioning, the codebase includes a **development-only fallback that accepts an all-zero 32-byte key (`0x00 * 32`)**. This fallback provides **zero security or device identity guarantees** (any BLE peer can compute valid tags with the public zero key). Before any real-world deployment, you must provision a unique 256-bit secret via `esp-firmware-companion/tools/provision-key.sh` and disable the zero-key fallback.
4. **Automotive Safety**:
   Do not interact with settings, maps, or diagnostics while operating a motor vehicle. Always obey traffic laws and keep your attention on the road.

---

## License

Copyright 2026 **Jupiter Labs**.

Licensed under the **Apache License, Version 2.0** (the "License"); you may not use this project except in compliance with the License. A copy of the License is provided in [`LICENSE`](LICENSE) or at:

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an **"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND**, either express or implied. See [`LICENSE`](LICENSE) and [`docs/THIRD-PARTY.md`](docs/THIRD-PARTY.md) for governing permissions, limitations, and third-party library notices (including Mapsforge LGPLv3 and OpenStreetMap ODbL attributions).
