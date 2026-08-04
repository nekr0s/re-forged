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
package forge.screens.match.views;

import java.awt.Color;
import java.awt.Rectangle;

import javax.swing.BorderFactory;
import javax.swing.border.CompoundBorder;

import forge.localinstance.properties.ForgePreferences.FPref;
import forge.util.Localizer;

/**
 * The window the stack is shown in, floating over the board. Sized to whatever
 * is on the stack at the moment, and hidden entirely when the stack is empty.
 * <p>
 * Deliberately not resizable: the cards are drawn at one fixed size, so there
 * is nothing a drag could usefully change. The only thing that alters the
 * window's size is opening or closing the text list, which it makes room for
 * itself and gives back again.
 */
@SuppressWarnings("serial")
public class FloatingStack extends FloatingMatchWindow {
    /**
     * Fraction of the match window's height the cascade's bottom edge sits at
     * before the user moves the window. Chosen to sit clear of the centred
     * {@link FloatingPrompt}, since both are usually up at the same time.
     */
    private static final float DEFAULT_BOTTOM_FRACTION = 0.42f;
    /** Matches the border {@link forge.view.FDialog} gives a resizable window. */
    private static final int BORDER_THICKNESS = 3;

    public FloatingStack() {
        super(FPref.STACK_WINDOW_LOC, true);
        setTitle(Localizer.getInstance().getMessage("lblStack"));
        setResizable(false);
        // The stack is read-only, so it must never take focus away from the
        // prompt's buttons, which are driven by the keyboard.
        setFocusableWindowState(false);
        // Same border the prompt wears, so the two windows the game floats over
        // the board read as a pair.
        setBorder(new CompoundBorder(
                BorderFactory.createLineBorder(Color.BLACK, 1),
                BorderFactory.createLineBorder(VPrompt.ACCENT, BORDER_THICKNESS - 1)));
    }

    /** Fits the window to the current stack, leaving it wherever the user put it. */
    public void sizeToContent() {
        pack();
        if (!isUserPlaced()) {
            placeByDefault();
        }
    }

    /** Only the position is the user's to choose; the size always follows the contents. */
    @Override
    protected boolean restoresSize() {
        return false;
    }

    @Override
    protected void placeByDefault() {
        final Rectangle r = ownerBounds();
        final int x = r.x + (r.width - getWidth()) / 2;
        final int y = Math.max(r.y + 8,
                r.y + Math.round(r.height * DEFAULT_BOTTOM_FRACTION) - getHeight());
        setLocation(x, y);
    }
}
