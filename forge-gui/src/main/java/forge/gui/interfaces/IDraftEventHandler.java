package forge.gui.interfaces;

import java.util.List;

import forge.deck.Deck;
import forge.gamemodes.net.event.DraftAutoPickedEvent;
import forge.gamemodes.net.event.DraftPackArrivedEvent;
import forge.gamemodes.net.event.DraftSeatPickedEvent;
import forge.gamemodes.net.event.NetEvent;
import forge.gamemodes.net.event.ReceiveEventPoolEvent;
import forge.item.PaperCard;

public interface IDraftEventHandler {
    void draftPackArrived(int seatIndex, List<PaperCard> pack,
            int packNumber, int pickNumber, int timerDurationSeconds);
    void draftSeatPicked(int seatIndex, int[] seatQueueDepths);
    void draftAutoPicked(int seatIndex, PaperCard card, int packNumber, int pickInPack);
    void receiveEventPool(String eventId, Deck pool);

    // Tournament event handlers
    default void onTournamentStart(forge.gamemodes.net.event.TournamentStartEvent event) {}
    default void onMatchStarted(forge.gamemodes.net.event.MatchStartedEvent event) {}
    default void onMatchComplete(forge.gamemodes.net.event.MatchCompleteEvent event) {}
    default void onRoundComplete(forge.gamemodes.net.event.RoundCompleteEvent event) {}
    default void onTournamentComplete(forge.gamemodes.net.event.TournamentCompleteEvent event) {}
    default void onSpectateApproved(forge.gamemodes.net.event.SpectateApprovedEvent event) {}

    /** Returns true if {@code event} was a draft event and was dispatched. */
    default boolean dispatch(NetEvent event) {
        if (event instanceof forge.gamemodes.net.event.TournamentStartEvent e) {
            onTournamentStart(e);
            return true;
        } else if (event instanceof forge.gamemodes.net.event.MatchStartedEvent e) {
            onMatchStarted(e);
            return true;
        } else if (event instanceof forge.gamemodes.net.event.MatchCompleteEvent e) {
            onMatchComplete(e);
            return true;
        } else if (event instanceof forge.gamemodes.net.event.RoundCompleteEvent e) {
            onRoundComplete(e);
            return true;
        } else if (event instanceof forge.gamemodes.net.event.TournamentCompleteEvent e) {
            onTournamentComplete(e);
            return true;
        } else if (event instanceof forge.gamemodes.net.event.SpectateApprovedEvent e) {
            onSpectateApproved(e);
            return true;
        } else if (event instanceof DraftPackArrivedEvent e) {
            draftPackArrived(e.getSeatIndex(), e.getPack(),
                    e.getPackNumber(), e.getPickNumber(), e.getTimerDurationSeconds());
            return true;
        } else if (event instanceof DraftSeatPickedEvent e) {
            draftSeatPicked(e.getSeatIndex(), e.getSeatQueueDepths());
            return true;
        } else if (event instanceof DraftAutoPickedEvent e) {
            draftAutoPicked(e.getSeatIndex(), e.getCard(),
                    e.getPackNumber(), e.getPickInPack());
            return true;
        } else if (event instanceof ReceiveEventPoolEvent e) {
            receiveEventPool(e.getEventId(), e.getPool());
            return true;
        }
        return false;
    }
}
