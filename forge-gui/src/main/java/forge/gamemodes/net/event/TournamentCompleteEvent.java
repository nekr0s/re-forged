package forge.gamemodes.net.event;

import forge.gamemodes.net.StandingView;
import java.util.List;

public final class TournamentCompleteEvent implements NetEvent {
    private static final long serialVersionUID = 1L;
    private final List<StandingView> finalStandings;
    private final boolean cancelled;

    public TournamentCompleteEvent(List<StandingView> finalStandings, boolean cancelled) {
        this.finalStandings = finalStandings;
        this.cancelled = cancelled;
    }

    public List<StandingView> getFinalStandings() { return finalStandings; }
    public boolean isCancelled() { return cancelled; }
}
