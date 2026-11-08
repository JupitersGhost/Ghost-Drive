# Nullvalon ESP32-C3 Vehicle Node (`esp-firmware-companion`)

Rust embedded companion firmware targeting a **4MB ESP32-C3** (RISC-V `riscv32imc-esp-espidf`) with `esp-idf-sys` v6.0.3 / Embassy-style non-blocking task/channel concurrency, NimBLE GATT peripheral (`0xC7A8`), UART **HLK-LD2410C** 24 GHz mmWave radar, passive Wi-Fi channel scanning, internal silicon temperature sensing, hardware RNG, timing jitter measurements, per-source health diagnostics, and `SHA3-512` source/pool conditioning.

> **Work-In-Progress & Non-Certification Notice**: This firmware is an experimental research and telemetry prototype developed by **Jupiter Labs**. It is **not** a formally validated or certified NIST SP 800-90B / FIPS 140-3 cryptographic random number generator.

## Hardware Setup

- **MCU**: ESP32-C3 (USB Serial/JTAG, 4MB flash)
- **Radar Sensor**: HLK-LD2410C 24 GHz mmWave presence/distance radar powered by regulated 5V (3.3V UART logic levels, common GND).
- **Default UART Pinout**: ESP32-C3 `GPIO5` (TX) -> Radar RX, ESP32-C3 `GPIO4` (RX) <- Radar TX (`256000` baud).
- Without the radar connected, the radar sources report `Absent` rather than simulated values. Build with `cargo build --no-default-features` to omit UART radar polling while keeping the wire protocol intact.

## Build & Flash

```bash
cd esp-firmware-companion
./tools/provision-key.sh
. "$HOME/export-esp.sh"
cargo build --release
espflash flash -p /dev/ttyACM0 target/riscv32imc-esp-espidf/release/nullvalon --monitor
```

## Seven Separately Collected Entropy-Source Windows (8,192 Bytes Each)

`HardwareRng`, `RadarMoving`, `RadarStationary`, `RadarDistance`, `Wifi`, `Timing`, and `Thermal`.

- **Separate Collection vs. Independence**: These seven 8,192-byte buffers are collected and evaluated in separate windows so that high-rate sources do not mask slow or degraded sensors. However, separate collection does **not** imply statistical independence—in particular, `RadarMoving`, `RadarStationary`, and `RadarDistance` are extracted from the same physical HLK-LD2410C 24 GHz radar transceiver and environmental reflections.
- Each window computes diagnostic statistics (raw/conditioned byte-histogram Shannon entropy and min-entropy estimates, 1st-order binary Markov, $\chi^2$, Repetition Count Test, and Adaptive Proportion Test) before domain-separated `SHA3-512` conditioning. Credited cryptographic bits remain `0` pending formal source characterization.

## BLE GATT Protocol (`0xC7A8`)

- **Advertising Name**: `NULLVALON`
- **Service UUID**: `c7a80000-0dab-4d03-9a0b-a000a0000001`
  - `...0001`: Encrypted Read / Notify compact 20-byte fragmented `Postcard` telemetry authenticated with a truncated 16-byte `HMAC-SHA3-256/16` message authentication code (`NULLVALON-telemetry-v1\0`).
  - `...0002`: Encrypted Write 16-byte challenge nonce from the phone.
  - `...0003`: Encrypted Read / Notify 16-byte `HMAC-SHA3-256/16` challenge proof (`NULLVALON-auth-v1\0`).

> **Development-Only Zero-Key Warning**: Always run `./tools/provision-key.sh` to provision a unique 32-byte secret key before real deployment. The unprovisioned all-zero key fallback is strictly for bench/development interoperability testing and provides no security against BLE spoofing. Symmetric HMAC authenticates frames between holders of the shared key and is not an asymmetric digital signature.
