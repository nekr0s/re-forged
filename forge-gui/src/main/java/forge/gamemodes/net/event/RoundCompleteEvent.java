package forge.gamemodes.net.event;

public final class RoundCompleteEvent implements NetEvent {
    private static final long serialVersionUID = 1L;
    private final int round;

    public RoundCompleteEvent(int round) {
        this.round = round;
    }

    public int getRound() { return round; }
}
