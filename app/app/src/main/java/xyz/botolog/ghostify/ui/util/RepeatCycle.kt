package xyz.botolog.ghostify.ui.util

/**
 * Repeat modes exposed to the player UI. The cycle order is part of the product contract:
 * OFF -> ALL -> ONE -> OFF.
 *
 * This is a pure Kotlin utility (JVM testable) with no Android or Compose dependencies.
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

/**
 * Handles the cycling logic for repeat modes in the player UI.
 *
 * The fixed cycle is: OFF -> ALL -> ONE -> OFF.
 */
object RepeatCycle {

    /** Ordered list of repeat modes defining the cycle sequence. */
    val order: List<RepeatMode> = listOf(RepeatMode.OFF, RepeatMode.ALL, RepeatMode.ONE)

    /**
     * Returns the mode that follows [current] in the fixed OFF -> ALL -> ONE -> OFF cycle.
     *
     * @param current the current repeat mode.
     * @return the next repeat mode in the cycle.
     */
    fun next(current: RepeatMode): RepeatMode =
        order[(order.indexOf(current) + 1) % order.size]
}
