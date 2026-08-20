package forge.gamemodes.net.event;

public final class MatchStartedEvent implements NetEvent {
    private static final long serialVersionUID = 1L;
    private final String matchId;
    private final String playerA;
    private final String playerB;
    private final int round;

    public MatchStartedEvent(String matchId, String playerA, String playerB, int round) {
        this.matchId = matchId;
        this.playerA = playerA;
        this.playerB = playerB;
        this.round = round;
    }

    public String getMatchId() { return matchId; }
    public String getPlayerA() { return playerA; }
    public String getPlayerB() { return playerB; }
    public int getRound() { return round; }
}
