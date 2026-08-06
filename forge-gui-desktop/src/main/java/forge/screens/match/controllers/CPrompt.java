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
package forge.screens.match.controllers;

import java.awt.Component;
import java.awt.Dialog;
import java.awt.Window;
import java.awt.event.ActionListener;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.List;

import javax.swing.JButton;
import javax.swing.Timer;

import forge.game.GameView;
import forge.game.card.CardView;
import forge.gui.FThreads;
import forge.gui.framework.DragCell;
import forge.gui.framework.EDocID;
import forge.gui.framework.ICDoc;
import forge.gui.framework.IVDoc;
import forge.gui.framework.SDisplayUtil;
import forge.gui.framework.SRearrangingUtil;
import forge.localinstance.properties.ForgePreferences;
import forge.model.FModel;
import forge.screens.match.CMatchUI;
import forge.screens.match.views.FloatingPrompt;
import forge.screens.match.views.VPrompt;
import forge.toolbox.FSkin;
import forge.util.TextUtil;
import forge.view.FView;

/**
 * Controls the prompt panel in the match UI.
 *
 * <br><br><i>(C at beginning of class name denotes a control class.)</i>
 */
public class CPrompt implements ICDoc {
    /**
     * How long the prompt stays up after the game stops asking for input. The
     * engine briefly disables both buttons between consecutive priority passes;
     * without this the window would blink on and off several times a turn.
     */
    private static final int HIDE_DELAY_MS = 350;

    private final CMatchUI matchUI;
    private final VPrompt view;

    private FloatingPrompt floatingPrompt;
    private Timer hideTimer;

    public CPrompt(final CMatchUI matchUI) {
        this.matchUI = matchUI;
        this.view = new VPrompt(this);
    }

    /** Lazily creates the floating prompt window and lays the controls into it. */
    private FloatingPrompt getFloatingPrompt() {
        if (floatingPrompt == null) {
            floatingPrompt = new FloatingPrompt();
            view.populateInto(floatingPrompt.getContentPanel());
        }
        return floatingPrompt;
    }

    /**
     * Called whenever the set of enabled prompt buttons changes. Shows the
     * floating prompt while the game is waiting on the local player, and hides
     * it again (after a short delay) once it isn't.
     */
    public void setInputRequired(final boolean required) {
        FThreads.assertExecutedByEdt(true);
        if (!isFloatingEnabled()) {
            //a docked prompt is always on screen, so it has nothing to show or hide.
            //Outline its cell instead, so it still announces that it wants an answer.
            setDockedHighlight(required);
            return;
        }
        if (hideTimer != null) {
            hideTimer.stop();
            hideTimer = null;
        }
        if (required) {
            final FloatingPrompt prompt = getFloatingPrompt();
            if (!prompt.isVisible()) {
                prompt.setVisible(true);
            }
            prompt.toFront();
        } else if (floatingPrompt != null && floatingPrompt.isVisible()) {
            hideTimer = new Timer(HIDE_DELAY_MS, e -> {
                hideTimer.stop();
                hideTimer = null;
                if (!matchUI.isInputButtonEnabled()) {
                    floatingPrompt.setVisible(false);
                }
            });
            hideTimer.setRepeats(false);
            hideTimer.start();
        }
    }

    /**
     * Puts the prompt where {@link ForgePreferences.FPref#UI_FLOATING_PROMPT} says it belongs.
     * Called after the saved layout has been loaded, which re-docks every
     * registered doc regardless of which mode is in force.
     */
    public void applyDockMode() {
        if (isFloatingEnabled()) {
            undockPrompt();
        } else {
            dockPrompt();
        }
    }

    /**
     * Pulls the prompt out of whatever dock cell the saved layout put it in, so
     * it exists only as the floating window. Collapses the cell if the prompt
     * was the only thing in it, mirroring how floating zones undock.
     */
    private void undockPrompt() {
        final DragCell cell = view.getParentCell();
        if (cell != null) {
            cell.removeDoc(view);
            view.setParentCell(null);
            if (cell.getDocs().isEmpty() && SRearrangingUtil.fillGapIfPossible(cell)) {
                FView.SINGLETON_INSTANCE.removeDragCell(cell);
            }
        }
        // Always re-populate: docking calls VPrompt.populate(), which reparents
        // the shared button/message instances into the cell body. Adding them to
        // the floating window's panel moves them back.
        final FloatingPrompt prompt = getFloatingPrompt();
        view.populateInto(prompt.getContentPanel());
    }

