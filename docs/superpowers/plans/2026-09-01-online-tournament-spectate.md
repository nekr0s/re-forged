# Online Tournament Spectating Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement client-side online-tournament spectating (Gap 5): an "Ongoing Matches" list + Spectate button in the tournament panel, spectator mode on the shared client GUI with hidden hands, and clean leave/auto-return lifecycle.

**Architecture:** Server-side spectating already exists (`handleSpectateRequest` → `RemoteClientGuiGame` + `WatchRemoteGame` + `GameEventForwarder`). This plan adds: server guards + a `"spectate:"` GUI-key convention + full leave teardown, a client-side `spectatorMode` flag (armed/cleared from the `openView` `myPlayers` null-ness in the shared `GameClientHandler`), the hidden-hands predicate (`mayView`/`mayFlip` in `AbstractGuiGame`), the desktop UI (list + button, Game-menu "Stop Spectating", WinLose suppression), and an end-to-end test. Scope decisions: lobby-only (one active game view), client-side hidden hands, anyone in the lobby can spectate.

**Tech Stack:** Java 17, Maven (no wrapper — use `mvn`), TestNG, Guava EventBus, Netty. Build/test command pattern (PowerShell stop-parsing):
`mvn --% -o -pl forge-gui-desktop -am test -Dtest=<TestClass> -Dsurefire.failIfNoSpecifiedTests=false`

---

## File Structure

| File | Responsibility |
|------|----------------|
| `forge-game/.../game/Game.java` | Add `unsubscribeFromEvents(Object)` (Guava EventBus unregister) |
| `forge-gui/.../net/server/RemoteClient.java` | Add `getMatchGuiKeys()` (snapshot of `matchGuis` keys) |
| `forge-gui/.../net/server/FServerManager.java` | `SPECTATE_KEY_PREFIX`, `spectateProblemFor(...)` static, rewritten `handleSpectateRequest`/`handleSpectateLeave`, `leaveSpectate(...)` helper, auto-leave on own-match-start in `getGui(index, matchId)` |
| `forge-gui/.../net/server/HostedMatch.java` | Add `unregisterNetworkSpectator(RemoteClientGuiGame)` |
| `forge-gui/.../match/AbstractGuiGame.java` | `spectatorMode` flag + `setSpectatorMode`/`isSpectatorMode`; hidden-hands in `mayView`/`mayFlip`; reset in `resetForNewMatch` |
| `forge-gui/.../net/client/GameClientHandler.java` | Null-guard `myPlayers` in `openView` + set `spectatorMode = (myPlayers == null)` |
| `forge-gui-desktop/.../match/CMatchUI.java` | `finishGame()` spectator gate (no WinLose) |
| `forge-gui-desktop/.../screens/match/menus/GameMenu.java` | "Stop Spectating" menu item (visible in spectator mode) |
| `forge-gui-desktop/.../screens/home/online/VSubmenuOnlineLobby.java` | Add `getLobbyView()` getter |
| `forge-gui-desktop/.../screens/home/CLobby.java` | `spectatingMatchId`, `onSpectateApproved`, `leaveSpectating()`, `onMatchStarted` clear |
| `forge-gui-desktop/.../screens/home/VLobby.java` | Ongoing Matches list + Spectate button; `showSpectateView` null handling |
| Test `forge-gui-desktop/src/test/java/forge/net/SpectateServerTest.java` | Unit tests: guard helper + leave teardown |
| Test `forge-gui-desktop/src/test/java/forge/net/SpectateEndToEndTest.java` | Full protocol e2e |

---

## Task 1: Server spectate lifecycle — guards, key convention, leave teardown

**Files:**
- Modify: `forge-game/src/main/java/forge/game/Game.java` (add `unsubscribeFromEvents`)
- Modify: `forge-gui/src/main/java/forge/gamemodes/net/server/RemoteClient.java`
- Modify: `forge-gui/src/main/java/forge/gamemodes/net/server/HostedMatch.java`
- Modify: `forge-gui/src/main/java/forge/gamemodes/net/server/FServerManager.java`
- Test: `forge-gui-desktop/src/test/java/forge/net/SpectateServerTest.java` (new)

