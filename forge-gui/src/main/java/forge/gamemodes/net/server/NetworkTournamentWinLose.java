package forge.gamemodes.net.server;

import forge.game.GameView;
import forge.gamemodes.match.NextGameDecision;

/**
 * Handles WinLose decisions for network tournament matches.
 * Instead of the standard Continue/Restart/Quit flow:
 * - If match is still ongoing (best-of-N): auto-continue to next game
 * - If match is complete: the ServerTournamentController's polling loop
 *   will detect completion and advance the tournament bracket
 * - No manual restart option (tournament matches can't be restarted)
 */
public class NetworkTournamentWinLose {

    private final String matchId;
    private final ServerTournamentController controller;
    private final GameView gameView;

    public NetworkTournamentWinLose(String matchId, ServerTournamentController controller, GameView gameView) {
        this.matchId = matchId;
        this.controller = controller;
        this.gameView = gameView;
    }

    /**
     * Determine what to do after a game ends in a tournament match.
     *
     * @return the NextGameDecision to apply
     */
    public NextGameDecision determineNextAction() {
        if (gameView.isMatchOver()) {
            String winnerName = gameView.getWinningPlayerName();
            // The ServerTournamentController uses a 500ms polling loop to
            // detect match completions and advance the bracket. This method
            // returns QUIT so the HostedMatch fires its onMatchOver callback,
            // which the controller's poll will pick up on the next cycle.
            return NextGameDecision.QUIT;
        }
        return NextGameDecision.CONTINUE;
    }

    public String getMatchId() { return matchId; }
    public ServerTournamentController getController() { return controller; }
    public GameView getGameView() { return gameView; }
}
