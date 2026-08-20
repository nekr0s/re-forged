package forge.gamemodes.net.event;

public final class SpectateRequestEvent implements IdentifiableNetEvent {
    private static final long serialVersionUID = 1L;
    private static int staticId = 0;
    private final int id;
    private final String matchId;

    public SpectateRequestEvent(String matchId) {
        this.id = staticId++;
        this.matchId = matchId;
    }

    @Override
    public int getId() { return id; }
    public String getMatchId() { return matchId; }
}
