package forge.gamemodes.net.event;

import forge.gamemodes.tournament.system.TournamentRoundRobin;

public final class TournamentStartEvent implements NetEvent {
    private static final long serialVersionUID = 1L;
    private final String eventId;
    private final TournamentRoundRobin tournament;

    public TournamentStartEvent(String eventId, TournamentRoundRobin tournament) {
        this.eventId = eventId;
        this.tournament = tournament;
    }

    public String getEventId() {
        return eventId;
    }

    public TournamentRoundRobin getTournament() {
        return tournament;
    }
}
