package forge.net;

import forge.LobbyPlayer;
import forge.ai.LobbyPlayerAi;
import forge.gamemodes.net.server.ServerTournamentController;
import forge.gamemodes.tournament.system.TournamentPairing;
import forge.gamemodes.tournament.system.TournamentPlayer;
import forge.player.LobbyPlayerHuman;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.List;

/**
 * Gap 8: winner-determination policy in {@link ServerTournamentController}.
 * A match outcome must map to a pairing result without ever silently awarding
 * a win to the first player: null winner → DRAW, named winner → WIN, unknown
 * winner name → VOID.
 */
public class ServerTournamentControllerResultTest {

    private static TournamentPairing pairingOf(String name0, String name1) {
        TournamentPlayer p0 = new TournamentPlayer(new LobbyPlayerAi(name0, null), 0);
        TournamentPlayer p1 = new TournamentPlayer(new LobbyPlayerAi(name1, null), 1);
        return new TournamentPairing(1, Arrays.asList(p0, p1));
    }

    @Test
    public void testNullMatchWinnerMarksDraw() {
        TournamentPairing pairing = pairingOf("Alice", "Bob");
        TournamentPairing.MatchResult result =
                ServerTournamentController.resolveMatchOutcome(pairing, null);

        Assert.assertEquals(result, TournamentPairing.MatchResult.DRAW);
        Assert.assertTrue(pairing.isDraw());
        Assert.assertNull(pairing.getWinner());
    }

    @Test
    public void testNameMatchMarksWinner() {
        TournamentPairing pairing = pairingOf("Alice", "Bob");
        LobbyPlayer matchWinner = new LobbyPlayerHuman("Bob");

        TournamentPairing.MatchResult result =
                ServerTournamentController.resolveMatchOutcome(pairing, matchWinner);

        Assert.assertEquals(result, TournamentPairing.MatchResult.WIN);
        Assert.assertEquals(pairing.getWinner().getPlayer().getName(), "Bob");
        Assert.assertFalse(pairing.isDraw());
        Assert.assertFalse(pairing.isVoid());
    }

    @Test
    public void testUnknownWinnerNameMarksVoid() {
        TournamentPairing pairing = pairingOf("Alice", "Bob");
        LobbyPlayer matchWinner = new LobbyPlayerHuman("Stranger");

        TournamentPairing.MatchResult result =
                ServerTournamentController.resolveMatchOutcome(pairing, matchWinner);

        Assert.assertEquals(result, TournamentPairing.MatchResult.VOID);
        Assert.assertTrue(pairing.isVoid());
        Assert.assertNull(pairing.getWinner());
    }

    @Test
    public void testSinglePlayerPairingUnchanged() {
        // Guard: a degenerate single-player pairing keeps its old "winner = only player" path.
        List<TournamentPlayer> solo = Arrays.asList(new TournamentPlayer(new LobbyPlayerAi("Only", null), 0));
        TournamentPairing pairing = new TournamentPairing(1, solo);
        pairing.setWinner(solo.get(0));
        Assert.assertEquals(pairing.getResult(), TournamentPairing.MatchResult.WIN);
    }
}
