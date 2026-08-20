package forge.gamemodes.net;

/**
 * Lifecycle state of the current round within a tournament.
 * <p>
 * This is orthogonal to {@link EventPhase}: the tournament's {@code TOURNAMENT_IN_PROGRESS}
 * phase stays stable across all rounds, while {@code RoundState} toggles as each round's
 * matches start and finish.
 */
public enum RoundState {
    /** No current round — the tournament has not started, or is finished/cancelled. */
    NONE,
    /** The current round's matches are running. */
    ACTIVE,
    /** All of the current round's matches finished; waiting for the next round to start. */
    COMPLETE
}