### Step 1: Write the failing unit test

Create `forge-gui-desktop/src/test/java/forge/net/SpectateServerTest.java`:

```java
package forge.net;

import forge.gamemodes.net.server.FServerManager;
import forge.gamemodes.net.server.RemoteClient;
import forge.gamemodes.net.server.RemoteClientGuiGame;
import forge.gamemodes.net.server.ServerGameLobby;
import io.netty.channel.embedded.EmbeddedChannel;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Server-side spectate lifecycle: the pure guard helpers and the leave teardown.
 * The prefixed "spectate:" key must let the guards tell a spectator GUI entry
 * from a player's own-match GUI entry.
 */
public class SpectateServerTest {

    @Test
    public void inOwnMatchIsFalseForEmptyClient() {
        RemoteClient client = new RemoteClient(new EmbeddedChannel());
        Assert.assertFalse(FServerManager.isClientInOwnMatch(client),
                "a client with no match GUIs is not in a match");
    }

    @Test
    public void inOwnMatchIsTrueForBareKey() {
        RemoteClient client = new RemoteClient(new EmbeddedChannel());
        // A player's own match GUI is registered under the BARE matchId.
        new RemoteClientGuiGame(client, "match-1");
        Assert.assertTrue(FServerManager.isClientInOwnMatch(client),
                "a bare match-GUI key means the client is playing their own match");
    }

    @Test
    public void inOwnMatchIsFalseForPrefixedKeyOnly() {
        RemoteClient client = new RemoteClient(new EmbeddedChannel());
        // A spectator GUI is registered under the PREFIXED key (mirroring
        // handleSpectateRequest, which removes the ctor's bare-key registration).
        RemoteClientGuiGame spec = new RemoteClientGuiGame(client, "match-1");
        client.removeMatchGui("match-1");
        client.setMatchGui("spectate:match-1", spec);
        Assert.assertFalse(FServerManager.isClientInOwnMatch(client),
                "a client that is only spectating is not in a match of their own");
    }

    @Test
    public void guardRejectsUnknownMatch() {
        RemoteClient client = new RemoteClient(new EmbeddedChannel());
        Assert.assertEquals(
                FServerManager.spectateProblemFor(client, "match-1", null),
                "no running match",
                "A missing HostedMatch must be rejected");
    }

    @Test
    public void leaveRemovesPrefixedMatchGui() {
        RemoteClient client = new RemoteClient(new EmbeddedChannel());
        RemoteClientGuiGame spec = new RemoteClientGuiGame(client, "match-1");
        client.removeMatchGui("match-1");
        client.setMatchGui("spectate:match-1", spec);
        client.setActiveMatchId("spectate:match-1");

        FServerManager server = FServerManager.getInstance();
        server.setLobby(new ServerGameLobby());
        server.handleSpectateLeave("match-1", client);

        Assert.assertNull(client.getMatchGui("spectate:match-1"),
                "leave must remove the prefixed spectator GUI");
        Assert.assertFalse(FServerManager.isClientInOwnMatch(client),
                "after leave the client has no match-GUI keys at all");
    }
}
```

### Step 2: Run it — verify it fails (compilation failure)

Run: `mvn --% -o -pl forge-gui-desktop -am test -Dtest=SpectateServerTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: FAIL — `isClientInOwnMatch`, `spectateProblemFor`, and `getMatchGui` do not exist yet (compile errors).

### Step 3: Implement the server lifecycle

**a) `forge-game/src/main/java/forge/game/Game.java`** — add after `subscribeToEvents` (line ~1023):

```java
    public void unsubscribeFromEvents(final Object subscriber) {
        try {
            events.unregister(subscriber);
        } catch (final IllegalArgumentException ignored) {
            // Already unregistered (e.g. the match already ended) — nothing to do.
        }
    }
```

**b) `forge-gui/src/main/java/forge/gamemodes/net/server/RemoteClient.java`** — add near `getMatchGui`:

```java
    /**
     * Snapshot of the current match-GUI keys. Returns a copy so callers may
     * iterate while removing entries (e.g. the spectate auto-leave loop).
     */
    public java.util.Set<String> getMatchGuiKeys() {
        return new java.util.HashSet<>(matchGuis.keySet());
    }
