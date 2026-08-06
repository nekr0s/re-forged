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
package forge.gamemodes.match.input;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityView;
import forge.localinstance.properties.ForgePreferences;
import forge.model.FModel;
import forge.player.PlayerControllerHuman;
import forge.util.ITriggerEvent;
import forge.util.Localizer;
import forge.util.TextUtil;

import java.util.List;

/**
 * <p>
 * Abstract Input class.
 * </p>
 *
 * @author Forge
 * @version $Id: InputBase.java 24769 2014-02-09 13:56:04Z Hellfish $
 */
public abstract class InputBase implements java.io.Serializable, Input {
    private static final long serialVersionUID = -2531867688249685076L;

    private final PlayerControllerHuman controller;
    public InputBase(final PlayerControllerHuman controller0) {
        controller = controller0;
    }
    public final PlayerControllerHuman getController() {
        return controller;
    }
    @Override
    public PlayerView getOwner() {
        final Player owner = getController().getPlayer();
        return owner == null ? null : owner.getView();
    }

    private boolean finished = false;
    protected final boolean isFinished() { return finished; }
    protected final void setFinished() {
        finished = true;

        if (allowAwaitNextInput()) {
            controller.awaitNextInput();
        }
    }

    protected boolean allowAwaitNextInput() {
        return false;
    }

    // showMessage() is always the first method called
    @Override
    public final void showMessageInitial() {
        finished = false;
        controller.cancelAwaitNextInput();
        showMessage();
    }

    protected abstract void showMessage();

    @Override
    public final void selectPlayer(final Player player, final ITriggerEvent triggerEvent) {
        if (isFinished()) { return; }
        onPlayerSelected(player, triggerEvent);
    }

    @Override
    public boolean selectAbility(final SpellAbility ab) {
        return false;
    }

    @Override
    public final void selectButtonCancel() {
        if (isFinished()) { return; }
        onCancel();
    }

    @Override
    public final void selectButtonOK() {
        if (isFinished()) { return; }
        onOk();
    }

    @Override
    public final boolean selectCard(final Card c, final List<Card> otherCardsToSelect, final ITriggerEvent triggerEvent) {
        if (isFinished()) { return false; }
        return onCardSelected(c, otherCardsToSelect, triggerEvent);
    }

    protected boolean onCardSelected(final Card c, final List<Card> otherCardsToSelect, final ITriggerEvent triggerEvent) {
        return false;
    }
    protected void onPlayerSelected(final Player player, final ITriggerEvent triggerEvent) {}
    protected void onCancel() {}
    protected void onOk() {}

    protected final void showMessage(final String message) {
        showMessage(message, (CardView) null);
    }
    protected final void showMessage(final String message, final SpellAbilityView sav) {
        showMessage(message, sav.getHostCard());
    }
    protected final void showMessage(final String message, final CardView card) {
        controller.getGui().showPromptMessage(getOwner(), message, card);
    }

    protected String getTurnPhasePriorityMessage(final Game game) {
        final PhaseHandler ph = game.getPhaseHandler();
        final StringBuilder sb = new StringBuilder();
        Localizer localizer = Localizer.getInstance();

        // Lead with what the game wants, not with what it already shows elsewhere:
        // the turn number and turn player live in the prompt's header.
        final Player priority = ph.getPriorityPlayer();
        if (priority != null && priority == getController().getPlayer()) {
            sb.append(localizer.getMessage("lblYouHavePriority"));
        } else {
            sb.append(localizer.getMessage("lblPlayerHasPriority", TextUtil.emphasize(priority)));
        }
        sb.append("\n");

        sb.append(localizer.getMessage("lblCurrentPhase")).append(": ")
                .append(TextUtil.emphasize(ph.getPhase().nameForUi));
        if (!game.isNeitherDayNorNight()) {
            sb.append("  [").append(localizer.getMessage(game.isDay() ? "lblDay" : "lblNight")).append("]");
        }
        sb.append("\n");

        final PhaseType nextStop = getNextStop(game);
        sb.append(localizer.getMessage("lblNextPhase")).append(": ")
                .append(TextUtil.emphasize(nextStop == null
                        ? localizer.getMessage("lblEndOfTurn") : nextStop.nameForUi));

        if (!game.getStack().isEmpty()) {
            //an empty stack is the normal case and says nothing worth a line of its own
            sb.append("\n").append(localizer.getMessage("lblStack")).append(": ")
                    .append(TextUtil.emphasize(game.getStack().size() + " " + localizer.getMessage("lbltoResolve")));
        }
        if (FModel.getPreferences().getPrefBoolean(ForgePreferences.FPref.UI_SHOW_STORM_COUNT_IN_PROMPT)) {
            int stormCount = game.getView().getStormCount();
            if (stormCount > 0) {
                sb.append("\n").append(localizer.getMessage("lblStormCount")).append(": ").append(stormCount);
            }
        }

        if (controller.macros() != null) {
            boolean isRecording = controller.macros().isRecording();
            String pbText = controller.macros().playbackText();
            if (pbText != null) {
                sb.append("\n");
                if (isRecording) {
                    sb.append("Macro Recording -- ");
                } else {
                    sb.append("Macro Playback -- ");
                }

                sb.append(pbText);
            } else if (isRecording) {
                sb.append("\n").append("Macro Recording -- ");
            }
        }

        return sb.toString();
    }

    /**
     * The phase this player will next be stopped at if everyone simply passes —
     * that is, the first upcoming phase whose indicator chip is still switched on
     * for the current turn player. Skipped phases are exactly the ones the engine
     * declines to prompt for (see
     * {@link forge.player.PlayerControllerHuman#getAbilityToPlay}), so this is the
     * same test, run forwards.
     * <p>
     * A prediction, not a promise: an opponent responding hands priority back
     * sooner, and the engine skips further phases for rules reasons this can't see
     * (a replacement effect skipping a phase, an extra phase pushed onto the
     * stack). The common rules skip — no attackers means no damage steps — is
     * checked, since it would otherwise be wrong every turn nobody attacks.
     *
     * @return the phase to name, or {@code null} if the turn ends first
     */
    private PhaseType getNextStop(final Game game) {
        final PhaseHandler ph = game.getPhaseHandler();
        final Player turnPlayer = ph.getPlayerTurn();
        if (turnPlayer == null || ph.getPhase() == null) {
            return null;
        }
        // Chips are per player field, so a walk past the turn boundary would be
        // reading the wrong player's settings. Stop there and say so instead.
        final boolean topsy = turnPlayer.isPhasesReversed();
        final PlayerView turnPlayerView = turnPlayer.getView();
        final boolean noAttackers = ph.getCombat() == null || ph.getCombat().getAttackers().isEmpty();

        PhaseType phase = ph.getPhase();
        while (!PhaseType.isLast(phase, topsy)) {
            phase = PhaseType.getNext(phase, topsy);
            if (noAttackers && (phase == PhaseType.COMBAT_FIRST_STRIKE_DAMAGE
                    || phase == PhaseType.COMBAT_DAMAGE)) {
                continue;
            }
            if (!controller.isUiSetToSkipPhase(turnPlayerView, phase)) {
                return phase;
            }
        }
        return null;
    }
}
