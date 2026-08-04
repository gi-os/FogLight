package expo.modules.recorder

/**
 * When the GPS radio is allowed to be on.
 *
 * The recorder used to keep GPS at a fix every ten seconds, all day, every day, and then throw
 * away every fix that landed inside a privacy zone. That is the worst of both: the phone paid for a
 * position it had already promised not to write down. Roughly 70% of a day's battery went on it.
 *
 * So the zones stop being a filter on the *output* and become a switch on the *input*, and a second
 * switch is added for the rest of the day: a phone that has not moved does not need to be asked
 * where it is. Both switches turn the radio off rather than discarding what it returns.
 *
 * Free of Android imports so the decision can be tested without a device — the service does the
 * sensing and the applying, this decides only what the state should be.
 */
object Power {

    /** What the recorder is doing with the GPS radio right now. */
    enum class State {
        /** Radio on, fixes being written. */
        ACTIVE,

        /** Radio off because the phone is on a network the user named home or work. */
        PAUSED_ZONE,

        /** Radio off because nothing has moved; a hardware motion trigger will wake it. */
        PAUSED_STILL,
    }

    /** Default wait before a phone that has not moved is called still. */
    const val DEFAULT_STILL_AFTER_MS = 6L * 60L * 1000L

    /**
     * How far a fix may wander from the anchor and still count as not having moved.
     *
     * Wider than GPS noise (a stationary phone indoors reports circles tens of metres across) and
     * narrower than a walk to the corner. Below about 40m this never triggers, because the drift
     * alone keeps resetting the anchor — which is the failure mode that makes motion gating look
     * like it does nothing.
     */
    const val STILL_RADIUS_M = 70.0

    /**
     * Longest a still pause may last before the radio is woken to look again.
     *
     * The significant-motion sensor is one-shot and its wake is the only thing that ends a still
     * pause, so a sensor that never fires — a phone carried perfectly smoothly, a trigger consumed
     * and lost to a process death — would end recording silently and for good. An hour is short
     * enough that a missed wake costs one hour of track and long enough that the watchdog itself
     * costs nothing.
     */
    const val STILL_WATCHDOG_MS = 60L * 60L * 1000L

    /**
     * The state the recorder should be in.
     *
     * **A zone beats stillness**, and the order matters: at home both tests are true, and the zone
     * answer is the one that survives a walk around the flat. Coming out of a still pause needs a
     * motion trigger to fire; coming out of a zone pause needs the Wi-Fi to drop, which happens on
     * its own at the door. Preferring stillness at home would mean a wake for every trip to the
     * kitchen.
     *
     * @param inZone connected to a network (or inside a radius) the user named home or work
     * @param gating whether the user has motion gating on at all
     * @param canSenseMotion a significant-motion sensor exists — without one, a still pause has
     *   nothing to end it except the watchdog, so it is not entered
     * @param stillFor how long fixes have stayed within [STILL_RADIUS_M] of the anchor, or null if
     *   the last fix moved off it
     * @param stillAfterMs the user's wait before stillness counts
     */
    fun decide(
        inZone: Boolean,
        gating: Boolean,
        canSenseMotion: Boolean,
        stillFor: Long?,
        stillAfterMs: Long = DEFAULT_STILL_AFTER_MS,
    ): State {
        if (inZone) return State.PAUSED_ZONE
        if (!gating || !canSenseMotion) return State.ACTIVE
        if (stillFor != null && stillFor >= stillAfterMs) return State.PAUSED_STILL
        return State.ACTIVE
    }

    /** True while a fix is close enough to the anchor to count as not having moved. */
    fun stillAt(distanceFromAnchorM: Double): Boolean = distanceFromAnchorM <= STILL_RADIUS_M

    /** What the notification says, so the phone can explain a radio that is off on purpose. */
    fun describe(state: State, zoneName: String?): String = when (state) {
        State.ACTIVE -> "Recording your path"
        State.PAUSED_ZONE -> "Paused — on your ${zoneName ?: "home"} network"
        State.PAUSED_STILL -> "Paused — nothing moving"
    }
}
