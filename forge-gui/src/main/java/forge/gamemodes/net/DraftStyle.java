package forge.gamemodes.net;

import forge.card.DraftOptions;

/**
 * The two supported online draft formats.
 * <p>
 * Each format fixes the draft pod size (the number of seats that draft and pass
 * packs) and the pick rule (how many cards a player takes from a pack before it
 * passes to the next seat). Non-playable AI fillers pad the pod to the format's
 * size; the round-robin tournament only counts real seated players/bots.
 */
public enum DraftStyle {
    EIGHT_PLAYER_PICK_ONE(8, DraftOptions.DoublePick.NEVER),
    FOUR_PLAYER_PICK_TWO(4, DraftOptions.DoublePick.ALWAYS);

    private final int podSize;
    private final DraftOptions.DoublePick doublePick;

    DraftStyle(int podSize, DraftOptions.DoublePick doublePick) {
        this.podSize = podSize;
        this.doublePick = doublePick;
    }

    public int podSize() { return podSize; }
    public DraftOptions.DoublePick doublePick() { return doublePick; }

    /** True when each player takes two cards per pack before passing. */
    public boolean isDoublePick() { return doublePick == DraftOptions.DoublePick.ALWAYS; }
}
