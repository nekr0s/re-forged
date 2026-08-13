package forge.net;

import forge.ai.LobbyPlayerAi;
import forge.gamemodes.tournament.system.TournamentPairing;
import forge.gamemodes.tournament.system.TournamentPlayer;
import forge.gamemodes.tournament.system.TournamentRoundRobin;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TournamentLogicTest {

    @Test
    public void testTournamentPlayerScore() {
        TournamentPlayer tp = new TournamentPlayer(new LobbyPlayerAi("Test", null));
        Assert.assertEquals(tp.getScore(), 0, "Initial score should be 0");
        tp.addWin();
        Assert.assertEquals(tp.getScore(), 3, "Score after 1 win should be 3");
        tp.addBye();
        Assert.assertEquals(tp.getScore(), 6, "Score after 1 win + 1 bye should be 6");
        tp.addTie();
        Assert.assertEquals(tp.getScore(), 7, "Score after 1 win + 1 bye + 1 tie should be 7");
    }

    @Test
    public void testOmwCalculationWithPlayerList() {
        TournamentPlayer tpAlice = new TournamentPlayer(new LobbyPlayerAi("Alice", null), 0);
        TournamentPlayer tpBob = new TournamentPlayer(new LobbyPlayerAi("Bob", null), 1);
        TournamentPlayer tpCharlie = new TournamentPlayer(new LobbyPlayerAi("Charlie", null), 2);
        TournamentPlayer tpDiana = new TournamentPlayer(new LobbyPlayerAi("Diana", null), 3);

        List<TournamentPlayer> allPlayers =
            Arrays.asList(tpAlice, tpBob, tpCharlie, tpDiana);

        // Alice beats Bob and Charlie; Bob beats Diana
        tpAlice.addWin(); tpAlice.addOpponentIndex(1);
        tpBob.addLoss();  tpBob.addOpponentIndex(0);
        tpAlice.addWin(); tpAlice.addOpponentIndex(2);
        tpCharlie.addLoss(); tpCharlie.addOpponentIndex(0);
        tpBob.addWin(); tpBob.addOpponentIndex(3);
        tpDiana.addLoss(); tpDiana.addOpponentIndex(1);

        // Alice's opponents: Bob (1W/1L = 0.5), Charlie (0W/1L = 0.0)
        // OMW = (0.5 + 0.0) / 2 = 0.25
        double aliceOmw = tpAlice.getOMW(allPlayers);
        Assert.assertTrue(aliceOmw > 0.24 && aliceOmw < 0.26,
            "Alice OMW should be ~0.25, got " + aliceOmw);

        // Bob's opponents: Alice (2W/0L = 1.0), Diana (0W/1L = 0.0)
        // OMW = (1.0 + 0.0) / 2 = 0.5
        double bobOmw = tpBob.getOMW(allPlayers);
        Assert.assertTrue(bobOmw > 0.49 && bobOmw < 0.51,
            "Bob OMW should be ~0.5, got " + bobOmw);
    }

    @Test
    public void testRoundRobinPairingsFor4Players() {
        List<TournamentPlayer> players = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            players.add(new TournamentPlayer(new LobbyPlayerAi("P" + i, null), i));
        }

        TournamentRoundRobin rr = new TournamentRoundRobin(3, players);

        Assert.assertEquals(rr.getActivePairings().size(), 2,
            "Round 1 should have 2 pairings for 4 players");

        for (TournamentPairing p : new ArrayList<>(rr.getActivePairings())) {
            p.setWinner(p.getPairedPlayers().get(0));
            rr.reportMatchCompletion(p);
        }

        Assert.assertEquals(rr.getActiveRound(), 2, "Should be on round 2");
        Assert.assertEquals(rr.getActivePairings().size(), 2,
            "Round 2 should have 2 pairings");

        for (TournamentPairing p : new ArrayList<>(rr.getActivePairings())) {
            p.setWinner(p.getPairedPlayers().get(0));
            rr.reportMatchCompletion(p);
        }

        Assert.assertEquals(rr.getActiveRound(), 3, "Should be on round 3");

        for (TournamentPairing p : new ArrayList<>(rr.getActivePairings())) {
            p.setWinner(p.getPairedPlayers().get(0));
            rr.reportMatchCompletion(p);
        }

        Assert.assertTrue(rr.isTournamentOver(), "Tournament should be over after 3 rounds");
    }

    @Test
    public void testByeHandlingFor3Players() {
        List<TournamentPlayer> players = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            players.add(new TournamentPlayer(new LobbyPlayerAi("P" + i, null), i));
        }

        TournamentRoundRobin rr = new TournamentRoundRobin(3, players);

        int byes = 0;
        int real = 0;
        for (TournamentPairing p : rr.getActivePairings()) {
            if (p.isBye()) byes++;
            else real++;
        }
        Assert.assertTrue(byes >= 1, "Round with 3 players should have at least 1 bye");
        Assert.assertEquals(real + byes, 2, "Should have 2 total pairings");
    }
}
