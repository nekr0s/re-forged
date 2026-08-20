package forge.gui.interfaces;

import forge.gamemodes.net.event.*;

public interface ITournamentEventHandler extends INetEventHandler {
    abstract void onTournamentStart(TournamentStartEvent event);
    abstract void onTournamentUpdate(TournamentUpdateEvent event);
    abstract void onMatchStarted(MatchStartedEvent event);
    abstract void onMatchComplete(MatchCompleteEvent event);
    abstract void onRoundComplete(RoundCompleteEvent event);
    abstract void onTournamentComplete(TournamentCompleteEvent event);
    abstract void onSpectateApproved(SpectateApprovedEvent event);

    /**
     * Returns true if {@code event} was a tournament event and was dispatched.
     */
    default boolean dispatch(NetEvent event) {
        if (event instanceof TournamentStartEvent e) {
            onTournamentStart(e);
            return true;
        } else if (event instanceof TournamentUpdateEvent e) {
            onTournamentUpdate(e);
            return true;
        } else if (event instanceof MatchStartedEvent e) {
            onMatchStarted(e);
            return true;
        } else if (event instanceof MatchCompleteEvent e) {
            onMatchComplete(e);
            return true;
        } else if (event instanceof RoundCompleteEvent e) {
            onRoundComplete(e);
            return true;
        } else if (event instanceof TournamentCompleteEvent e) {
            onTournamentComplete(e);
            return true;
        } else if (event instanceof SpectateApprovedEvent e) {
            onSpectateApproved(e);
            return true;
        }
        return false;
    }
}
