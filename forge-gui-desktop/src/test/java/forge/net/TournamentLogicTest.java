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

    @Test
    public void testStandingsSortByScoreThenOMW() {
        // 4 players: Alice 3-0, Bob 2-1, Charlie 1-2, Diana 0-3
        TournamentPlayer tpAlice = new TournamentPlayer(new LobbyPlayerAi("Alice", null), 0);
        TournamentPlayer tpBob = new TournamentPlayer(new LobbyPlayerAi("Bob", null), 1);
        TournamentPlayer tpCharlie = new TournamentPlayer(new LobbyPlayerAi("Charlie", null), 2);
        TournamentPlayer tpDiana = new TournamentPlayer(new LobbyPlayerAi("Diana", null), 3);

        List<TournamentPlayer> all = Arrays.asList(tpAlice, tpBob, tpCharlie, tpDiana);

        // Alice: beat Bob, Charlie, Diana
        tpAlice.addWin(); tpAlice.addOpponentIndex(1);
        tpAlice.addWin(); tpAlice.addOpponentIndex(2);
        tpAlice.addWin(); tpAlice.addOpponentIndex(3);

        // Bob: lost to Alice, beat Charlie, beat Diana
        tpBob.addLoss(); tpBob.addOpponentIndex(0);
        tpBob.addWin();  tpBob.addOpponentIndex(2);
        tpBob.addWin();  tpBob.addOpponentIndex(3);

        // Charlie: lost to Alice, lost to Bob, beat Diana
        tpCharlie.addLoss(); tpCharlie.addOpponentIndex(0);
        tpCharlie.addLoss(); tpCharlie.addOpponentIndex(1);
        tpCharlie.addWin();  tpCharlie.addOpponentIndex(3);

        // Diana: lost to all
        tpDiana.addLoss(); tpDiana.addOpponentIndex(0);
        tpDiana.addLoss(); tpDiana.addOpponentIndex(1);
        tpDiana.addLoss(); tpDiana.addOpponentIndex(2);

        // Sort by score desc, then OMW desc
        List<TournamentPlayer> sorted = new ArrayList<>(all);
        sorted.sort((a, b) -> {
            int scoreCmp = Integer.compare(b.getScore(), a.getScore());
            if (scoreCmp != 0) return scoreCmp;
            return Double.compare(b.getOMW(all), a.getOMW(all));
        });

        Assert.assertEquals(sorted.get(0).getPlayer().getName(), "Alice");
        Assert.assertEquals(sorted.get(1).getPlayer().getName(), "Bob");
        Assert.assertEquals(sorted.get(2).getPlayer().getName(), "Charlie");
        Assert.assertEquals(sorted.get(3).getPlayer().getName(), "Diana");
    }

    @Test
    public void testTieScenario() {
        // 6 players, 3 rounds — A and B both 2-1 but A has better OMW
        TournamentPlayer tpA = new TournamentPlayer(new LobbyPlayerAi("A", null), 0);
        TournamentPlayer tpB = new TournamentPlayer(new LobbyPlayerAi("B", null), 1);
        TournamentPlayer tpC = new TournamentPlayer(new LobbyPlayerAi("C", null), 2);
        TournamentPlayer tpD = new TournamentPlayer(new LobbyPlayerAi("D", null), 3);
        TournamentPlayer tpE = new TournamentPlayer(new LobbyPlayerAi("E", null), 4);
        TournamentPlayer tpF = new TournamentPlayer(new LobbyPlayerAi("F", null), 5);

        List<TournamentPlayer> all = Arrays.asList(tpA, tpB, tpC, tpD, tpE, tpF);

        // Round 1: A>C, B>D, E>F
        tpA.addWin();  tpA.addOpponentIndex(2);  tpC.addLoss(); tpC.addOpponentIndex(0);
        tpB.addWin();  tpB.addOpponentIndex(3);  tpD.addLoss(); tpD.addOpponentIndex(1);
        tpE.addWin();  tpE.addOpponentIndex(5);  tpF.addLoss(); tpF.addOpponentIndex(4);

        // Round 2: A>D, E>B, C>F
        tpA.addWin();  tpA.addOpponentIndex(3);  tpD.addLoss(); tpD.addOpponentIndex(0);
        tpE.addWin();  tpE.addOpponentIndex(1);  tpB.addLoss(); tpB.addOpponentIndex(4);
        tpC.addWin();  tpC.addOpponentIndex(5);  tpF.addLoss(); tpF.addOpponentIndex(2);

        // Round 3: E>A, B>F, C>D
        tpE.addWin();  tpE.addOpponentIndex(0);  tpA.addLoss(); tpA.addOpponentIndex(4);
        tpB.addWin();  tpB.addOpponentIndex(5);  tpF.addLoss(); tpF.addOpponentIndex(1);
        tpC.addWin();  tpC.addOpponentIndex(3);  tpD.addLoss(); tpD.addOpponentIndex(2);

        // Records: A 2-1, B 2-1, C 2-1, D 0-3, E 3-0, F 0-3
        // A's OMW: C(2/3=0.667), D(0/3=0), E(3/3=1.0) = 0.556
        // B's OMW: D(0/3=0), E(3/3=1.0), F(0/3=0) = 0.333
        // A should rank higher than B due to better OMW

        double omwA = tpA.getOMW(all);
        double omwB = tpB.getOMW(all);
        Assert.assertTrue(omwA > omwB,
            "A should have better OMW than B. A=" + omwA + " B=" + omwB);

        List<TournamentPlayer> sorted = new ArrayList<>(all);
        sorted.sort((a, b) -> {
            int scoreCmp = Integer.compare(b.getScore(), a.getScore());
            if (scoreCmp != 0) return scoreCmp;
            return Double.compare(b.getOMW(all), a.getOMW(all));
        });

        Assert.assertEquals(sorted.get(0).getPlayer().getName(), "E", "E should be 1st (3-0)");
        Assert.assertEquals(sorted.get(1).getPlayer().getName(), "A", "A should be 2nd (2-1, best OMW)");
        Assert.assertEquals(sorted.get(2).getPlayer().getName(), "B", "B should be 3rd (2-1, 2nd OMW)");
    }
}
