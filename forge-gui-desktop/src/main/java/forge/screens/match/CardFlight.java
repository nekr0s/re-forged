/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package forge.screens.match;

import java.awt.Component;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import javax.swing.JLayeredPane;
import javax.swing.SwingUtilities;

import forge.Singletons;
import forge.game.card.CardView;
import forge.game.player.PlayerView;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.screens.match.views.VField;
import forge.screens.match.views.VHand;
import forge.view.arcane.CardPanel;
import forge.view.arcane.util.Animation;

/**
 * Flies a card from wherever the player last saw it to wherever it has just
 * landed — hand to stack when a spell is cast, stack to battlefield when it
 * resolves, hand to battlefield for a land.
 * <p>
 * The flight is a throwaway {@link CardPanel} moved across the frame's layered
 * pane, so it passes over everything and disturbs no layout. Every entry point
 * reports whether it found somewhere to fly from; callers fall back to showing
 * the card outright when it didn't, which is what the UI did for everything
 * before.
 * <p>
 * Positions are held in screen coordinates, because the stack lives in its own
 * window and only screen coordinates mean the same thing in both.
 */
public final class CardFlight {
    /** How long a card takes to cross the board. Short enough not to hold up play. */
    private static final int FLIGHT_MS = 320;
    /** Size of the card puff that stands in for a hand nobody can see. */
    private static final int HIDDEN_ORIGIN_WIDTH = 14;
    /**
     * How long a card's last position stays worth flying out of. Long enough to
     * cover the updates of one game event, short enough that a card returning from
     * a graveyard turns later doesn't fly out of where it died.
     */
    private static final long DEPARTURE_TTL_MS = 1500;

    private final CMatchUI matchUI;
    /** Where cards were last shown, recorded as the panel showing them is thrown away. */
    private final Map<Integer, Departure> departures = new HashMap<>();

    private record Departure(Rectangle bounds, long at) { }

    CardFlight(final CMatchUI matchUI0) {
        matchUI = matchUI0;
    }

    /**
     * Notes where a card is on screen, just before the panel showing it goes away.
     * Zones redraw one at a time and in no fixed order, so the zone a card is
     * entering may well redraw after the one it left; this is what it flies out of
     * when that happens.
     */
    public void recordDeparture(final CardView card, final Rectangle onScreen) {
        if (card == null || onScreen == null) { return; }
        pruneDepartures();
        departures.put(card.getId(), new Departure(onScreen, System.currentTimeMillis()));
    }

    /** Where a card panel's art is on screen, or null while it isn't showing. */
    public static Rectangle screenBoundsOf(final CardPanel panel) {
        if (panel == null || !panel.isShowing() || panel.getCardWidth() <= 0) { return null; }
        final Point loc = panel.getCardLocationOnScreen();
        return new Rectangle(loc.x, loc.y, panel.getCardWidth(), panel.getCardHeight());
    }

    /**
     * Where a card should fly out of, in screen coordinates: the panel still
     * showing it in a hand, else wherever it was when the last panel showing it
     * went away.
     * <p>
     * Call this while the card is still in the zone it is leaving. Anything that
     * has to wait for layout before it can animate should take the origin now and
     * hand it to {@link #fly} later, or the card will have moved on by then.
     *
     * @param allowHiddenZone whether a hand the player cannot see may stand in
     *      when there is no panel to fly from at all.
     */
    public Rectangle originOf(final CardView card, final boolean allowHiddenZone) {
        if (card == null) { return null; }

        for (final VHand hand : matchUI.getHandViews()) {
            final Rectangle bounds = screenBoundsOf(hand.getHandArea().getCardPanel(card.getId()));
            if (bounds != null) { return bounds; }
        }
        final Departure departed = departures.remove(card.getId());
        if (departed != null && System.currentTimeMillis() - departed.at() <= DEPARTURE_TTL_MS) {
            return departed.bounds();
        }
        return allowHiddenZone ? hiddenZoneOrigin(card.getController()) : null;
    }

    /**
     * Flies a card into the slot a freshly added battlefield panel occupies,
     * revealing that panel when it arrives.
     */
    public boolean flyInto(final CardPanel placeholder) {
        if (placeholder == null || !placeholder.isShowing()) { return false; }
        final CardView card = placeholder.getCard();
        // A token is made on the battlefield rather than moved to it, so it has
        // nowhere to come from; everything else came out of a zone of its owner's.
        final Rectangle origin = originOf(card, card != null && !card.isToken());
        if (origin == null) { return false; }

        final Point target = placeholder.getCardLocationOnScreen();
        return fly(card, origin, target, placeholder.getCardWidth(), placeholder);
    }

    /** Flies a card from an origin taken earlier to a point in screen coordinates. */
    public boolean fly(final CardView card, final Rectangle origin, final Point targetOnScreen,
            final int targetWidth) {
        return fly(card, origin, targetOnScreen, targetWidth, null);
    }

    private boolean fly(final CardView card, final Rectangle origin, final Point targetOnScreen,
            final int targetWidth, final CardPanel placeholder) {
        if (card == null || origin == null || targetOnScreen == null || targetWidth <= 0) { return false; }
        if (!FModel.getPreferences().getPrefBoolean(FPref.UI_CARD_ANIMATIONS)) { return false; }
        if (!Singletons.getView().getFrame().isShowing() || !matchUI.isCurrentScreen()) { return false; }

        final JLayeredPane pane = layeredPane();
        if (pane == null || !pane.isShowing()) { return false; }
        final Point from = toPane(origin.getLocation(), pane);
        final Point to = toPane(targetOnScreen, pane);
        if (from.equals(to)) { return false; } //nowhere to travel

        Animation.moveCard(from.x, from.y, origin.width, to.x, to.y, targetWidth,
                new CardPanel(matchUI, card), placeholder, pane, FLIGHT_MS);
        return true;
    }

    /**
     * A small card-shaped patch over the zone readout of a player whose hand is not
     * on screen — the only thing there is to fly their cards out of. The readout
     * carries every hidden zone they have, not just the hand, so a card coming from
     * their graveyard or library flies out of an honest place too.
     */
    private Rectangle hiddenZoneOrigin(final PlayerView controller) {
        if (controller == null || matchUI.getHandFor(controller) != null) { return null; }
        final VField field = matchUI.getFieldViewFor(controller);
        if (field == null) { return null; }
        final Component details = field.getDetailsPanel();
        if (!details.isShowing() || details.getWidth() == 0) { return null; }

        final int height = Math.round(HIDDEN_ORIGIN_WIDTH * CardPanel.ASPECT_RATIO);
        final Point centre = details.getLocationOnScreen();
        centre.translate(details.getWidth() / 2, details.getHeight() / 2);
        return new Rectangle(centre.x - HIDDEN_ORIGIN_WIDTH / 2, centre.y - height / 2,
                HIDDEN_ORIGIN_WIDTH, height);
    }

    private void pruneDepartures() {
        final long now = System.currentTimeMillis();
        final Iterator<Departure> it = departures.values().iterator();
        while (it.hasNext()) {
            if (now - it.next().at() > DEPARTURE_TTL_MS) { it.remove(); }
        }
    }

    private static Point toPane(final Point onScreen, final JLayeredPane pane) {
        final Point p = new Point(onScreen);
        SwingUtilities.convertPointFromScreen(p, pane);
        return p;
    }

    private static JLayeredPane layeredPane() {
        return Singletons.getView().getFrame().getLayeredPane();
    }
}
