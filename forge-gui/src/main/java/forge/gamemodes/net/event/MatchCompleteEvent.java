package forge.gamemodes.net.event;

public final class MatchCompleteEvent implements NetEvent {
    private static final long serialVersionUID = 1L;
    private final String matchId;
    private final String winner;
    private final String score;

    public MatchCompleteEvent(String matchId, String winner, String score) {
        this.matchId = matchId;
        this.winner = winner;
        this.score = score;
    }

    public String getMatchId() { return matchId; }
    public String getWinner() { return winner; }
    public String getScore() { return score; }
}