```

**c) `forge-gui/src/main/java/forge/gamemodes/match/HostedMatch.java`** — add after `registerNetworkSpectator` (line ~409):

```java
    /**
     * Remove a remote spectator from this match. Unsubscribes its forwarder from
     * the game event bus and every human controller's input queue, shuts the
     * forwarder down, and drops its WatchRemoteGame controller. Null-safe for a
     * match whose game has already been torn down.
     */
    public void unregisterNetworkSpectator(final RemoteClientGuiGame gui) {
        final GameEventForwarder forwarder = gui.getForwarder();
        if (forwarder != null) {
            if (game != null) {
                game.unsubscribeFromEvents(forwarder);
            }
            for (final PlayerControllerHuman hc : humanControllers) {
                hc.getInputQueue().deleteObserver(forwarder);
            }
            gui.shutdownForwarder();
        }
        humanControllers.removeIf(hc -> hc.getGui() == gui);
    }
```

`GameEventForwarder` is already imported in `HostedMatch` (used at line 430).

**d) `forge-gui/src/main/java/forge/gamemodes/net/server/FServerManager.java`** — replace `handleSpectateRequest` and `handleSpectateLeave` (currently lines 522-554) with:

```java
    /** Key prefix that marks a {@link RemoteClient} match-GUI entry as a spectator
     *  view rather than a player's own match (see {@link #spectateProblemFor}). */
    public static final String SPECTATE_KEY_PREFIX = "spectate:";

    /**
     * Handle a spectate request from a client.
     */
    public void handleSpectateRequest(final String matchId, final RemoteClient client) {
        if (localLobby == null) {
            client.send(new SpectateApprovedEvent(null));
            return;
        }
        final HostedMatch match = localLobby.getMatch(matchId);
        final String problem = spectateProblemFor(client, matchId, match);
        if (problem != null) {
            netLog.warn("Rejected spectate request from client {} for match {}: {}",
                    client.getIndex(), matchId, problem);
            client.send(new SpectateApprovedEvent(null));
            return;
        }

        // One spectate per client: drop any previous one before attaching the new.
        for (final String key : client.getMatchGuiKeys()) {
            if (key.startsWith(SPECTATE_KEY_PREFIX)) {
                leaveSpectate(client, key.substring(SPECTATE_KEY_PREFIX.length()));
            }
        }

        final RemoteClientGuiGame spectatorGui = new RemoteClientGuiGame(client, matchId);
        final String spectateKey = SPECTATE_KEY_PREFIX + matchId;
        // The constructor registers under the bare matchId; move it under the prefixed
        // key so the "in a match" guard can distinguish spectator entries from a
        // player's own match. Guard 2 guarantees no bare key exists for this client.
        client.removeMatchGui(matchId);
        client.setMatchGui(spectateKey, spectatorGui);
        client.setActiveMatchId(spectateKey);

        match.registerNetworkSpectator(spectatorGui);

        client.send(new SpectateApprovedEvent(matchId));
        netLog.info("Client {} now spectating match {}", client.getIndex(), matchId);
    }

    /**
     * True if this client is a player in one of their own matches right now: they
     * have a match-GUI entry under a bare (non-prefixed) key. A client with only
     * {@code "spectate:"}-prefixed entries is merely watching.
     */
    public static boolean isClientInOwnMatch(final RemoteClient client) {
        for (final String key : client.getMatchGuiKeys()) {
            if (!key.startsWith(SPECTATE_KEY_PREFIX)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Pure guard for a spectate request: returns a human-readable rejection reason,
     * or {@code null} if the request may proceed. A client whose own match is
     * running (a bare, non-prefixed match-GUI key) cannot spectate; a client that
     * is only spectating may switch freely.
     */
    public static String spectateProblemFor(final RemoteClient client, final String matchId,
            final HostedMatch match) {
        if (matchId == null || match == null || match.getGame() == null) {
            return "no running match";
        }
        if (isClientInOwnMatch(client)) {
            return "client is already in a match";
        }
        return null;
    }

    /**
     * Handle a spectate leave from a client.
     */
    public void handleSpectateLeave(final String matchId, final RemoteClient client) {
        leaveSpectate(client, matchId);
    }

    private void leaveSpectate(final RemoteClient client, final String matchId) {
        final String spectateKey = SPECTATE_KEY_PREFIX + matchId;
        final HostedMatch match = localLobby != null ? localLobby.getMatch(matchId) : null;
        final RemoteClientGuiGame spectatorGui = client.getMatchGui(spectateKey);
        if (spectatorGui != null && match != null) {
            match.unregisterNetworkSpectator(spectatorGui);
        }
        client.removeMatchGui(spectateKey);
        netLog.info("Client {} stopped spectating match {}", client.getIndex(), matchId);
    }
```

**e) Auto-leave on own-match start** — in `FServerManager.getGui(final int index, final String matchId)` (REMOTE branch, after `final RemoteClient client = findClientByIndex(index);` and before `if (client != null) { RemoteClientGuiGame gui = client.getMatchGui(matchId);`), add:

```java
                // A client whose own match is starting drops any spectate — the client
                // has a single active game view and cannot spectate while playing.
                for (final String key : client.getMatchGuiKeys()) {
                    if (key.startsWith(SPECTATE_KEY_PREFIX)) {
                        leaveSpectate(client, key.substring(SPECTATE_KEY_PREFIX.length()));
                    }
                }
```

### Step 4: Run the tests

Run: `mvn --% -o -pl forge-gui-desktop -am test -Dtest=SpectateServerTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS (5 tests). Note: the "no running match" guard path and the "busy client" rejection are each unit-covered (`spectateProblemFor` with a null match; `isClientInOwnMatch`); the composed happy path against a real running match is covered by Task 4's e2e.

### Step 5: Compile the whole module and commit

Run: `mvn --% -o -pl forge-gui-desktop -am test -DskipTests`

Commit:
```bash
git add forge-game/src/main/java/forge/game/Game.java
git add forge-gui/src/main/java/forge/gamemodes/net/server/RemoteClient.java
git add forge-gui/src/main/java/forge/gamemodes/match/HostedMatch.java
git add forge-gui/src/main/java/forge/gamemodes/net/server/FServerManager.java
git add forge-gui-desktop/src/test/java/forge/net/SpectateServerTest.java
git commit -m "Add online tournament spectate server lifecycle (guards, key convention, leave teardown)"
```

---

## Task 2: Client — spectatorMode flag, hidden hands, openView null-guard (+ e2e test, RED)

**Files:**
- Modify: `forge-gui/src/main/java/forge/gamemodes/match/AbstractGuiGame.java`
- Modify: `forge-gui/src/main/java/forge/gamemodes/net/client/GameClientHandler.java`
- Test: `forge-gui-desktop/src/test/java/forge/net/SpectateEndToEndTest.java` (new, written first — RED)

### Step 1: Write the failing end-to-end test

Create `forge-gui-desktop/src/test/java/forge/net/SpectateEndToEndTest.java`:

```java
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
import forge.gamemodes.net.server.ServerTournamentController;
import forge.gui.interfaces.IDraftEventHandler;
import forge.item.PaperCard;
import forge.util.IHasForgeLog;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Gap 5 e2e: a guest client spectates an ongoing AI-vs-AI tournament match over a
 * real TCP connection. Asserts the approval event, that the client receives the
 * game stream without disconnecting (the openView(null) null-guard), that hidden
 * hands hold on the spectator GUI, and that SpectateLeaveEvent cleans up server-side.
 */
public class SpectateEndToEndTest implements IHasForgeLog {

    private static final String[] NAMES = {"Alice", "Bob"};

    @Test(timeOut = 600000)
    public void testGuestSpectatesOngoingMatch() throws Exception {
        TestUtils.ensureFModelInitialized();

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
            GameView gv = spectator.getGameView();
            boolean sawHand = false;
            boolean sawPublic = false;
            for (PlayerView pv : gv.getPlayers()) {
                for (CardView c : pv.getHand()) {
                    sawHand = true;
                    Assert.assertFalse(gui.mayView(c), "spectator must not see hand cards");
                }
                for (CardView c : pv.getBattlefield()) {
                    sawPublic = true;
                    Assert.assertTrue(gui.mayView(c), "spectator must see face-up battlefield cards");
                }
            }
            Assert.assertTrue(sawHand, "both players should have hand cards to check");

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
```

### Step 2: Run it — verify it fails

Run: `mvn --% -o -pl forge-gui-desktop -am test -Dtest=SpectateEndToEndTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: FAIL. At minimum `AbstractGuiGame.isSpectatorMode()` does not compile. If it compiles (before hidden-hands), the assertion `assertFalse(gui.mayView(handCard))` fails because `mayView` returns `true` for a GUI with no local players, and/or `isSpectatorMode()` is false.

### Step 3: Implement `spectatorMode` + hidden hands in `AbstractGuiGame`

In `forge-gui/src/main/java/forge/gamemodes/match/AbstractGuiGame.java`:

**a)** Add the flag next to `tournamentMatch` (line ~49):

```java
    private boolean spectatorMode = false;
```

Add accessors after `setTournamentMatch` (line ~70):

```java
    /**
     * A spectator view (online tournament spectating): the client is watching a
     * match it does not play in, so only public information may be rendered.
     */
    public final boolean isSpectatorMode() {
        return spectatorMode;
    }
    public final void setSpectatorMode(final boolean spectatorMode) {
        this.spectatorMode = spectatorMode;
    }
```

**b)** Reset in `resetForNewMatch()` (add to the body, line ~250):

```java
        spectator = null;
        spectatorMode = false;
```

**c)** Replace the head of `mayView` (line ~283):

```java
    @Override
    public boolean mayView(final CardView c) {
        if (spectatorMode) {
            // Spectators see only public info: cards every player in the game can see.
            if (gameView == null) {
                return false;
            }
            for (final PlayerView p : gameView.getPlayers()) {
                if (!c.canBeShownTo(p)) {
                    return false;
                }
            }
            return true;
        }
        if (!hasLocalPlayers()) {
            return true; //if not in game, card can be shown
        }
        if (GuiBase.getInterface().isLibgdxPort() && gameView != null && gameView.isGameOver()) {
            return true; //mobile: browse every zone from the minimized win/lose overlay after the match ends
        }
        if (getGameController().mayLookAtAllCards()) {
            return true;
        }
        return c.canBeShownToAny(getLocalPlayers());
    }
```

**d)** Replace the head of `mayFlip` (line ~297):

```java
    @Override
    public boolean mayFlip(final CardView cv) {
        if (spectatorMode) {
            // A face-down card's face is private; a face-up card's back is public.
            return !cv.isFaceDown();
        }
        if (cv == null) {
            return false;
        }
        final CardStateView altState = cv.getAlternateState();
        ... (rest unchanged) ...
    }
```

### Step 4: Implement the `openView` null-guard + self-synchronizing flag in `GameClientHandler`

In `forge-gui/src/main/java/forge/gamemodes/net/client/GameClientHandler.java`, replace the `case openView:` block in `beforeCall` (lines 110-123) with:

```java
            case openView:
                gui.setNetGame();
                // A new match's view resets any tournament flag from a previous match;
                // MatchStartedEvent (which the server sends after openView) re-arms it
                // for tournament matches, so WinLose selection is correct per match.
                gui.setTournamentMatch(false);
                // Self-synchronizing spectator mode: an openView with no local players
                // is a spectator view; one with local players is the client's own match.
                final TrackableCollection<PlayerView> myPlayers = (TrackableCollection<PlayerView>) args[0];
                gui.setSpectatorMode(myPlayers == null);
                if (myPlayers != null) {
                    for (PlayerView myPlayer : myPlayers) {
                        if (myPlayer.getTracker() == null) {
                            myPlayer.setTracker(this.tracker);
                        }
                    }
                    client.setGameControllers(myPlayers);
                }
                break;
```

### Step 5: Run the e2e — verify it passes

Run: `mvn --% -o -pl forge-gui-desktop -am test -Dtest=SpectateEndToEndTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS (the whole spectate flow, including hidden hands and no-disconnect).

### Step 6: Commit

```bash
git add forge-gui/src/main/java/forge/gamemodes/match/AbstractGuiGame.java
git add forge-gui/src/main/java/forge/gamemodes/net/client/GameClientHandler.java
git add forge-gui-desktop/src/test/java/forge/net/SpectateEndToEndTest.java
git commit -m "Add client spectator mode with hidden hands (online tournament spectating)"
```

---

## Task 3: Client — leave/auto-return, Stop Spectating, tournament-panel UI

**Files:**
- Modify: `forge-gui-desktop/src/main/java/forge/screens/match/CMatchUI.java`
- Modify: `forge-gui-desktop/src/main/java/forge/screens/match/menus/GameMenu.java`
- Modify: `forge-gui-desktop/src/main/java/forge/screens/home/online/VSubmenuOnlineLobby.java`
- Modify: `forge-gui-desktop/src/main/java/forge/screens/home/CLobby.java`
- Modify: `forge-gui-desktop/src/main/java/forge/screens/home/VLobby.java`

The headless e2e already covers the protocol-level effects (approval, hidden hands, leave cleanup). The desktop-only behaviors here are thin delegation (UI → `CLobby.leaveSpectating()`/`requestSpectate`, WinLose suppression), verified by compilation + manual test. A later manual check should confirm: spectator sees no WinLose, Game menu shows "Stop Spectating", the tournament panel lists ongoing matches.

### Step 1: `VSubmenuOnlineLobby.getLobbyView()`

In `forge-gui-desktop/src/main/java/forge/screens/home/online/VSubmenuOnlineLobby.java`, add after `getClient()` (line ~77):

```java
    public VLobby getLobbyView() {
        return this.lobby;
    }
```

### Step 2: `CMatchUI.finishGame()` spectator gate

In `forge-gui-desktop/src/main/java/forge/screens/match/CMatchUI.java`, replace `finishGame()` (lines 981-993) with:

```java
    @Override
    public void finishGame() {
        FloatingZone.closeAll(); //ensure floating card areas cleared and closed after the game
        if (isNetGame()) {
            writeMatchPreferences();
        }
        final GameView gameView = getGameView();
        if (isSpectatorMode()) {
            // Spectators never get a WinLose screen; afterGameEnd closes the tab.
            return;
        }
        if (hasLocalPlayers() || gameView.isMatchOver()) {
            new ViewWinLose(gameView, this).show();
        }
        if (showOverlay) {
            SOverlayUtils.showOverlay();
        }
    }
```

### Step 3: `GameMenu` — Stop Spectating item

In `forge-gui-desktop/src/main/java/forge/screens/match/menus/GameMenu.java`:

**a)** Add imports:

```java
import forge.screens.home.CLobby;
import forge.screens.home.VLobby;
import forge.screens.home.online.VSubmenuOnlineLobby;
```

**b)** In `getMenu()`, add the item after the separator at the end and refresh visibility in the existing `MenuListener`:

```java
        menu.add(getMenuItem_ClearRememberedAbilityOrders());
        final SkinnedMenuItem stopSpectating = getMenuItem_StopSpectating();
        menu.add(stopSpectating);
        menu.addMenuListener(new MenuListener() {
            @Override public void menuSelected(final MenuEvent e) {
                autoPassItem.setState(prefs.getPrefBoolean(FPref.YIELD_AUTO_PASS_NO_ACTIONS));
                stopSpectating.setVisible(matchUI.isSpectatorMode());
            }
            @Override public void menuDeselected(final MenuEvent e) {}
            @Override public void menuCanceled(final MenuEvent e) {}
        });
        return menu;
```

**c)** Add the item method:

```java
    private SkinnedMenuItem getMenuItem_StopSpectating() {
        final Localizer localizer = Localizer.getInstance();
        final SkinnedMenuItem menuItem = new SkinnedMenuItem("Stop Spectating");
        menuItem.setVisible(false);
        menuItem.addActionListener(e -> {
            final VLobby vLobby = VSubmenuOnlineLobby.SINGLETON_INSTANCE.getLobbyView();
            final CLobby cl = vLobby != null ? vLobby.getController() : null;
            if (cl != null) {
                cl.leaveSpectating();
            }
            matchUI.afterGameEnd(); //close the game tab and return to the lobby
        });
        return menuItem;
    }
```

### Step 4: `CLobby` — spectatingMatchId + leave

In `forge-gui-desktop/src/main/java/forge/screens/home/CLobby.java`:

**a)** Add a field near the other tournament fields:

