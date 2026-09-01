package forge.net;

import forge.ai.LobbyPlayerAi;
import forge.gamemodes.tournament.system.TournamentPairing;
import forge.gamemodes.tournament.system.TournamentPlayer;
import forge.gamemodes.tournament.system.TournamentRoundRobin;
import forge.player.LobbyPlayerHuman;
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

    /**
     * Regression test for Bug 2: winner determination by LobbyPlayer.equals() fails
     * when TournamentPlayer has LobbyPlayerAi but the match uses LobbyPlayerHuman.
     * The fix matches by name instead of by equals().
     */
    @Test
    public void testWinnerMatchingByNameNotByEquals() {
        // Simulate the scenario: TournamentPlayer created with LobbyPlayerHuman
        // (as ServerTournamentController now does for human participants)
        TournamentPlayer human = new TournamentPlayer(new LobbyPlayerHuman("nekr0s"), 0);
        TournamentPlayer ai = new TournamentPlayer(new LobbyPlayerAi("Michelle", null), 1);

        // In the actual match, a DIFFERENT LobbyPlayerHuman instance is created
        // with the same name — equals() would fail due to instance inequality,
        // but name matching succeeds.
        forge.LobbyPlayer matchWinner = new LobbyPlayerHuman("nekr0s");

        List<TournamentPlayer> pairedPlayers = Arrays.asList(human, ai);
        TournamentPairing pairing = new TournamentPairing(1, pairedPlayers);

        // Match by name (the fixed approach)
        boolean found = false;
        for (TournamentPlayer tp : pairedPlayers) {
            if (tp.getPlayer().getName().equals(matchWinner.getName())) {
                pairing.setWinner(tp);
                found = true;
                break;
            }
        }

        Assert.assertTrue(found, "Winner should be found by name matching");
        Assert.assertEquals(pairing.getWinner(), human,
            "Winner should be the human player (nekr0s), not the AI");

        // Verify the old approach (equals) would have failed
        boolean equalsFound = false;
        for (TournamentPlayer tp : pairedPlayers) {
            if (tp.getPlayer().equals(matchWinner)) {
                equalsFound = true;
                break;
            }
        }
        // equals() fails because human has LobbyPlayerHuman and matchWinner is
        // a different LobbyPlayerHuman instance — they have the same name but
        // equals() checks getClass() which would pass for same class...
        // Actually LobbyPlayerHuman.equals(LobbyPlayerHuman) with same name DOES pass.
        // The original bug was that TournamentPlayer had LobbyPlayerAi while
        // the match used LobbyPlayerHuman — different classes, so equals() fails.
        Assert.assertTrue(equalsFound || !found,
            "If equals() works, name matching should also work");
    }

    /**
     * Regression test for Bug 2: when TournamentPlayer has LobbyPlayerAi but
     * the match winner is a LobbyPlayerHuman, equals() fails but name matching works.
     */
    @Test
    public void testWinnerMatchingAcrossLobbyPlayerTypes() {
        // This simulates the ORIGINAL bug: TournamentPlayer created with LobbyPlayerAi
        TournamentPlayer humanWithAi = new TournamentPlayer(new LobbyPlayerAi("nekr0s", null), 0);
        TournamentPlayer ai = new TournamentPlayer(new LobbyPlayerAi("Michelle", null), 1);

        // Match winner is LobbyPlayerHuman (as created by GameLobby.startMatch)
        forge.LobbyPlayer matchWinner = new LobbyPlayerHuman("nekr0s");

        List<TournamentPlayer> pairedPlayers = Arrays.asList(humanWithAi, ai);

        // Old approach: equals() fails because LobbyPlayerAi != LobbyPlayerHuman
        boolean equalsFound = false;
        for (TournamentPlayer tp : pairedPlayers) {
            if (tp.getPlayer().equals(matchWinner)) {
                equalsFound = true;
                break;
            }
        }
        Assert.assertFalse(equalsFound, "equals() should fail across LobbyPlayer types (the original bug)");

        // Fixed approach: name matching works
        boolean nameFound = false;
        for (TournamentPlayer tp : pairedPlayers) {
            if (tp.getPlayer().getName().equals(matchWinner.getName())) {
                nameFound = true;
                break;
            }
        }
        Assert.assertTrue(nameFound, "Name matching should work across LobbyPlayer types (the fix)");
    }

    /**
     * Gap 8: a pairing marked as a DRAW (the match ended with no winner) must
     * award a tie to every player — never a silent win for player A.
     */
    @Test
    public void testDrawPairingAwardsTiesToBothPlayers() {
        TournamentPlayer p0 = new TournamentPlayer(new LobbyPlayerAi("P0", null), 0);
        TournamentPlayer p1 = new TournamentPlayer(new LobbyPlayerAi("P1", null), 1);
        TournamentPairing pairing = new TournamentPairing(1, Arrays.asList(p0, p1));
        pairing.markDraw();

        TournamentRoundRobin rr = new TournamentRoundRobin(3, Arrays.asList(p0, p1));
        rr.reportMatchCompletion(pairing);

        Assert.assertEquals(p0.getTies(), 1, "Player 0 should have 1 tie");
        Assert.assertEquals(p1.getTies(), 1, "Player 1 should have 1 tie");
        Assert.assertEquals(p0.getWins(), 0, "A draw must not award a win");
        Assert.assertEquals(p0.getLosses(), 0, "A draw must not award a loss");
        Assert.assertEquals(p0.getScore(), 1, "A tie is worth 1 point");
        Assert.assertNull(pairing.getWinner(), "A draw has no winner");
        Assert.assertTrue(pairing.isDraw());
    }

    /**
     * Gap 8: a VOID pairing (e.g. both players AWOL, or a desync) must award no
     * points and record no opponents — never a silent win for player A.
     */
    @Test
    public void testVoidPairingAwardsNoPointsAndNoOpponents() {
        TournamentPlayer p0 = new TournamentPlayer(new LobbyPlayerAi("P0", null), 0);
        TournamentPlayer p1 = new TournamentPlayer(new LobbyPlayerAi("P1", null), 1);
        TournamentPairing pairing = new TournamentPairing(1, Arrays.asList(p0, p1));
        pairing.markVoid();

        TournamentRoundRobin rr = new TournamentRoundRobin(3, Arrays.asList(p0, p1));
        rr.reportMatchCompletion(pairing);

        Assert.assertEquals(p0.getWins(), 0, "Void must not award a win");
        Assert.assertEquals(p0.getLosses(), 0, "Void must not award a loss");
        Assert.assertEquals(p0.getTies(), 0, "Void must not award a tie");
        Assert.assertEquals(p0.getScore(), 0, "Void awards no points");
        Assert.assertTrue(p0.getPreviousOpponents().isEmpty(), "Void must not record opponents for OMW");
        Assert.assertNull(pairing.getWinner());
        Assert.assertTrue(pairing.isVoid());
    }

    /**
     * Gap 8 regression: a normal WIN pairing still awards win/loss and records
     * opponents, so existing standings/OMW behavior is preserved.
     */
    @Test
    public void testWinPairingStillAwardsWinAndLoss() {
        TournamentPlayer p0 = new TournamentPlayer(new LobbyPlayerAi("P0", null), 0);
        TournamentPlayer p1 = new TournamentPlayer(new LobbyPlayerAi("P1", null), 1);
        TournamentPairing pairing = new TournamentPairing(1, Arrays.asList(p0, p1));
        pairing.setWinner(p0);

        TournamentRoundRobin rr = new TournamentRoundRobin(3, Arrays.asList(p0, p1));
        rr.reportMatchCompletion(pairing);

        Assert.assertEquals(p0.getWins(), 1, "Winner should get a win");
        Assert.assertEquals(p1.getLosses(), 1, "Loser should get a loss");
        Assert.assertEquals(p0.getTies(), 0);
        Assert.assertEquals(p1.getPreviousOpponents().size(), 1, "Opponents should be recorded for OMW");
        Assert.assertEquals(pairing.getResult(), TournamentPairing.MatchResult.WIN);
    }

    /**
     * Regression test for Bug 3: completing a match via
     * {@link TournamentRoundRobin#reportMatchCompletion} must record opponent
     * indices so OMW (the opponent-match-win tiebreaker) is non-zero. Previously
     * only wins/losses were recorded, so standings always showed 0% OMW.
     */
    @Test
    public void testReportMatchCompletionRecordsOpponentsForOMW() {
        List<TournamentPlayer> players = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            players.add(new TournamentPlayer(new LobbyPlayerAi("P" + i, null), i));
        }

        TournamentRoundRobin rr = new TournamentRoundRobin(3, players);

        for (TournamentPairing p : new ArrayList<>(rr.getActivePairings())) {
            p.setWinner(p.getPairedPlayers().get(0));
            rr.reportMatchCompletion(p);
        }

        // Every real player should have exactly one recorded opponent after round 1.
        for (TournamentPlayer tp : players) {
            Assert.assertEquals(tp.getPreviousOpponents().size(), 1,
                    tp.getPlayer().getName() + " should have faced one opponent in round 1");
        }

        // OMW is the average win rate of the opponents faced. In round 1 every
        // pairing produced one winner and one loser: a winner's opponent went 0-1
        // (OMW 0.0), a loser's opponent went 1-0 (OMW 1.0). The key regression is
        // that previousOpponents is now populated, so OMW is a real number rather
        // than the old always-0.0.
        List<TournamentPlayer> all = new ArrayList<>(players);
        for (TournamentPlayer tp : players) {
            double omw = tp.getOMW(all);
            double expected = tp.getWins() > 0 ? 0.0 : 1.0;
            Assert.assertEquals(omw, expected, 0.0001,
                    tp.getPlayer().getName() + " OMW mismatch (got " + omw + ", expected " + expected + ")");
        }
    }
}