    /**
     * Makes sure the prompt has a cell to live in. The saved layout normally
     * supplies one, but a layout written while the prompt was floating has no
     * entry for it at all — so fall back to a cell that does exist, rather than
     * leaving the match with no way to respond to it.
     */
    private void dockPrompt() {
        closeFloatingPrompt();
        if (view.getParentCell() != null) {
            return;
        }
        final IVDoc<? extends ICDoc> anchor = EDocID.BUTTON_DOCK.getDoc();
        DragCell target = anchor == null ? null : anchor.getParentCell();
        if (target == null) {
            final List<DragCell> cells = FView.SINGLETON_INSTANCE.getDragCells();
            if (cells.isEmpty()) { return; }
            target = cells.get(0);
        }
        target.addDoc(view);
        target.setSelected(view);
    }

    /**
     * Tints the docked prompt's cell in the prompt accent while the game is
     * waiting on this player — the standing equivalent of the orange frame the
     * floating window wears, and of the outline a field gets while its player is
     * being waited on.
     */
    private void setDockedHighlight(final boolean on) {
        final DragCell cell = view.getParentCell();
        if (cell != null) {
            cell.setBorderOverride(on ? VPrompt.ACCENT : null);
        }
    }

    /** Whether the prompt shows as a floating window rather than a docked panel. */
    private boolean isFloatingEnabled() {
        return FModel.getPreferences().getPrefBoolean(ForgePreferences.FPref.UI_FLOATING_PROMPT);
    }

    /** Tears the floating prompt down at the end of a match. */
    public void closeFloatingPrompt() {
        if (hideTimer != null) {
            hideTimer.stop();
            hideTimer = null;
        }
        setDockedHighlight(false); //no-op while floating; the match is over either way
        if (floatingPrompt != null) {
            floatingPrompt.setVisible(false);
            floatingPrompt.dispose();
            floatingPrompt = null;
        }
    }

    public final CMatchUI getMatchUI() {
        return matchUI;
    }
    public final VPrompt getView() {
        return view;
    }

    private Component lastFocusedButton = null;

    private final ActionListener actCancel = evt -> selectButtonCancel();
    private final ActionListener actOK = evt -> selectButtonOk();

    private final WindowAdapter focusOKButtonOnDialogClose = new WindowAdapter() {
        @Override
        public void windowClosed(WindowEvent evt) {
            view.getBtnOK().requestFocusInWindow();
        }
    };

    private final PropertyChangeListener focusOnEnable = new PropertyChangeListener() {
        @Override
        public void propertyChange(PropertyChangeEvent evt) {
            if (lastFocusedButton == null || lastFocusedButton == view.getBtnOK()) {
                // Attempt to resolve sporadic button focus issues when dialogs are shown.
                Dialog activeDialog = getActiveDialog(true);
                if (activeDialog != null) {
                    // If this dialog already has our listener, remove it
                    activeDialog.removeWindowListener(focusOKButtonOnDialogClose);
                    activeDialog.addWindowListener(focusOKButtonOnDialogClose);
                }

                // Focus the OK button when it becomes enabled
                boolean isEnabled = (Boolean) evt.getNewValue();
                if (isEnabled) {
                    view.getBtnOK().requestFocusInWindow();
                }
            }
        }
    };

    private final FocusListener onFocus = new FocusAdapter() {
        @Override
        public void focusGained(final FocusEvent e) {
            if (null != view.getParentCell() && view == view.getParentCell().getSelected()) {
                // only record focus changes when we're showing -- otherwise it is due to a tab visibility change
                lastFocusedButton = e.getComponent();
            }
        }
    };

    private void _initButton(final JButton button, final ActionListener onClick) {
        // remove to ensure listeners don't accumulate over many initializations
        button.removeActionListener(onClick);
        button.addActionListener(onClick);
        button.removeFocusListener(onFocus);
        button.addFocusListener(onFocus);
        if (button == view.getBtnOK()) {
            button.removePropertyChangeListener("enabled", focusOnEnable);
            button.addPropertyChangeListener("enabled", focusOnEnable);
        }
    }

