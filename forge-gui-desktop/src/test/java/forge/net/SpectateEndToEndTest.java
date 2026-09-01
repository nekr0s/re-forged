package forge.net;

import forge.deck.Deck;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.player.PlayerView;
import forge.gamemodes.match.AbstractGuiGame;
import forge.gamemodes.match.LobbySlot;
import forge.gamemodes.match.LobbySlotType;
import forge.gamemodes.net.EventFormat;
import forge.gamemodes.net.EventParticipant;
import forge.gamemodes.net.NetworkEvent;
import forge.gamemodes.net.PairingView;
import forge.gamemodes.net.RoundState;
import forge.gamemodes.net.event.NetEvent;
import forge.gamemodes.net.event.SpectateApprovedEvent;
import forge.gamemodes.net.event.SpectateLeaveEvent;
import forge.gamemodes.net.event.SpectateRequestEvent;
import forge.gamemodes.net.event.TournamentUpdateEvent;
import forge.gamemodes.net.server.FServerManager;
import forge.gamemodes.net.server.ServerGameLobby;
import forge.gui.interfaces.IDraftEventHandler;
import forge.item.PaperCard;
import forge.util.IHasForgeLog;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Gap 5 e2e: a guest client spectates an ongoing AI-vs-AI tournament match over a
 * real TCP connection. Asserts the approval event, that the client receives the
 * game stream without disconnecting (the openView(null) null-guard), that hidden
 * hands hold on the spectator GUI, and that SpectateLeaveEvent cleans up server-side.
 */
public class SpectateEndToEndTest implements IHasForgeLog {

    private static final String[] NAMES = {"Alice", "Bob"};

    @BeforeClass
    public void init() {
        TestUtils.ensureFModelInitialized();
    }

