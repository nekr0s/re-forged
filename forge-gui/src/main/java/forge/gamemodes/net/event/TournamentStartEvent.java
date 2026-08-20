package forge.gamemodes.net.event;

import java.util.List;

/**
 * Signals that a tournament has started. Carries only a wire-safe summary; the
 * full, live tournament state (rounds, pairings, standings) is pushed to
 * clients via {@link TournamentUpdateEvent} on every transition.
 */
public final class TournamentStartEvent implements NetEvent {
    private static final long serialVersionUID = 1L;
    private final String eventId;
    private final List<String> playerNames;
    private final int totalRounds;

    public TournamentStartEvent(String eventId, List<String> playerNames, int totalRounds) {
        this.eventId = eventId;
        this.playerNames = List.copyOf(playerNames);
        this.totalRounds = totalRounds;
    }

    public String getEventId() {
        return eventId;
    }

    public List<String> getPlayerNames() {
        return playerNames;
    }

    public int getTotalRounds() {
        return totalRounds;
    }
}
