package forge.gamemodes.net.event;

public final class SpectateApprovedEvent implements NetEvent {
    private static final long serialVersionUID = 1L;
    private final String matchId;

    public SpectateApprovedEvent(String matchId) {
        this.matchId = matchId;
    }

    public String getMatchId() { return matchId; }
}
