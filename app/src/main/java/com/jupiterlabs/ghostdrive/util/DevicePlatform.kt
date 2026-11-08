package com.jupiterlabs.ghostdrive.util

import android.os.Build

/**
 * Cross-platform hardware awareness layer for Ghost Drive + Mimi-AI Onboard NPU integration.
 * Dynamically detects Samsung Galaxy S25 Ultra (Snapdragon 8 Elite • Hexagon NPU) and
 * Google Pixel 11 Pro XL / Pixel series (Google Tensor TPU) at runtime while keeping all
 * persisted database and archive sync keys 100% cross-compatible between both phones.
 */
object DevicePlatform {

    enum class Kind {
        S25_ULTRA,
        SAMSUNG_GALAXY,
        GOOGLE_PIXEL_11_PRO_XL,
        GOOGLE_PIXEL,
        GENERIC_ANDROID,
    }

    val kind: Kind by lazy {
        val manufacturer = (Build.MANUFACTURER ?: "").uppercase()
        val brand = (Build.BRAND ?: "").uppercase()
        val model = (Build.MODEL ?: "").uppercase()
        val device = (Build.DEVICE ?: "").uppercase()
        val product = (Build.PRODUCT ?: "").uppercase()

        val isSamsung = manufacturer.contains("SAMSUNG") || brand.contains("SAMSUNG")
        val isPixel = manufacturer.contains("GOOGLE") || brand.contains("GOOGLE") || model.contains("PIXEL")

        when {
            // Samsung Galaxy S25 Ultra (SM-S938U, SM-S938U1, SM-S938B, SM-S938N, SM-S9380, pa3q / e3q)
            isSamsung && (
                model.contains("S938") ||
                model.contains("S25 ULTRA") ||
                device.startsWith("PA3Q") ||
                device.startsWith("E3Q") ||
                product.startsWith("PA3Q")
            ) -> Kind.S25_ULTRA

            isSamsung -> Kind.SAMSUNG_GALAXY

            // Google Pixel 11 Pro XL (and explicit Pixel 11 / Pro XL identifiers)
            isPixel && (
                model.contains("PIXEL 11 PRO XL") ||
                model.contains("PIXEL 11") ||
                device.contains("11PROXL") ||
                product.contains("11PROXL")
            ) -> Kind.GOOGLE_PIXEL_11_PRO_XL

            isPixel -> Kind.GOOGLE_PIXEL

            else -> Kind.GENERIC_ANDROID
        }
    }

    val isSamsung: Boolean
        get() = kind == Kind.S25_ULTRA || kind == Kind.SAMSUNG_GALAXY

    val isPixel: Boolean
        get() = kind == Kind.GOOGLE_PIXEL_11_PRO_XL || kind == Kind.GOOGLE_PIXEL

    /** Human-readable device edition badge shown in Home & Merlin Settings */
    val editionBannerTitle: String
        get() = when (kind) {
            Kind.S25_ULTRA -> "Samsung Galaxy S25 Ultra Edition"
            Kind.SAMSUNG_GALAXY -> "Samsung Galaxy ${Build.MODEL ?: "Edition"}"
            Kind.GOOGLE_PIXEL_11_PRO_XL -> "Google Pixel 11 Pro XL Edition"
            Kind.GOOGLE_PIXEL -> {
                val m = Build.MODEL ?: "Pixel"
                if (m.startsWith("Google", ignoreCase = true)) "$m Edition" else "Google $m Edition"
            }
            Kind.GENERIC_ANDROID -> "${Build.MANUFACTURER ?: "Android"} ${Build.MODEL ?: "Device"}".trim()
        }

    /** Short phone identifier for EntropicDroid headers and compact badges */
    val deviceShortName: String
        get() = when (kind) {
            Kind.S25_ULTRA -> "S25 Ultra"
            Kind.SAMSUNG_GALAXY -> Build.MODEL ?: "Galaxy"
            Kind.GOOGLE_PIXEL_11_PRO_XL -> "Pixel 11 Pro XL"
            Kind.GOOGLE_PIXEL -> (Build.MODEL ?: "Pixel 11 Pro XL").removePrefix("Google ").trim()
            Kind.GENERIC_ANDROID -> Build.MODEL ?: "Android"
        }

    /** Short name of the on-device neural accelerator (AICore / Gemini Nano hardware target) */
    val npuShortName: String
        get() = when (kind) {
            Kind.S25_ULTRA, Kind.SAMSUNG_GALAXY -> "Hexagon NPU"
            Kind.GOOGLE_PIXEL_11_PRO_XL, Kind.GOOGLE_PIXEL -> "Tensor TPU"
            Kind.GENERIC_ANDROID -> "On-Device NPU"
        }

    /** Full SoC + NPU label for the Onboard AI Engine */
    val npuFullName: String
        get() = when (kind) {
            Kind.S25_ULTRA -> "Snapdragon 8 Elite • Hexagon NPU"
            Kind.SAMSUNG_GALAXY -> "Snapdragon / Exynos • Hexagon NPU"
            Kind.GOOGLE_PIXEL_11_PRO_XL -> "Google Tensor G6 • Tensor TPU"
            Kind.GOOGLE_PIXEL -> "Google Tensor • Tensor TPU"
            Kind.GENERIC_ANDROID -> "Android AICore • On-Device NPU"
        }

    /** CPU SIMD architecture description for local GGUF / EntropicDroid */
    val cpuSimdName: String
        get() = when (kind) {
            Kind.S25_ULTRA -> "Oryon ARMv9.2 SIMD"
            Kind.SAMSUNG_GALAXY -> "ARM64 NEON SIMD"
            Kind.GOOGLE_PIXEL_11_PRO_XL, Kind.GOOGLE_PIXEL -> "Tensor ARMv9 SIMD"
            Kind.GENERIC_ANDROID -> "ARM64 NEON"
        }

    /** Compact cross-platform badge for S25 Ultra + Pixel 11 Pro XL */
    val crossPlatformBadge: String
        get() = "$deviceShortName • $npuShortName (S25 Ultra ⇄ Pixel 11 Pro XL Compatible)"

    /** Dynamic title for the Phone EntropicDroid card */
    val entropicHeaderTitle: String
        get() = "📱 $deviceShortName EntropicDroid ($cpuSimdName) · NIST SP 800-90B"
}