    @Test(timeOut = 600000)
    public void testGuestSpectatesOngoingMatch() throws Exception {
        FServerManager server = FServerManager.getInstance();
        ServerGameLobby lobby = new ServerGameLobby();
        List<NetEvent> capturedEvents = new CopyOnWriteArrayList<>();
        HeadlessNetworkClient spectator = null;

        try {
            int port = PortAllocator.allocatePort();
            server.startServer(port);
            server.setLobby(lobby);
            server.addNetEventHandler(capturedEvents::add);

            // 2-AI best-of-1 round-robin (1 round, 1 match).
            for (int i = 0; i < 2; i++) {
                LobbySlot slot = lobby.getSlot(i);
                slot.setType(LobbySlotType.AI);
                slot.setName(NAMES[i]);
                slot.setDeck(fastDeck());
                slot.setIsReady(true);
            }
            NetworkEvent event = new NetworkEvent(EventFormat.SEALED);
            for (int i = 0; i < 2; i++) {
                EventParticipant p = new EventParticipant(NAMES[i], EventParticipant.Type.AI, i, i);
                p.setDeck(fastDeck());
                event.addParticipant(p);
            }
            lobby.setCurrentEvent(event);
            lobby.startTournament(1);
            Assert.assertNotNull(lobby.getTournamentController(), "tournament should start");

            // Wait for round 1 to go ACTIVE and grab the real matchId of the ongoing pairing.
            String matchId = waitForMatchId(capturedEvents);
            Assert.assertNotNull(matchId, "round 1 should produce an ONGOING pairing with a real matchId");

            // Connect a guest spectator into a spare OPEN slot (not a participant).
            lobby.addSlot();
            LobbySlot guestSlot = lobby.getSlot(2);
            guestSlot.setType(LobbySlotType.OPEN);

            spectator = new HeadlessNetworkClient("Spectator", "localhost", port);
            Assert.assertTrue(spectator.connect(30_000), "spectator client should connect");
            Assert.assertEquals(spectator.getAssignedSlot(), 2, "guest should take slot 2");

            // Capture the approval on the client side.
            CaptureHandler capture = new CaptureHandler();
            spectator.getClient().setDraftHandler(capture);
            spectator.getClient().send(new SpectateRequestEvent(matchId));

            // 1) Approval with the real matchId.
            long deadline = System.currentTimeMillis() + 30_000;
            while (capture.approved.get() == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            Assert.assertEquals(capture.approved.get(), matchId, "server must approve with the real matchId");

            // 2) The client stays connected and receives openView + a game view.
            deadline = System.currentTimeMillis() + 30_000;
            while ((!spectator.isOpenViewCalled() || spectator.getGameView() == null)
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            Assert.assertTrue(spectator.isConnected(), "client must not disconnect on openView(null)");
            Assert.assertTrue(spectator.isOpenViewCalled(), "spectator GUI must receive openView");
            Assert.assertNotNull(spectator.getGameView(), "spectator GUI must receive a game view");

            // 3) Hidden hands: spectator mode is armed and hand cards are hidden.
            AbstractGuiGame gui = (AbstractGuiGame) spectator.getClient().getGui();
            Assert.assertTrue(gui.isSpectatorMode(), "shared GUI must be in spectator mode");
            // The initial snapshot can arrive before the game's player list / zones are
            // populated; the delta stream catches it up. Re-read the game view on every
            // poll (a held reference would stay on the stale pre-setup snapshot, whose
            // getPlayers() is null until updatePlayers() runs) and wait until both a hand
            // card and a face-up battlefield card are observable.
            boolean sawHand = false;
            boolean sawPublic = false;
            long handsDeadline = System.currentTimeMillis() + 30_000;
            while ((!sawHand || !sawPublic) && System.currentTimeMillis() < handsDeadline) {
                GameView gv = spectator.getGameView();
                if (gv != null && gv.getPlayers() != null) {
                    for (PlayerView pv : gv.getPlayers()) {
                        for (CardView c : pv.getHand()) {
                            sawHand = true;
                            Assert.assertFalse(gui.mayView(c), "spectator must not see hand cards");
                        }
                        for (CardView c : pv.getBattlefield()) {
                            if (gui.mayView(c)) {
                                sawPublic = true;
                            }
                        }
                    }
                }
                Thread.sleep(50);
            }
            Assert.assertTrue(sawHand, "both players should have hand cards to check");
            Assert.assertTrue(sawPublic, "face-up battlefield cards must be visible to a spectator");

            // 4) Leave cleans up server-side.
            spectator.getClient().send(new SpectateLeaveEvent(matchId));
            deadline = System.currentTimeMillis() + 30_000;
            while (server.findClientByIndex(2).getMatchGui(FServerManager.SPECTATE_KEY_PREFIX + matchId) != null
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            Assert.assertNull(server.findClientByIndex(2).getMatchGui(FServerManager.SPECTATE_KEY_PREFIX + matchId),
                    "leave must remove the server-side spectator GUI");
        } finally {
            if (spectator != null) {
                spectator.close();
            }
            server.stopServer();
            HeadlessGuiDesktop.clearLastMatch();
        }
    }

    private static String waitForMatchId(List<NetEvent> events) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            for (NetEvent e : events) {
                if (e instanceof TournamentUpdateEvent upd && upd.getRoundState() == RoundState.ACTIVE) {
                    for (PairingView p : upd.getPairings()) {
                        if (p.matchId() != null) {
                            return p.matchId();
                        }
                    }
                }
            }
            Thread.sleep(100);
        }
        return null;
    }

    /** A 20-land deck so the AI game lasts long enough to spectate mid-match. */
    private static Deck fastDeck() {
        return TestDeckLoader.createMinimalDeck("Forest", 20);
    }

    private static final class CaptureHandler implements IDraftEventHandler {
        final AtomicReference<String> approved = new AtomicReference<>();
        @Override public void draftPackArrived(int seatIndex, List<PaperCard> pack, int packNumber, int pickNumber, int timerDurationSeconds) {}
        @Override public void draftSeatPicked(int seatIndex, int[] seatQueueDepths) {}
        @Override public void draftAutoPicked(int seatIndex, PaperCard card, int packNumber, int pickInPack) {}
        @Override public void receiveEventPool(String eventId, Deck pool) {}
        @Override public boolean dispatch(NetEvent event) {
            if (event instanceof SpectateApprovedEvent a) {
                approved.set(a.getMatchId());
            }
            return false;
        }
    }
}
