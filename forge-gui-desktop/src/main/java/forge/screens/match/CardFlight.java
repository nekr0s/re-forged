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
import java.util.Map;

import javax.swing.JLayeredPane;
import javax.swing.SwingUtilities;

import forge.Singletons;
import forge.game.card.CardView;
import forge.game.player.PlayerView;
import forge.screens.match.views.VField;
import forge.screens.match.views.VHand;
import forge.view.arcane.CardPanel;
import forge.view.arcane.util.Animation;

/**
 * Flies a card from wherever the player last saw it to wherever it has just
 * landed — hand to stack when a spell is cast, stack to battlefield when it
 * resolves.
 * <p>
 * The flight is a throwaway {@link CardPanel} moved across the frame's layered
 * pane, so it passes over everything and disturbs no layout. Every entry point
 * reports whether it found somewhere to fly from; callers fall back to showing
 * the card outright when it didn't, which is what the UI did for everything
 * before.
 */
public final class CardFlight {
    /** How long a card takes to cross the board. Short enough not to hold up play. */
    private static final int FLIGHT_MS = 320;
    /** Size of the card puff that stands in for a hand nobody can see. */
    private static final int HIDDEN_ORIGIN_WIDTH = 14;

    private final CMatchUI matchUI;
    /**
     * Where cards were last shown, recorded as the panel showing them is thrown
     * away. Entries are consumed by the next flight and dropped otherwise, so a
     * card never flies out of a position it held some turns ago.
     */
    private final Map<Integer, Rectangle> departures = new HashMap<>();

    CardFlight(final CMatchUI matchUI0) {
        matchUI = matchUI0;
    }

    /** Notes where a card is on screen, just before the panel showing it goes away. */
    public void recordDeparture(final CardView card, final Component from) {
        if (card == null || from == null || !from.isShowing()) { return; }
        final Rectangle bounds = toLayeredPane(from.getLocationOnScreen(), from.getWidth(), from.getHeight());
        if (bounds != null) {
            departures.put(card.getId(), bounds);
        }
    }

    /**
     * Drops everything remembered. Called before each fresh round of departures is
     * recorded, so what is held is never older than one stack change.
     */
    public void forgetDepartures() {
        departures.clear();
    }

    /**
     * Flies a card into the slot a freshly added battlefield panel occupies,
     * revealing that panel when it arrives.
     */
    public boolean flyInto(final CardPanel placeholder) {
        if (placeholder == null || !placeholder.isShowing()) { return false; }
        final CardView card = placeholder.getCard();
        // No zone fallback here: a card can reach the battlefield from anywhere,
        // and guessing would fly cards out of zones they were never in.
        final Rectangle origin = originOf(card, false);
        if (origin == null) { return false; }

        final Point target = SwingUtilities.convertPoint(placeholder.getParent(),
                placeholder.getCardLocation(), layeredPane());
        return fly(card, origin, target, placeholder.getCardWidth(), placeholder);
    }

    /**
     * Flies a card to a point given in screen coordinates — used for the stack,
     * which lives in its own window and so has no place in the layered pane.
     */
    public boolean flyToScreen(final CardView card, final Point targetOnScreen, final int targetWidth) {
        if (card == null || targetOnScreen == null || targetWidth <= 0) { return false; }
        // A card only reaches the stack by being cast, so a hand nobody can see is
        // a safe guess at where an opponent's spell came from.
        final Rectangle origin = originOf(card, true);
        if (origin == null) { return false; }

        final Point target = new Point(targetOnScreen);
        SwingUtilities.convertPointFromScreen(target, layeredPane());
        return fly(card, origin, target, targetWidth, null);
    }

    private boolean fly(final CardView card, final Rectangle origin, final Point target,
            final int targetWidth, final CardPanel placeholder) {
        if (!Singletons.getView().getFrame().isShowing() || !matchUI.isCurrentScreen()) { return false; }
        if (origin.x == target.x && origin.y == target.y) { return false; }

        final CardPanel flier = new CardPanel(matchUI, card);
        Animation.moveCard(origin.x, origin.y, origin.width, target.x, target.y, targetWidth,
                flier, placeholder, layeredPane(), FLIGHT_MS);
        return true;
    }

    /**
     * Where a card should fly out of: the panel still showing it in a hand or on
     * the stack, else wherever it was when the last panel showing it went away.
     *
     * @param allowHiddenZone whether a hand the player cannot see may stand in
     *      when there is no panel to fly from at all.
     */
    private Rectangle originOf(final CardView card, final boolean allowHiddenZone) {
        if (card == null) { return null; }

        // Zones update one after another, so the zone a card is leaving usually
        // still has it on screen when the zone it is entering redraws.
        for (final VHand hand : matchUI.getHandViews()) {
            final CardPanel panel = hand.getHandArea().getCardPanel(card.getId());
            if (panel != null && panel.isShowing() && panel.getCardWidth() > 0) {
                final Point loc = SwingUtilities.convertPoint(panel.getParent(),
                        panel.getCardLocation(), layeredPane());
                return new Rectangle(loc.x, loc.y, panel.getCardWidth(), panel.getCardHeight());
            }
        }
        final Rectangle onStack = matchUI.getCStack().getView().getCardBoundsOnScreen(card.getId());
        if (onStack != null) {
            final Rectangle inPane = toLayeredPane(onStack.getLocation(), onStack.width, onStack.height);
            if (inPane != null) { return inPane; }
        }

        final Rectangle departed = departures.remove(card.getId());
        if (departed != null) { return departed; }

        return allowHiddenZone ? hiddenZoneOrigin(card.getController()) : null;
    }

    /**
     * A small card-shaped patch over the zone readout of a player whose hand is
     * not on screen — the only thing there is to fly their cards out of.
     */
    private Rectangle hiddenZoneOrigin(final PlayerView controller) {
        if (controller == null || matchUI.getHandFor(controller) != null) { return null; }
        final VField field = matchUI.getFieldViewFor(controller);
        if (field == null) { return null; }
        final Component details = field.getDetailsPanel();
        if (!details.isShowing() || details.getWidth() == 0) { return null; }

        final int height = Math.round(HIDDEN_ORIGIN_WIDTH * CardPanel.ASPECT_RATIO);
        final Point centre = SwingUtilities.convertPoint(details,
                details.getWidth() / 2, details.getHeight() / 2, layeredPane());
        return new Rectangle(centre.x - HIDDEN_ORIGIN_WIDTH / 2, centre.y - height / 2,
                HIDDEN_ORIGIN_WIDTH, height);
    }

    private Rectangle toLayeredPane(final Point onScreen, final int width, final int height) {
        final JLayeredPane pane = layeredPane();
        if (pane == null || !pane.isShowing()) { return null; }
        final Point p = new Point(onScreen);
        SwingUtilities.convertPointFromScreen(p, pane);
        return new Rectangle(p.x, p.y, width, height);
    }

    private static JLayeredPane layeredPane() {
        return Singletons.getView().getFrame().getLayeredPane();
    }
}