    @Override
    public void initialize() {
        _initButton(view.getBtnCancel(), actCancel);
        _initButton(view.getBtnOK(), actOK);
    }

    private static Dialog getActiveDialog(boolean modalOnly)
    {
        Window[] windows = Window.getWindows();
        if (windows != null) {
            for (Window w : windows) {
                if (w.isShowing() && w instanceof Dialog && (!modalOnly || ((Dialog)w).isModal())) {
                    return (Dialog)w;
                }
            }
        }
        return null;
    }

    private void selectButtonOk() {
        matchUI.getGameController().selectButtonOk();
    }

    private void selectButtonCancel() {
        matchUI.getGameController().selectButtonCancel();
    }

    public void setMessage(final String s0, final CardView card) {
        view.getTarMessage().setText(colorEmphasis(FSkin.encodeSymbols(spaceRows(s0), false)));
        view.setCardView(card);
    }

    /**
     * Paints the runs the message marked as carrying its answer — the phase names,
     * the player holding priority — in the prompt accent, so the eye can pick them
     * out of the labels without reading the whole panel. Runs after
     * {@link FSkin#encodeSymbols}, which emits HTML of its own but leaves the
     * control characters alone.
     */
    private static String colorEmphasis(final String message) {
        if (message == null) {
            return null;
        }
        return message
                .replace(String.valueOf(TextUtil.EMPHASIS_START),
                        "<span style=\"color:" + accentHex() + "\">")
                .replace(String.valueOf(TextUtil.EMPHASIS_END), "</span>");
    }

    private static String accentHex() {
        return String.format("#%02X%02X%02X",
                VPrompt.ACCENT.getRed(), VPrompt.ACCENT.getGreen(), VPrompt.ACCENT.getBlue());
    }

    /**
     * Puts a blank line between the prompt's rows — priority, phase, next phase —
     * which read as a wall of text otherwise. Runs of line breaks collapse into one
     * gap, so messages that already separate their paragraphs don't end up with
     * several blank lines. Must run before {@link FSkin#encodeSymbols}, which turns
     * every line break into a {@code <br>} of its own.
     */
    private static String spaceRows(final String message) {
        return message.replaceAll("[\r\n]+", "\n\n");
    }

    /**
     * Invoke a flashing animation on the prompt.
     */
    public void remind() {
        if (isFloating()) {
            floatingPrompt.flash(5, 80);
        } else {
            SDisplayUtil.remind(view);
        }
    }

    public void alert() {
        if (FModel.getPreferences().getPrefBoolean(ForgePreferences.FPref.UI_REMIND_ON_PRIORITY)) {
            if (isFloating()) {
                floatingPrompt.flash(15, 30);
            } else {
                SDisplayUtil.remind(view, 15, 30);
            }
        }
    }

    /**
     * True when the prompt lives in the floating window rather than a dock cell.
     * {@link SDisplayUtil#remind} dereferences the parent cell, so it must not be
     * called once the prompt has been undocked.
     */
    private boolean isFloating() {
        return floatingPrompt != null && view.getParentCell() == null;
    }

    @Override
    public void register() {
    }

    @Override
    public void update() {
        // set focus back to button that last had it
        if (null != lastFocusedButton) {
            lastFocusedButton.requestFocusInWindow();
        }
    }

    public void updateText() {
        FThreads.assertExecutedByEdt(true);
        final GameView game = matchUI.getGameView();
        if (game == null) {
            return;
        }
        //The turn number and turn player used to be repeated in the message body;
        //the header is where they belong, so the body can lead with the ask. Game
        //number and game type stay in the tooltip — they don't change often enough
        //to earn room in a header that's read every priority pass.
        final String turnPlayer = game.getPlayerTurn() == null ? null : game.getPlayerTurn().getName();
        final String text = String.format("Turn: %d%s", game.getTurn(),
                turnPlayer == null ? "" : " (" + turnPlayer + ")");
        view.getLblGames().setText(text);
        view.getLblGames().setToolTipText(String.format("%s: Game #%d of %d, turn %d", game.getGameType(), game.getNumPlayedGamesInMatch() + 1, game.getNumGamesInMatch(), game.getTurn()));
    }
}