```java
    private String spectatingMatchId = null;
```

**b)** Replace `onSpectateApproved` (lines 652-655):

```java
    @Override
    public void onSpectateApproved(SpectateApprovedEvent event) {
        final String id = event.getMatchId();
        if (id != null) {
            spectatingMatchId = id;
        }
        SwingUtilities.invokeLater(() -> view.showSpectateView(id));
    }

    /** Stop watching the current spectate: clear state, drop spectator mode, tell the server. */
    void leaveSpectating() {
        if (spectatingMatchId == null) { return; }
        final String id = spectatingMatchId;
        spectatingMatchId = null;
        final FGameClient client = VSubmenuOnlineLobby.SINGLETON_INSTANCE.getClient();
        if (client != null) {
            if (client.getGui() instanceof AbstractGuiGame agg) {
                agg.setSpectatorMode(false);
            }
            client.send(new SpectateLeaveEvent(id));
        }
    }
```

Add imports if missing: `forge.gamemodes.net.event.SpectateLeaveEvent` (CLobby's `forge.gamemodes.net.event.*` wildcard already covers it, so no new import is needed).

**c)** Update `onMatchStarted` (lines 618-629) to clear the id when the client's own match starts:

```java
    @Override
    public void onMatchStarted(MatchStartedEvent event) {
        currentRoundState = RoundState.ACTIVE;
        // A tournament match is starting: mark the shared client GUI so the WinLose
        // screen picks the tournament controller. The host's own GUI is marked via
        // HostedMatch.setTournamentMatch instead (there is no FGameClient on the host).
        FGameClient client = VSubmenuOnlineLobby.SINGLETON_INSTANCE.getClient();
        if (client != null && client.getGui() instanceof AbstractGuiGame agg) {
            agg.setTournamentMatch(true);
            // Own match starting ends any spectate (the server cleaned it up). Keep the
            // id only if still in spectator mode (a guest watching others' matches).
            if (spectatingMatchId != null && !agg.isSpectatorMode()) {
                spectatingMatchId = null;
            }
        }
        SwingUtilities.invokeLater(view::updateRightPanelForMode);
    }
```

