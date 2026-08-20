package forge.gamemodes.net.event;

import forge.gamemodes.net.PairingView;
import forge.gamemodes.net.RoundState;
import forge.gamemodes.net.StandingView;

import java.util.List;

/**
 * Server-authoritative snapshot of tournament state, broadcast on every
 * transition (round start, match complete, between-round standby, next round
 * start, tournament complete/cancel). Clients replace their snapshot
 * wholesale from this event — they never re-derive or mutate tournament state.
 *
 * <p>This replaces the old practice of shipping the whole {@code TournamentRoundRobin}
 * engine object on the wire (which was not serializable and, even when it was,
 * gave clients a frozen one-time snapshot).
 */
public final class TournamentUpdateEvent implements NetEvent {
    private static final long serialVersionUID = 1L;

    private final String eventId;
    /** The round to display. During the COMPLETE standby window this is the round that just finished. */
    private final int round;
    private final int totalRounds;
    private final RoundState roundState;
    private final List<PairingView> pairings;
    private final List<StandingView> standings;

    public TournamentUpdateEvent(String eventId, int round, int totalRounds,
            RoundState roundState, List<PairingView> pairings, List<StandingView> standings) {
        this.eventId = eventId;
        this.round = round;
        this.totalRounds = totalRounds;
        this.roundState = roundState;
        this.pairings = List.copyOf(pairings);
        this.standings = List.copyOf(standings);
    }

    public String getEventId() { return eventId; }
    public int getRound() { return round; }
    public int getTotalRounds() { return totalRounds; }
    public RoundState getRoundState() { return roundState; }
    public List<PairingView> getPairings() { return pairings; }
    public List<StandingView> getStandings() { return standings; }
}
