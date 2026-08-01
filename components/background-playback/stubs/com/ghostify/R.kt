package com.ghostify

/**
 * Compile-time-only stub for the application's generated `R`, so the service
 * source (compiled under the headless `androidCheck` source set without aapt2)
 * can reference `R.drawable.ic_notification`. In the real AGP build the
 * generated `com.ghostify.R` is used instead; this file is never packaged.
 */
object R {
    object drawable {
        const val ic_notification: Int = 0x7f020000
    }
}
