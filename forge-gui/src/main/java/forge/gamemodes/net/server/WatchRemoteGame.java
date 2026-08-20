package forge.gamemodes.net.server;

import forge.LobbyPlayer;
import forge.game.Game;
import forge.game.card.CardView;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbilityView;
import forge.gui.interfaces.IGuiGame;
import forge.interfaces.IDevModeCheats;
import forge.player.PlayerControllerHuman;
import forge.util.ITriggerEvent;

import java.util.List;

/**
 * Spectator controller for remote clients observing a match.
 * Network equivalent of WatchLocalGame — receives game events
 * but cannot make any game decisions.
 */
public class WatchRemoteGame extends PlayerControllerHuman {

    public WatchRemoteGame(final Game game0, final LobbyPlayer lp, final IGuiGame gui) {
        super(game0, null, lp);
        setGui(gui);
    }

    @Override
    public void updateAchievements() {
    }

    @Override
    public boolean canUndoLastAction() {
        return false;
    }

    @Override
    public void undoLastAction() {
    }

    @Override
    public void selectButtonOk() {
    }

    @Override
    public void selectButtonCancel() {
    }

    @Override
    public void passPriority() {
    }

    @Override
    public void useMana(final byte mana) {
    }

    @Override
    public void selectPlayer(final PlayerView player, final ITriggerEvent triggerEvent) {
    }

    @Override
    public boolean selectCard(final CardView card, final List<CardView> otherCardViewsToSelect,
            final ITriggerEvent triggerEvent) {
        return false;
    }

    @Override
    public void selectAbility(final SpellAbilityView sa) {
    }

    @Override
    public void alphaStrike() {
    }

    @Override
    public boolean canPlayUnlimitedLands() {
        return false;
    }

    @Override
    public IDevModeCheats cheat() {
        return IDevModeCheats.NO_CHEAT;
    }

    @Override
    public void awaitNextInput() {
    }

    @Override
    public void cancelAwaitNextInput() {
    }
}
