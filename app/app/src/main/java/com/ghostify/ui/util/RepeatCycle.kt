package com.ghostify.ui.util

/**
 * Repeat modes exposed to the player UI. The cycle order is part of the product contract:
 * OFF -> ALL -> ONE -> OFF. Pure Kotlin (JVM testable).
 */
enum class RepeatMode {
    OFF,
    ALL,
    ONE;

    /** Human readable label used for the toggle button's accessible text. */
    val label: String
        get() = when (this) {
            OFF -> "Off"
            ALL -> "All"
            ONE -> "One"
        }
}

object RepeatCycle {
    val order: List<RepeatMode> = listOf(RepeatMode.OFF, RepeatMode.ALL, RepeatMode.ONE)

    /** Returns the mode that follows [current] in the fixed OFF -> ALL -> ONE -> OFF cycle. */
    fun next(current: RepeatMode): RepeatMode =
        order[(order.indexOf(current) + 1) % order.size]
}
