package forge.gamemodes.net;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * Immutable, serializable snapshot of a {@link NetworkEvent} for transmission to clients.
 * Contains event metadata (format, phase, participants, timer) but not server-side
 * state like the SealedCardPoolGenerator or BoosterDraftHost reference.
 */
public final class NetworkEventView implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String eventId;
    private final EventFormat format;
    private final EventPhase phase;
    private final List<EventParticipant> participants;
    private final int pickTimerSeconds;
    private final String productDescription;
    private final int numRounds;
    private final int currentRound;
    private final int totalRounds;
    private final java.util.List<PairingView> pairings;
    private final java.util.List<StandingView> standings;
    private final int gamesPerMatch;
    private final java.util.Map<Integer, String> activeMatchIds;
    private final RoundState roundState;

    // Backward-compat constructor (no tournament state)
    public NetworkEventView(String eventId, EventFormat format, EventPhase phase,
            List<EventParticipant> participants, int pickTimerSeconds,
            String productDescription, int numRounds) {
        this(eventId, format, phase, participants, pickTimerSeconds, productDescription, numRounds,
                0, 0, java.util.Collections.emptyList(), java.util.Collections.emptyList(),
                3, java.util.Collections.emptyMap(), RoundState.NONE);
    }

    // Full constructor with tournament state
    public NetworkEventView(String eventId, EventFormat format, EventPhase phase,
            List<EventParticipant> participants, int pickTimerSeconds,
            String productDescription, int numRounds,
            int currentRound, int totalRounds,
            List<PairingView> pairings, List<StandingView> standings,
            int gamesPerMatch, Map<Integer, String> activeMatchIds) {
        this(eventId, format, phase, participants, pickTimerSeconds, productDescription, numRounds,
                currentRound, totalRounds, pairings, standings,
                gamesPerMatch, activeMatchIds, RoundState.NONE);
    }

    // Full constructor with tournament state and round state
    public NetworkEventView(String eventId, EventFormat format, EventPhase phase,
            List<EventParticipant> participants, int pickTimerSeconds,
            String productDescription, int numRounds,
            int currentRound, int totalRounds,
            List<PairingView> pairings, List<StandingView> standings,
            int gamesPerMatch, Map<Integer, String> activeMatchIds, RoundState roundState) {
        this.eventId = eventId;
        this.format = format;
        this.phase = phase;
        this.participants = List.copyOf(participants);
        this.pickTimerSeconds = pickTimerSeconds;
        this.productDescription = productDescription;
        this.numRounds = numRounds;
        this.currentRound = currentRound;
        this.totalRounds = totalRounds;
        this.pairings = List.copyOf(pairings);
        this.standings = List.copyOf(standings);
        this.gamesPerMatch = gamesPerMatch;
        this.activeMatchIds = Map.copyOf(activeMatchIds);
        this.roundState = roundState;
    }

    public String getEventId() { return eventId; }
    public EventFormat getFormat() { return format; }
    public EventPhase getPhase() { return phase; }
    public List<EventParticipant> getParticipants() { return participants; }
    public int getPickTimerSeconds() { return pickTimerSeconds; }
    public String getProductDescription() { return productDescription; }
    public int getNumRounds() { return numRounds; }
    public int getCurrentRound() { return currentRound; }
    public int getTotalRounds() { return totalRounds; }
    public List<PairingView> getPairings() { return pairings; }
    public List<StandingView> getStandings() { return standings; }
    public int getGamesPerMatch() { return gamesPerMatch; }
    public Map<Integer, String> getActiveMatchIds() { return activeMatchIds; }
    public RoundState getRoundState() { return roundState; }

    public boolean isTournamentActive() {
        return totalRounds > 0 && !standings.isEmpty();
    }
}