### Step 5: `VLobby` — Ongoing Matches list + Spectate button + showSpectateView

In `forge-gui-desktop/src/main/java/forge/screens/home/VLobby.java` (no new imports needed — `java.util.*`, `javax.swing.*`, `forge.gamemodes.net.*`, and `forge.toolbox.*` wildcards already cover `ArrayList`, `JList`, `ListSelectionModel`, `FButton`, and `PairingView`):

**a)** In `refreshTournamentPanel()`, after `tournamentPanel.add(lblTournamentPairings, "gaptop 10, wrap");` (line ~988), add:

```java
        // Ongoing matches: pick one to spectate.
        final List<PairingView> ongoing = new ArrayList<>();
        for (PairingView p : pairings) {
            if (p.status() == PairingView.PairingStatus.ONGOING && p.matchId() != null) {
                ongoing.add(p);
            }
        }
        if (!ongoing.isEmpty()) {
            final JList<String> spectateList = new JList<>(ongoing.stream()
                    .map(p -> p.playerAName() + " vs " + p.playerBName())
                    .toArray(String[]::new));
            spectateList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            final FButton btnSpectate = new FButton("Spectate");
            btnSpectate.setEnabled(false);
            spectateList.addListSelectionListener(e ->
                    btnSpectate.setEnabled(spectateList.getSelectedIndex() >= 0));
            btnSpectate.addActionListener(e -> {
                int idx = spectateList.getSelectedIndex();
                if (idx >= 0 && getController() != null) {
                    getController().requestSpectate(ongoing.get(idx).matchId());
                }
            });
            tournamentPanel.add(new FLabel.Builder().text("Ongoing Matches:").build(),
                    "gaptop 5, wrap");
            tournamentPanel.add(spectateList, "w 100%, h 60!, wrap");
            tournamentPanel.add(btnSpectate, "w 70!, h 26!, wrap");
        }
```

