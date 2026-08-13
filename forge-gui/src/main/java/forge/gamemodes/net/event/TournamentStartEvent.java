package forge.gamemodes.net.event;

public final class TournamentStartEvent implements NetEvent {
    private static final long serialVersionUID = 1L;
    private final String eventId;

    public TournamentStartEvent(String eventId) {
        this.eventId = eventId;
    }

    public String getEventId() { return eventId; }
}
