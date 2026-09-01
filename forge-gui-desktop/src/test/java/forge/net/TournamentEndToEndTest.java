package forge.net;

import forge.deck.Deck;
import forge.gamemodes.match.LobbySlot;
import forge.gamemodes.match.LobbySlotType;
import forge.gamemodes.net.EventFormat;
import forge.gamemodes.net.EventParticipant;
import forge.gamemodes.net.NetworkEvent;
import forge.gamemodes.net.RoundState;
import forge.gamemodes.net.event.MatchCompleteEvent;
import forge.gamemodes.net.event.MatchStartedEvent;
import forge.gamemodes.net.event.NetEvent;
import forge.gamemodes.net.event.RoundCompleteEvent;
import forge.gamemodes.net.event.TournamentCompleteEvent;
import forge.gamemodes.net.event.TournamentStartEvent;
import forge.gamemodes.net.event.TournamentUpdateEvent;
import forge.gamemodes.net.server.FServerManager;
import forge.gamemodes.net.server.ServerGameLobby;
import forge.gamemodes.net.server.ServerTournamentController;
import forge.gamemodes.tournament.system.TournamentRoundRobin;
import forge.util.IHasForgeLog;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Gap 9: headless end-to-end tournament test. Boots a real server + lobby, sets
 * up a 4-AI-participant sealed event with fast minimal decks, starts a best-of-1
 * round-robin tournament, lets it run to completion (all-AI auto-advances between
 * rounds), and asserts the tournament broadcasts and final standings.
 *
 * <p>This exercises the full flow the unit tests can't: {@code startTournament},
 * per-round match hosting, the 500ms completion poll, standby auto-advance, and
 * the {@code TournamentUpdateEvent} snapshot chain.
 */
public class TournamentEndToEndTest implements IHasForgeLog {

    private static final String[] NAMES = {"Alice", "Bob", "Charlie", "Diana"};

