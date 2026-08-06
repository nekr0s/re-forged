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

import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;

import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.view.FDialog;
import forge.view.FFrame;

/**
 * Base for the match windows that float over the board instead of living in a
 * dock cell. Handles remembering where the user dragged the window to, and
 * placing it sensibly the first time it appears.
 */
@SuppressWarnings("serial")
public abstract class FloatingMatchWindow extends FDialog {
    private static final String COORD_DELIM = ",";

    private final FPref locPref;
    private final Timer saveLocTimer;

    protected FloatingMatchWindow(final FPref locPref0, final boolean allowResize) {
        super(JOptionPane.getRootFrame(), false, allowResize, "2");
        locPref = locPref0;
        getTitleBar().setCloseButtonVisible(false);

        // Coalesce a burst of drag events into a single preference write, once the
        // window has come to rest.
        saveLocTimer = new Timer(400, e -> saveBounds()); //non-repeating, so it stops itself
        saveLocTimer.setRepeats(false);

        // Only a drag of the window itself counts as the user placing it. Component
        // events can't be used to tell our own moves from the user's: they arrive long
        // after the call that caused them, and a window manager need not grant exactly
        // what was asked for, so comparing bounds against what we last set says "the
        // user did that" for moves we made ourselves. A window that repositions itself
        // as often as the stack does then buries the position the user chose.
        final MouseMotionAdapter userMoved = new MouseMotionAdapter() {
            @Override
            public void mouseDragged(final MouseEvent e) { saveLocTimer.restart(); }
        };
        getTitleBar().addMouseMotionListener(userMoved); //moving, per FDialog.addMoveSupport
        getRootPane().addMouseMotionListener(userMoved); //resizing, per FDialog.addResizeSupport
    }

    /** The panel this window's contents are laid out in. */
    public JPanel getContentPanel() {
        return (JPanel) getContentPane();
    }

    @Override
    public void setVisible(final boolean visible) {
        final boolean wasVisible = isVisible();
        if (visible && !wasVisible) {
            applyStoredLocation(); //place before showing, so it doesn't flash at the wrong spot
            // A full-screen-exclusive owner sits above every ordinary window, its own
            // dialogs included, so a window shown over it is invisible until the mode
            // is left. Being always-on-top is what gets it back in front.
            final Window owner = JOptionPane.getRootFrame();
            setAlwaysOnTop(owner instanceof FFrame && ((FFrame) owner).isFullScreen());
        }
        super.setVisible(visible);
        if (visible && !wasVisible) {
            // FDialog.setVisible re-centres on every show; undo that so the
            // window stays where the user put it.
            applyStoredLocation();
            // Showing a window only asks the window manager to map it — the placing
            // happens on its side, after this call has returned, so the position set
            // above can still be overridden by the manager's own policy. Assert it
            // once more when the queue has caught up with the map. Harmless if the
            // window has been hidden again by then.
            SwingUtilities.invokeLater(this::applyStoredLocation);
            // The contents were rebuilt while the window was hidden, and Swing drops
            // repaints of a component that isn't showing. Showing the window again is
            // no guarantee of a fresh paint either — a compositing window manager
            // keeps the pixels it had and sends no expose event — so a window that
            // comes back the same size as it went away shows what was on it last
            // time. Repaint what is on it now.
            getContentPanel().repaint();
        }
    }

    protected void applyStoredLocation() {
        final Rectangle b = storedBounds();
        if (b == null) {
            placeByDefault();
            return;
        }
        if (restoresSize()) {
            setBounds(b);
        } else {
            // setBounds, not setLocation, even though only the position is being
            // restored: position and size reach the window manager as one set of
            // hints, and a bare move on a window that is about to be mapped leaves
            // it free to place the window wherever its own policy says.
            setBounds(b.x, b.y, getWidth(), getHeight());
        }
        if (!isOnScreen()) { placeByDefault(); } //display layout may have changed since last run
    }

    /** True once the user has dragged the window somewhere of their own choosing. */
    protected boolean isUserPlaced() {
        return storedBounds() != null;
    }

    /** Whether the stored size is restored along with the stored position. */
    protected boolean restoresSize() {
        return true;
    }

    /** Where the window sits before the user has moved it. */
    protected void placeByDefault() {
        final Rectangle r = ownerBounds();
        setLocation(r.x + (r.width - getWidth()) / 2, r.y + (r.height - getHeight()) / 2);
    }

    protected static Rectangle ownerBounds() {
        final Window owner = JOptionPane.getRootFrame();
        return owner != null && owner.isShowing()
                ? owner.getBounds()
                : new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
    }

    protected Rectangle storedBounds() {
        final String s = FModel.getPreferences().getPref(locPref);
        if (s == null || s.isEmpty()) { return null; }
        final String[] parts = s.split(COORD_DELIM);
        if (parts.length != 4) { return null; }
        try {
            return new Rectangle(
                    Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
        } catch (final NumberFormatException ex) {
            return null;
        }
    }

    private boolean isOnScreen() {
        // A window that has no size yet can't be tested: the zero-width strip below
        // intersects nothing, and the answer "off screen" would send every such show
        // to the default position.
        if (getWidth() <= 0) { return true; }
        // Require a decent chunk of the title bar to be reachable, not just one pixel.
        final Rectangle titleStrip = new Rectangle(getX(), getY(), getWidth(), 30);
        // Every display, not just the primary one: a window the user dragged onto a
        // second monitor is where they wanted it, and putting it back in the middle of
        // the main screen on every show is not a rescue.
        for (final GraphicsDevice screen
                : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            if (screen.getDefaultConfiguration().getBounds().intersects(titleStrip)) {
                return true;
            }
        }
        return false;
    }

    private void saveBounds() {
        final Point p = getLocation();
        FModel.getPreferences().setPref(locPref,
                p.x + COORD_DELIM + p.y + COORD_DELIM + getWidth() + COORD_DELIM + getHeight());
        FModel.getPreferences().save();
    }
}
