package com.daybloom.platform

enum class PlatformKind { ANDROID, IOS }

/** Small set of facts the shared UI uses to adapt to platform conventions. */
data class PlatformInfo(
    val kind: PlatformKind,
    val osVersion: String,
) {
    val isIos: Boolean get() = kind == PlatformKind.IOS
}