    @Test(timeOut = 600000)
    public void testFourPlayerTournamentRunsToCompletion() throws Exception {
        TestUtils.ensureFModelInitialized();

        FServerManager server = FServerManager.getInstance();
        ServerGameLobby lobby = new ServerGameLobby();
        List<NetEvent> capturedEvents = new CopyOnWriteArrayList<>();

        try {
            int port = PortAllocator.allocatePort();
            server.startServer(port);
            server.setLobby(lobby);
            server.addNetEventHandler(capturedEvents::add);

            // 4 lobby slots, all AI (host slot is AI in the harness too).
            for (int i = 2; i < 4; i++) {
                lobby.addSlot();
            }
            for (int i = 0; i < 4; i++) {
                LobbySlot slot = lobby.getSlot(i);
                slot.setType(LobbySlotType.AI);
                slot.setName(NAMES[i]);
                slot.setDeck(fastDeck());
                slot.setIsReady(true);
            }

            // Sealed event with 4 AI participants carrying their decks.
            NetworkEvent event = new NetworkEvent(EventFormat.SEALED);
            for (int i = 0; i < 4; i++) {
                EventParticipant p = new EventParticipant(NAMES[i], EventParticipant.Type.AI, i, i);
                p.setDeck(fastDeck());
                event.addParticipant(p);
            }
            lobby.setCurrentEvent(event);

            netLog.info("Starting 4-player best-of-1 tournament");
            lobby.startTournament(1);

            ServerTournamentController controller = lobby.getTournamentController();
            Assert.assertNotNull(controller, "Tournament controller should be created");
            TournamentRoundRobin tournament = controller.getTournament();
            Assert.assertEquals(tournament.getTotalRounds(), 3, "4-player round-robin = 3 rounds");

            // Wait for the all-AI tournament to run to completion.
            long deadline = System.currentTimeMillis() + 300_000;
            while (System.currentTimeMillis() < deadline) {
                if (tournament.isTournamentOver()) {
                    break;
                }
                Thread.sleep(500);
            }

            Assert.assertTrue(tournament.isTournamentOver(),
                    "Tournament should complete within the deadline (activeRound="
                            + tournament.getActiveRound() + ", over=" + tournament.isTournamentOver() + ")");

            // Every player must have actually played: 3 rounds each.
            for (var tp : tournament.getAllPlayers()) {
                int matches = tp.getWins() + tp.getLosses() + tp.getTies() + tp.getByes();
                Assert.assertEquals(matches, 3,
                        tp.getPlayer().getName() + " should have played 3 rounds");
                Assert.assertEquals(tp.getPreviousOpponents().size(), 3,
                        tp.getPlayer().getName() + " should have faced 3 distinct opponents");
            }

            // Every stage of the broadcast chain must have fired.
            Assert.assertTrue(hasEvent(capturedEvents, TournamentStartEvent.class),
                    "TournamentStartEvent should be broadcast");
            Assert.assertTrue(hasEvent(capturedEvents, TournamentUpdateEvent.class),
                    "TournamentUpdateEvent should be broadcast");
            Assert.assertTrue(hasEvent(capturedEvents, MatchStartedEvent.class),
                    "MatchStartedEvent should be broadcast");
            Assert.assertTrue(hasEvent(capturedEvents, MatchCompleteEvent.class),
                    "MatchCompleteEvent should be broadcast");
            Assert.assertTrue(hasEvent(capturedEvents, RoundCompleteEvent.class),
                    "RoundCompleteEvent should be broadcast");
            Assert.assertTrue(hasEvent(capturedEvents, TournamentCompleteEvent.class),
                    "TournamentCompleteEvent should be broadcast");

            TournamentStartEvent start = firstOfType(capturedEvents, TournamentStartEvent.class);
            Assert.assertEquals(start.getPlayerNames().size(), 4);

            // The snapshot chain must have carried pairings and standings.
            boolean sawActiveRound = false;
            boolean sawStandings = false;
            for (NetEvent e : capturedEvents) {
                if (e instanceof TournamentUpdateEvent upd) {
                    sawStandings |= !upd.getStandings().isEmpty();
                    if (upd.getRoundState() == RoundState.ACTIVE) {
                        sawActiveRound = true;
                        Assert.assertEquals(upd.getPairings().size(), 2,
                                "Round " + upd.getRound() + " should show 2 pairings");
                    }
                }
            }
            Assert.assertTrue(sawActiveRound, "At least one ACTIVE round snapshot expected");
            Assert.assertTrue(sawStandings, "Standings should be populated in snapshots");

            TournamentCompleteEvent complete = firstOfType(capturedEvents, TournamentCompleteEvent.class);
            Assert.assertFalse(complete.isCancelled(), "Tournament should not be cancelled");
            Assert.assertEquals(complete.getFinalStandings().size(), 4,
                    "Final standings should rank all 4 players");
            // Standings must be score-sorted (first place has the most points).
            List<Integer> scores = complete.getFinalStandings().stream()
                    .map(s -> s.score()).toList();
            for (int i = 1; i < scores.size(); i++) {
                Assert.assertTrue(scores.get(i - 1) >= scores.get(i),
                        "Standings must be sorted by score descending");
            }
        } finally {
            server.stopServer();
            HeadlessGuiDesktop.clearLastMatch();
        }
    }

    /** A fast 10-land deck so games end quickly by decking out (legality is disabled in tests). */
    private static Deck fastDeck() {
        return TestDeckLoader.createMinimalDeck("Forest", 10);
    }

    private static boolean hasEvent(List<NetEvent> events, Class<? extends NetEvent> type) {
        for (NetEvent e : events) {
            if (type.isInstance(e)) return true;
        }
        return false;
    }

    private static <T extends NetEvent> T firstOfType(List<NetEvent> events, Class<T> type) {
        for (NetEvent e : events) {
            if (type.isInstance(e)) return type.cast(e);
        }
        return null;
    }
}