`FLabel` is already used in `VLobby` (the tournament title labels). `List<PairingView>`/`ArrayList` resolve via `java.util.*` + `forge.gamemodes.net.*`.

**b)** Replace `showSpectateView` (lines 1011-1014):

```java
    void showSpectateView(String matchId) {
        if (matchId == null) {
            FOptionPane.showMessageDialog("Cannot spectate that match.", "Spectate",
                    FSkin.getIcon(FSkinProp.ICO_WARNING));
        }
        // On success the game screen opens via the openView stream; nothing to do here.
    }
```

### Step 6: Compile and run the full spectate + tournament suite

Run: `mvn --% -o -pl forge-gui-desktop -am test -Dtest=SpectateEndToEndTest,SpectateServerTest,TournamentEndToEndTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS.

### Step 7: Commit

```bash
git add forge-gui-desktop/src/main/java/forge/screens/match/CMatchUI.java
git add forge-gui-desktop/src/main/java/forge/screens/match/menus/GameMenu.java
git add forge-gui-desktop/src/main/java/forge/screens/home/online/VSubmenuOnlineLobby.java
git add forge-gui-desktop/src/main/java/forge/screens/home/CLobby.java
git add forge-gui-desktop/src/main/java/forge/screens/home/VLobby.java
git commit -m "Add online tournament spectate UI, Stop Spectating, and WinLose gating"
```

---

## Task 4: Update living doc + full-suite verification

**Files:**
- Modify: `docs/superpowers/specs/2026-08-16-tournament-current-state.md`

### Step 1: Mark Gap 5 done in the living doc

In the "Recommended Next Steps" section, update item 7 (Gap 5):

```markdown
7. ~~**Gap 5** — complete the client side of spectating.~~ **Done 2026-09-01** — lobby-only
   spectating with client-side hidden hands: Ongoing Matches list + Spectate button in the
   tournament panel, spectator mode on the shared client GUI (armed from `openView`
   `myPlayers` null-ness), hidden-hands `mayView`/`mayFlip`, server guards + `"spectate:"`
   key convention + leave teardown, Game-menu "Stop Spectating", WinLose suppression, and a
   headless e2e (`SpectateEndToEndTest`). See
   `docs/superpowers/specs/2026-09-01-online-tournament-spectate-design.md`.
