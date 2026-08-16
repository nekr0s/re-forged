package forge.gui.interfaces;

import forge.deck.Deck;
import forge.gamemodes.net.event.*;
import forge.item.PaperCard;

import java.util.List;

public interface IDraftEventHandler extends INetEventHandler {
    abstract void draftPackArrived(int seatIndex, List<PaperCard> pack,
            int packNumber, int pickNumber, int timerDurationSeconds);
    abstract void draftSeatPicked(int seatIndex, int[] seatQueueDepths);
    abstract void draftAutoPicked(int seatIndex, PaperCard card, int packNumber, int pickInPack);
    abstract void receiveEventPool(String eventId, Deck pool);

    /** Returns true if {@code event} was a draft event and was dispatched. */
    default boolean dispatch(NetEvent event) {
        if (event instanceof DraftPackArrivedEvent e) {
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
