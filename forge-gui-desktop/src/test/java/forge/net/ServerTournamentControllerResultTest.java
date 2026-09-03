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
 * {@link ServerTournamentController#resolveMatchOutcome} maps a match outcome to
 * a pairing result without ever silently awarding a win to the first player:
 * null winner → DRAW, named winner → WIN, unknown winner name → VOID. The online
 * tournament does not support draws, so {@link ServerTournamentController#resolveDecisive}
 * wraps that mapping with a fallback that always records a decisive winner.
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

    // resolveDecisive: the online tournament does not support draws — a null or
    // unmatched winner falls back to the first paired player so every round records
    // a winner (the pre-regression "always a winner" behavior).

    @Test
    public void testNullWinnerFallsBackToFirstPlayer() {
        TournamentPairing pairing = pairingOf("Alice", "Bob");
        TournamentPairing.MatchResult result =
                ServerTournamentController.resolveDecisive(pairing, null);

        Assert.assertEquals(result, TournamentPairing.MatchResult.WIN);
        Assert.assertEquals(pairing.getWinner().getPlayer().getName(), "Alice",
                "draws are unsupported: a null winner must fall back to the first player, not record a tie");
    }

    @Test
    public void testUnmatchedWinnerFallsBackToFirstPlayer() {
        TournamentPairing pairing = pairingOf("Alice", "Bob");
        TournamentPairing.MatchResult result =
                ServerTournamentController.resolveDecisive(pairing, new LobbyPlayerHuman("Stranger"));

        Assert.assertEquals(result, TournamentPairing.MatchResult.WIN);
        Assert.assertEquals(pairing.getWinner().getPlayer().getName(), "Alice",
                "an unmatched winner name must fall back to the first player");
    }

    @Test
    public void testMatchedWinnerStillWins() {
        TournamentPairing pairing = pairingOf("Alice", "Bob");
        TournamentPairing.MatchResult result =
                ServerTournamentController.resolveDecisive(pairing, new LobbyPlayerHuman("Bob"));

        Assert.assertEquals(result, TournamentPairing.MatchResult.WIN);
        Assert.assertEquals(pairing.getWinner().getPlayer().getName(), "Bob",
                "a real match winner must still be awarded the match");
    }
}
