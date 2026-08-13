package forge.screens.match;

import forge.game.GameView;
import forge.gamemodes.match.NextGameDecision;
import forge.gui.SOverlayUtils;
import forge.gui.framework.FScreen;
import forge.Singletons;
import forge.interfaces.IGameController;
import forge.toolbox.FSkin;
import forge.toolbox.FSkin.Colors;
import forge.toolbox.FSkin.SkinColor;
import forge.toolbox.FSkin.SkinnedLabel;

import javax.swing.SwingConstants;
import java.awt.Dimension;

/**
 * WinLose controller for network tournament matches.
 * - No restart option (tournament matches can't be restarted)
 * - Auto-continues within a match (best-of-N)
 * - Quit means leave the tournament match
 */
public class NetworkTournamentWinLose extends ControlWinLose {

    private static final SkinColor FORE_COLOR = FSkin.getColor(Colors.CLR_TEXT);
    private static final String CONSTRAINTS_TITLE = "w 95%!, gap 0 0 20px 10px";
    private static final String CONSTRAINTS_TEXT = "w 95%!, h 180px!, gap 0 0 0 20px";

    public NetworkTournamentWinLose(final ViewWinLose view0, final GameView game0, final CMatchUI matchUI) {
        super(view0, game0, matchUI);
    }

    @Override
    public void addListeners() {
        getView().getBtnContinue().addActionListener(e -> actionOnContinue());

        getView().getBtnRestart().setEnabled(false);

        getView().getBtnQuit().addActionListener(e -> {
            actionOnQuit();
            ((javax.swing.JButton) e.getSource()).setEnabled(false);
        });
    }

    @Override
    public void actionOnContinue() {
        nextGameAction(NextGameDecision.CONTINUE);
    }

    @Override
    public void actionOnRestart() {
        // No restart in tournament matches
    }

    @Override
    public void actionOnQuit() {
        nextGameAction(NextGameDecision.QUIT);
        Singletons.getControl().setCurrentScreen(FScreen.HOME_SCREEN);
    }

    @Override
    public boolean populateCustomPanel() {
        final ViewWinLose view = getView();

        final SkinnedLabel lblInfo = new SkinnedLabel("Tournament match in progress.");
        lblInfo.setHorizontalAlignment(SwingConstants.CENTER);
        lblInfo.setFont(FSkin.getRelativeFont(17));
        lblInfo.setForeground(FORE_COLOR);

        view.getPnlCustom().add(new TitleLabel("Tournament"), CONSTRAINTS_TITLE);
        view.getPnlCustom().add(lblInfo, CONSTRAINTS_TEXT);

        return true;
    }

    private void nextGameAction(final NextGameDecision decision) {
        SOverlayUtils.hideOverlay();
        saveOptions();
        for (final IGameController controller : matchUI.getOriginalGameControllers()) {
            controller.nextGameDecision(decision);
        }
    }

    @SuppressWarnings("serial")
    private class TitleLabel extends SkinnedLabel {
        TitleLabel(final String msg) {
            super(msg);
            setFont(FSkin.getRelativeFont(18));
            setPreferredSize(new Dimension(200, 40));
            setHorizontalAlignment(SwingConstants.CENTER);
            setForeground(FORE_COLOR);
            setBorder(new FSkin.MatteSkinBorder(1, 0, 1, 0, FORE_COLOR));
        }
    }
}