```

Also append a short status line to the Gap 5 section body (after the existing "Guidance" list):

```markdown
> **Status: RESOLVED 2026-09-01** — implemented per the design doc
> `docs/superpowers/specs/2026-09-01-online-tournament-spectate-design.md`. Left as known
> limitations for a future phase: concurrent spectating while playing your own match,
> server-side hand filtering on the wire, reconnect-while-spectating, and mobile spectate UI.
```

### Step 2: Run the full net/match suite

Run: `mvn --% -o -pl forge-gui-desktop -am test`

Expected: all net/match tests pass (existing ~78 plus the new `SpectateServerTest` and `SpectateEndToEndTest`), 0 failures. Stress-gated batch tests remain skipped.

### Step 3: Commit

```bash
git add docs/superpowers/specs/2026-08-16-tournament-current-state.md
git commit -m "Mark online tournament spectating (Gap 5) done in living doc"
```

---

## Out of scope (this phase)

- Concurrent spectating while playing your own match (multi-tracker wire codecs).
- Server-side hand filtering on the wire (hidden info still crosses the wire; the client renders only public info).
- Reconnect-while-spectating (a reconnected spectator must re-request).
- Host-approval gate.
- Libgdx/mobile spectate UI (desktop `CMatchUI` only).
- Automated desktop-UI tests for the tournament panel list / Game-menu item / WinLose suppression (compile-verified + manual).
