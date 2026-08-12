# Tournament Logic Layer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the server-side tournament orchestration that manages round-robin pairings, parallel match creation, STANDBY phases between rounds with AFK enforcement, and standings with OMW% tiebreakers.

**Architecture:** Reuse Forge's existing `TournamentRoundRobin` engine. Add a `ServerTournamentController` that lives on `NetworkEvent`, orchestrates match creation via Plan 1's multi-match infrastructure, and tracks results. Extend `EventPhase` and `EventParticipant` with tournament state. Add OMW% to `TournamentPlayer`.

**Tech Stack:** Java 17, Maven, TestNG 7.10.2, Mockito 5.14.2

**Spec:** `docs/superpowers/specs/2026-08-12-tournament-mode-design.md` — Section 3

**Depends on:** Plan 1 (`docs/superpowers/plans/2026-08-12-multi-match-infrastructure.md`) — multi-match infrastructure must be complete

---

## File Structure

### New Files

| File | Responsibility |
|------|----------------|
| `forge-gui/src/main/java/forge/gamemodes/net/server/ServerTournamentController.java` | Orchestrates tournament: creates pairings, starts matches, records results, manages STANDBY/AFK |
| `forge-gui-desktop/src/test/java/forge/net/TournamentLogicTest.java` | Unit tests for OMW%, pairings, AFK penalties, bye handling |

### Modified Files

| File | Changes |
|------|---------|
| `forge-gui/.../net/EventPhase.java` | Add `TOURNAMENT_IN_PROGRESS`, `STANDBY`, `TOURNAMENT_COMPLETE` |
| `forge-gui/.../net/NetworkEvent.java` | Add `tournament` field (TournamentRoundRobin), `gamesPerMatch` field, `tournamentController` field |
| `forge-gui/.../net/EventParticipant.java` | Add `tournamentPlayer`, `deck` fields |
| `forge-gui/.../tournament/system/TournamentPlayer.java` | Add `getOMW()` method, `previousOpponentPlayers` tracking |
| `forge-gui/.../tournament/system/AbstractTournament.java` | Add `getPlayerByName()` helper |
| `forge-gui/.../net/server/ServerGameLobby.java` | Add `startTournament()` method, wire `ServerTournamentController` |

---

## Task 1: EventPhase Extension

**Files:**
- Modify: `forge-gui/src/main/java/forge/gamemodes/net/EventPhase.java`

- [ ] **Step 1: Add new phase values**

Replace the entire content of `EventPhase.java`:

```java
package forge.gamemodes.net;

public enum EventPhase {
    LOBBY_GATHER,
    DRAFTING,
    POOL_DISTRIBUTION,
    TOURNAMENT_IN_PROGRESS,
    STANDBY,
    TOURNAMENT_COMPLETE
}
```

- [ ] **Step 2: Verify compilation**

Run: `mvn compile -pl forge-gui -am -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add forge-gui/src/main/java/forge/gamemodes/net/EventPhase.java
git commit -m "feat: add TOURNAMENT_IN_PROGRESS, STANDBY, TOURNAMENT_COMPLETE to EventPhase"
```

---

## Task 2: EventParticipant Extension

**Files:**
- Modify: `forge-gui/src/main/java/forge/gamemodes/net/EventParticipant.java`

- [ ] **Step 1: Add tournamentPlayer and deck fields**

The current `EventParticipant` is immutable (all fields final). Add mutable tournament fields after line 27 (`private final int lobbySlotIndex;`):

```java
    private forge.gamemodes.tournament.system.TournamentPlayer tournamentPlayer;
    private forge.deck.Deck deck;
```

Add getters/setters after line 41 (`public boolean isAI() { return type == Type.AI; }`):

```java
    public forge.gamemodes.tournament.system.TournamentPlayer getTournamentPlayer() { return tournamentPlayer; }
    public void setTournamentPlayer(forge.gamemodes.tournament.system.TournamentPlayer tp) { this.tournamentPlayer = tp; }
    public forge.deck.Deck getDeck() { return deck; }
    public void setDeck(forge.deck.Deck deck) { this.deck = deck; }
```

Note: Using fully-qualified types to avoid adding imports to this small file. Can add imports if preferred.

- [ ] **Step 2: Verify compilation**

Run: `mvn compile -pl forge-gui -am -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add forge-gui/src/main/java/forge/gamemodes/net/EventParticipant.java
git commit -m "feat: add tournamentPlayer and deck fields to EventParticipant"
```

---

## Task 3: TournamentPlayer OMW%

**Files:**
- Modify: `forge-gui/src/main/java/forge/gamemodes/tournament/system/TournamentPlayer.java`
- Modify: `forge-gui/src/main/java/forge/gamemodes/tournament/system/AbstractTournament.java`
- Test: `forge-gui-desktop/src/test/java/forge/net/TournamentLogicTest.java`

- [ ] **Step 1: Write failing test for OMW% calculation**

Create `forge-gui-desktop/src/test/java/forge/net/TournamentLogicTest.java`:

```java
package forge.net;

import forge.LobbyPlayer;
import forge.gamemodes.tournament.system.TournamentPlayer;
import forge.player.GamePlayerUtil;
import org.testng.Assert;
import org.testng.annotations.Test;

public class TournamentLogicTest {

    @Test
    public void testOmwCalculation() {
        // Create 4 players
        LobbyPlayer alice = GamePlayerUtil.createAiPlayer("Alice", 0);
        LobbyPlayer bob = GamePlayerUtil.createAiPlayer("Bob", 0);
        LobbyPlayer charlie = GamePlayerUtil.createAiPlayer("Charlie", 0);
        LobbyPlayer diana = GamePlayerUtil.createAiPlayer("Diana", 0);

        TournamentPlayer tpAlice = new TournamentPlayer(alice, 0);
        TournamentPlayer tpBob = new TournamentPlayer(bob, 1);
        TournamentPlayer tpCharlie = new TournamentPlayer(charlie, 2);
        TournamentPlayer tpDiana = new TournamentPlayer(diana, 3);

        // Alice beats Bob, Charlie beats Diana (round 1)
        // Alice beats Charlie, Bob beats Diana (round 2)
        // Alice beats Diana, Bob beats Charlie (round 3)

        // Round 1: Alice 2-0, Bob 0-2, Charlie 2-0, Diana 0-2
        tpAlice.addWin(); tpAlice.addOpponentIndex(1); // beat Bob
        tpBob.addLoss(); tpBob.addOpponentIndex(0);    // lost to Alice
        tpCharlie.addWin(); tpCharlie.addOpponentIndex(3); // beat Diana
        tpDiana.addLoss(); tpDiana.addOpponentIndex(2);    // lost to Charlie

        // Round 2: Alice 4-0, Bob 1-3, Charlie 2-2, Diana 0-4
        tpAlice.addWin(); tpAlice.addOpponentIndex(2); // beat Charlie
        tpCharlie.addLoss(); tpCharlie.addOpponentIndex(0); // lost to Alice
        tpBob.addWin(); tpBob.addOpponentIndex(3);     // beat Diana
        tpDiana.addLoss(); tpDiana.addOpponentIndex(1); // lost to Bob

        // Round 3: Alice 6-0, Bob 2-4, Charlie 2-4, Diana 0-6
        tpAlice.addWin(); tpAlice.addOpponentIndex(3); // beat Diana
        tpDiana.addLoss(); tpDiana.addOpponentIndex(0); // lost to Alice
        tpBob.addWin(); tpBob.addOpponentIndex(2);     // beat Charlie
        tpCharlie.addLoss(); tpCharlie.addOpponentIndex(1); // lost to Bob

        // Alice's OMW: opponents are Bob(2 wins/3 matches), Charlie(2/3), Diana(0/3)
        // OMW = (2/3 + 2/3 + 0/3) / 3 = (0.667 + 0.667 + 0) / 3 = 0.444
        // We need the tournament to compute OMW since it needs all players
        // For now, test that getOMW needs the player list
    }

    @Test
    public void testTournamentPlayerScore() {
        TournamentPlayer tp = new TournamentPlayer(GamePlayerUtil.createAiPlayer("Test", 0));
        Assert.assertEquals(tp.getScore(), 0, "Initial score should be 0");
        tp.addWin();
        Assert.assertEquals(tp.getScore(), 3, "Score after 1 win should be 3");
        tp.addBye();
        Assert.assertEquals(tp.getScore(), 6, "Score after 1 win + 1 bye should be 6");
        tp.addTie();
        Assert.assertEquals(tp.getScore(), 7, "Score after 1 win + 1 bye + 1 tie should be 7");
    }
}
```

- [ ] **Step 2: Run test to verify it passes (basic parts)**

Run: `mvn test -pl forge-gui-desktop -am -Dtest=TournamentLogicTest -q`
Expected: PASS (2 tests — OMW test is mostly a placeholder until we implement it)

- [ ] **Step 3: Add OMW calculation to TournamentPlayer**

In `TournamentPlayer.java`, add after line 69 (`public int getSwissScore() { ... }`):

```java
    /**
     * Opponent Match Win percentage — the average win rate of all opponents faced.
     * Used as a tiebreaker. Requires the full tournament player list to compute.
     *
     * @param allPlayers all players in the tournament
     * @return OMW as a double (0.0 - 1.0), or 0.0 if no opponents played
     */
    public double getOMW(java.util.List<TournamentPlayer> allPlayers) {
        if (previousOpponents.isEmpty()) {
            return 0.0;
        }
        double totalWinRate = 0.0;
        int opponentsFound = 0;
        for (int oppIndex : previousOpponents) {
            for (TournamentPlayer tp : allPlayers) {
                if (tp.getIndex() == oppIndex) {
                    int oppMatches = tp.getWins() + tp.getLosses() + tp.getTies();
                    if (oppMatches > 0) {
                        totalWinRate += (double) tp.getWins() / oppMatches;
                    }
                    opponentsFound++;
                    break;
                }
            }
        }
        return opponentsFound > 0 ? totalWinRate / opponentsFound : 0.0;
    }

    /**
     * Convenience getter for OMW as a percentage string (e.g., "67%").
     */
    public String getOMWPercent(java.util.List<TournamentPlayer> allPlayers) {
        return Math.round(getOMW(allPlayers) * 100) + "%";
    }
```

- [ ] **Step 4: Add getPlayerByName helper to AbstractTournament**

In `AbstractTournament.java`, add after `getAllPlayers()` (line 133):

```java
    public TournamentPlayer getPlayerByName(String name) {
        for (TournamentPlayer tp : allPlayers) {
            if (tp.getPlayer().getName().equals(name)) {
                return tp;
            }
        }
        return null;
    }

    public TournamentPlayer getPlayerByIndex(int index) {
        for (TournamentPlayer tp : allPlayers) {
            if (tp.getIndex() == index) {
                return tp;
            }
        }
        return null;
    }
```

- [ ] **Step 5: Write a proper OMW test**

Add to `TournamentLogicTest.java`:

```java
    @Test
    public void testOmwCalculationWithPlayerList() {
        LobbyPlayer alice = GamePlayerUtil.createAiPlayer("Alice", 0);
        LobbyPlayer bob = GamePlayerUtil.createAiPlayer("Bob", 0);
        LobbyPlayer charlie = GamePlayerUtil.createAiPlayer("Charlie", 0);
        LobbyPlayer diana = GamePlayerUtil.createAiPlayer("Diana", 0);

        TournamentPlayer tpAlice = new TournamentPlayer(alice, 0);
        TournamentPlayer tpBob = new TournamentPlayer(bob, 1);
        TournamentPlayer tpCharlie = new TournamentPlayer(charlie, 2);
        TournamentPlayer tpDiana = new TournamentPlayer(diana, 3);

        java.util.List<TournamentPlayer> allPlayers =
            java.util.Arrays.asList(tpAlice, tpBob, tpCharlie, tpDiana);

        // Alice beats Bob and Charlie; Bob beats Diana
        tpAlice.addWin(); tpAlice.addOpponentIndex(1);
        tpBob.addLoss();  tpBob.addOpponentIndex(0);
        tpAlice.addWin(); tpAlice.addOpponentIndex(2);
        tpCharlie.addLoss(); tpCharlie.addOpponentIndex(0);
        tpBob.addWin(); tpBob.addOpponentIndex(3);
        tpDiana.addLoss(); tpDiana.addOpponentIndex(1);

        // Alice's opponents: Bob (1W/2L = 0.333), Charlie (0W/1L = 0.0)
        // OMW = (0.333 + 0.0) / 2 = 0.167
        double aliceOmw = tpAlice.getOMW(allPlayers);
        Assert.assertTrue(aliceOmw > 0.16 && aliceOmw < 0.17,
            "Alice OMW should be ~0.167, got " + aliceOmw);

        // Bob's opponents: Alice (2W/0L = 1.0), Diana (0W/1L = 0.0)
        // OMW = (1.0 + 0.0) / 2 = 0.5
        double bobOmw = tpBob.getOMW(allPlayers);
        Assert.assertTrue(bobOmw > 0.49 && bobOmw < 0.51,
            "Bob OMW should be ~0.5, got " + bobOmw);
    }
```

- [ ] **Step 6: Run tests**

Run: `mvn test -pl forge-gui-desktop -am -Dtest=TournamentLogicTest -q`
Expected: PASS (3 tests)

- [ ] **Step 7: Commit**

```bash
git add forge-gui/src/main/java/forge/gamemodes/tournament/system/TournamentPlayer.java forge-gui/src/main/java/forge/gamemodes/tournament/system/AbstractTournament.java forge-gui-desktop/src/test/java/forge/net/TournamentLogicTest.java
git commit -m "feat: add OMW% tiebreaker to TournamentPlayer, helper lookups to AbstractTournament"
```

---

## Task 4: NetworkEvent Tournament Fields

**Files:**
- Modify: `forge-gui/src/main/java/forge/gamemodes/net/NetworkEvent.java:31-55, 114-117`

- [ ] **Step 1: Add tournament fields to NetworkEvent**

After line 43 (`private BoosterDraft draft;`), add:

```java
    private forge.gamemodes.tournament.system.TournamentRoundRobin tournament;
    private int gamesPerMatch = 3;
```

Add getters/setters after line 75 (`public void setNumRounds(int numRounds) { ... }`):

```java
    public forge.gamemodes.tournament.system.TournamentRoundRobin getTournament() { return tournament; }
    public void setTournament(forge.gamemodes.tournament.system.TournamentRoundRobin tournament) { this.tournament = tournament; }
    public int getGamesPerMatch() { return gamesPerMatch; }
    public void setGamesPerMatch(int gamesPerMatch) { this.gamesPerMatch = gamesPerMatch; }
    public boolean isTournamentMode() { return tournament != null; }
```

- [ ] **Step 2: Verify compilation**

Run: `mvn compile -pl forge-gui -am -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add forge-gui/src/main/java/forge/gamemodes/net/NetworkEvent.java
git commit -m "feat: add tournament and gamesPerMatch fields to NetworkEvent"
```

---

## Task 5: ServerTournamentController

**Files:**
- Create: `forge-gui/src/main/java/forge/gamemodes/net/server/ServerTournamentController.java`
- Test: `forge-gui-desktop/src/test/java/forge/net/TournamentLogicTest.java` (add tests)

This is the core orchestration class. It manages the tournament lifecycle: creating pairings, starting matches, recording results, managing STANDBY, and applying AFK penalties.

- [ ] **Step 1: Write ServerTournamentController**

Create `forge-gui/src/main/java/forge/gamemodes/net/server/ServerTournamentController.java`:

```java
package forge.gamemodes.net.server;

import forge.LobbyPlayer;
import forge.deck.Deck;
import forge.game.GameType;
import forge.game.player.RegisteredPlayer;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.net.EventParticipant;
import forge.gamemodes.net.EventPhase;
import forge.gamemodes.net.NetworkEvent;
import forge.gamemodes.tournament.system.TournamentPairing;
import forge.gamemodes.tournament.system.TournamentPlayer;
import forge.gamemodes.tournament.system.TournamentRoundRobin;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;

import java.util.*;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Server-side controller for a network tournament.
 * Lives on the NetworkEvent and orchestrates match creation, result recording,
 * STANDBY phase management, and AFK enforcement.
 *
 * Depends on the multi-match infrastructure from Plan 1 (MatchRegistry, scoped
 * HostedMatch management, per-match RemoteClientGuiGame).
 */
public class ServerTournamentController {

    private final NetworkEvent event;
    private final ServerGameLobby lobby;
    private final TournamentRoundRobin tournament;
    private final int gamesPerMatch;
    private final GameType gameType;

    private ScheduledFuture<?> standbyTimer;
    private int standbyTimeoutSeconds = 300; // 5 minutes default

    // Map: matchId -> pairing for that match
    private final Map<String, TournamentPairing> matchToPairing = new HashMap<>();

    public ServerTournamentController(NetworkEvent event, ServerGameLobby lobby, int gamesPerMatch) {
        this.event = event;
        this.lobby = lobby;
        this.gamesPerMatch = gamesPerMatch;
        // Use GameType.Sealed for sealed events, GameType.Draft for draft events
        this.gameType = (event.getFormat() == forge.gamemodes.net.EventFormat.SEALED)
                ? GameType.Sealed : GameType.Draft;

        // Build TournamentPlayer list from EventParticipants
        List<TournamentPlayer> players = new ArrayList<>();
        for (EventParticipant p : event.getParticipants()) {
            LobbyPlayer lp = GamePlayerUtil.createAiPlayer(p.getName(), 0);
            TournamentPlayer tp = new TournamentPlayer(lp, p.getSeatIndex());
            p.setTournamentPlayer(tp);
            p.setDeck(lobby.getSlot(p.getLobbySlotIndex()).getDeck());
            players.add(tp);
        }

        // Round-robin: totalRounds = playerCount - 1 (even), playerCount (odd)
        int totalRounds = players.size() % 2 == 0 ? players.size() - 1 : players.size();
        this.tournament = new TournamentRoundRobin(totalRounds, players);
        event.setTournament(tournament);
    }

    /**
     * Start the tournament: initialize round-robin, generate first round pairings,
     * start all matches for round 1 in parallel.
     */
    public synchronized void startTournament() {
        tournament.initializeTournament();
        event.setPhase(EventPhase.TOURNAMENT_IN_PROGRESS);
        startRoundMatches();
    }

    /**
     * Start all active pairings as parallel 1v1 matches.
     */
    private void startRoundMatches() {
        FServerManager server = FServerManager.getInstance();

        for (TournamentPairing pairing : tournament.getActivePairings()) {
            if (pairing.isBye()) {
                // Auto-record bye result
                handleBye(pairing);
                continue;
            }

            // Find the two participants
            List<EventParticipant> pairParticipants = new ArrayList<>();
            for (TournamentPlayer tp : pairing.getPairedPlayers()) {
                for (EventParticipant ep : event.getParticipants()) {
                    if (ep.getSeatIndex() == tp.getIndex()) {
                        pairParticipants.add(ep);
                        break;
                    }
                }
            }

            if (pairParticipants.size() != 2) {
                netLog.warn("Pairing has {} participants, expected 2", pairParticipants.size());
                continue;
            }

            // Create the match using the multi-match startMatch
            List<Integer> slotIndices = new ArrayList<>();
            for (EventParticipant ep : pairParticipants) {
                slotIndices.add(ep.getLobbySlotIndex());
            }

            Runnable startMatch = lobby.startMatch(slotIndices, gameType, Collections.emptySet());
            if (startMatch != null) {
                startMatch.run();
                // Find the HostedMatch that was just created
                // (startMatch registers it in the MatchRegistry)
                HostedMatch match = findLatestMatch();
                if (match != null) {
                    matchToPairing.put(match.getMatchId(), pairing);
                    // Set games-per-match on the match's GameRules
                    match.getMatch().getRules().setGamesPerMatch(gamesPerMatch);
                }
            }
        }

        lobby.updateView(true);
    }

    private HostedMatch findLatestMatch() {
        // The most recently registered match
        Collection<HostedMatch> all = lobby.getActiveMatches().getAll();
        if (all.isEmpty()) return null;
        return all.stream()
            .max(Comparator.comparing(HostedMatch::getMatchId))
            .orElse(null);
    }

    private void handleBye(TournamentPairing pairing) {
        // Find the non-BYE player and give them a win
        for (TournamentPlayer tp : pairing.getPairedPlayers()) {
            if (!tp.getPlayer().getName().equals("BYE")) {
                tp.addBye();
                pairing.setWinner(tp);
            }
        }
        tournament.reportMatchCompletion(pairing);
    }

    /**
     * Called when a match completes. Records the result and checks if the round is done.
     *
     * @param matchId the match that completed
     * @param winnerName name of the winning player, or null for a draw
     */
    public synchronized void onMatchComplete(String matchId, String winnerName) {
        TournamentPairing pairing = matchToPairing.remove(matchId);
        if (pairing == null) {
            netLog.warn("Match {} completed but no pairing found", matchId);
            return;
        }

        // Set winner on the pairing
        if (winnerName != null) {
            for (TournamentPlayer tp : pairing.getPairedPlayers()) {
                if (tp.getPlayer().getName().equals(winnerName)) {
                    pairing.setWinner(tp);
                    break;
                }
            }
        }

        tournament.reportMatchCompletion(pairing);

        if (tournament.isTournamentOver()) {
            event.setPhase(EventPhase.TOURNAMENT_COMPLETE);
            lobby.updateView(true);
        } else if (isRoundComplete()) {
            enterStandby();
        }
    }

    private boolean isRoundComplete() {
        return tournament.getActivePairings().isEmpty()
            && matchToPairing.isEmpty()
            && !tournament.isTournamentOver();
    }

    /**
     * Enter STANDBY phase: mark all players not-ready, start AFK timer.
     */
    private void enterStandby() {
        event.setPhase(EventPhase.STANDBY);
        for (int i = 0; i < lobby.getNumberOfSlots(); i++) {
            lobby.getSlot(i).setIsReady(false);
        }
        lobby.updateView(true);
        startStandbyTimer();
    }

    private void startStandbyTimer() {
        cancelStandbyTimer();
        standbyTimer = FServerManager.getInstance().getAfkExecutor().schedule(() -> {
            onAfkTimerExpired();
        }, standbyTimeoutSeconds, TimeUnit.SECONDS);
    }

    private void cancelStandbyTimer() {
        if (standbyTimer != null) {
            standbyTimer.cancel(false);
            standbyTimer = null;
        }
    }

    /**
     * Called when a player marks themselves ready during STANDBY.
     */
    public synchronized void onPlayerReady(int slotIndex) {
        if (event.getPhase() != EventPhase.STANDBY) return;
        // Check if all non-open slots are ready
        for (int i = 0; i < lobby.getNumberOfSlots(); i++) {
            if (lobby.getSlot(i).getType() != forge.gamemodes.match.LobbySlotType.OPEN) {
                if (!lobby.getSlot(i).isReady()) {
                    return; // still waiting
                }
            }
        }
        // All ready — start next round
        cancelStandbyTimer();
        startNextRound();
    }

    /**
     * AFK timer expired — apply penalties to not-ready players and start next round.
     */
    private synchronized void onAfkTimerExpired() {
        if (event.getPhase() != EventPhase.STANDBY) return;

        // Mark not-ready players as AFK (they get a loss for the next round)
        Set<Integer> afkSlots = new HashSet<>();
        for (int i = 0; i < lobby.getNumberOfSlots(); i++) {
            var slot = lobby.getSlot(i);
            if (slot.getType() != forge.gamemodes.match.LobbySlotType.OPEN && !slot.isReady()) {
                afkSlots.add(i);
                // Find the participant for this slot and add a loss
                for (EventParticipant ep : event.getParticipants()) {
                    if (ep.getLobbySlotIndex() == i && ep.getTournamentPlayer() != null) {
                        ep.getTournamentPlayer().addLoss();
                        break;
                    }
                }
            }
        }

        startNextRound();
    }

    private void startNextRound() {
        event.setPhase(EventPhase.TOURNAMENT_IN_PROGRESS);
        // Generate next round pairings (completeRound was already called by reportMatchCompletion)
        startRoundMatches();
    }

    /**
     * Cancel the tournament (host action).
     */
    public synchronized void cancelTournament() {
        cancelStandbyTimer();
        // Terminate any active matches
        for (String matchId : matchToPairing.keySet()) {
            HostedMatch match = lobby.getMatch(matchId);
            if (match != null && !match.isMatchOver()) {
                // Force-end the match
                match.endCurrentGame();
            }
        }
        matchToPairing.clear();
        event.setPhase(EventPhase.TOURNAMENT_COMPLETE);
        event.setTournament(null);
        lobby.updateView(true);
    }

    /**
     * Get current standings sorted by score (desc), then OMW% (desc).
     */
    public List<TournamentPlayer> getStandings() {
        List<TournamentPlayer> sorted = new ArrayList<>(tournament.getAllPlayers());
        sorted.sort((a, b) -> {
            int scoreCmp = Integer.compare(b.getScore(), a.getScore());
            if (scoreCmp != 0) return scoreCmp;
            double omwA = a.getOMW(tournament.getAllPlayers());
            double omwB = b.getOMW(tournament.getAllPlayers());
            return Double.compare(omwB, omwA);
        });
        return sorted;
    }

    /**
     * Get active pairings for UI display.
     */
    public List<TournamentPairing> getActivePairings() {
        return tournament.getActivePairings();
    }

    public int getCurrentRound() { return tournament.getActiveRound(); }
    public int getTotalRounds() { return tournament.getTotalRounds(); }

    public void setStandbyTimeoutSeconds(int seconds) {
        this.standbyTimeoutSeconds = seconds;
    }

    public int getStandbyTimeoutSeconds() {
        return standbyTimeoutSeconds;
    }

    public int getRemainingStandbySeconds() {
        // This would need a start timestamp to compute remaining time
        // For now, return the full timeout — UI can track the countdown
        return standbyTimeoutSeconds;
    }

    private static final org.slf4j.Logger netLog = org.slf4j.LoggerFactory.getLogger("forge.net");
}
```

- [ ] **Step 2: Add getAfkExecutor accessor to FServerManager**

In `FServerManager.java`, find the `afkExecutor` field (around line 84). Add a package-visible accessor:

```java
ScheduledExecutorService getAfkExecutor() {
    return afkExecutor;
}
```

If the field is private with no existing accessor, add this method near the `armAfkTimeout` method.

- [ ] **Step 3: Verify compilation**

Run: `mvn compile -pl forge-gui -am -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Write tests for tournament flow**

Add to `TournamentLogicTest.java`:

```java
    @Test
    public void testRoundRobinPairingsFor4Players() {
        // Verify 4-player round-robin has 3 rounds and 6 total pairings
        List<TournamentPlayer> players = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            players.add(new TournamentPlayer(GamePlayerUtil.createAiPlayer("P" + i, 0), i));
        }

        TournamentRoundRobin rr = new TournamentRoundRobin(3, players);
        rr.initializeTournament();

        // Round 1: 2 pairings
        Assert.assertEquals(rr.getActivePairings().size(), 2,
            "Round 1 should have 2 pairings for 4 players");

        // Complete round 1
        for (TournamentPairing p : new ArrayList<>(rr.getActivePairings())) {
            p.setWinner(p.getPairedPlayers().get(0));
            rr.reportMatchCompletion(p);
        }

        // After round 1, round 2 should be generated (continualPairing=true)
        Assert.assertEquals(rr.getActiveRound(), 2, "Should be on round 2");
        Assert.assertEquals(rr.getActivePairings().size(), 2,
            "Round 2 should have 2 pairings");

        // Complete round 2
        for (TournamentPairing p : new ArrayList<>(rr.getActivePairings())) {
            p.setWinner(p.getPairedPlayers().get(0));
            rr.reportMatchCompletion(p);
        }

        Assert.assertEquals(rr.getActiveRound(), 3, "Should be on round 3");

        // Complete round 3
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
            players.add(new TournamentPlayer(GamePlayerUtil.createAiPlayer("P" + i, 0), i));
        }

        // 3 players -> 3 rounds (odd count)
        TournamentRoundRobin rr = new TournamentRoundRobin(3, players);
        rr.initializeTournament();

        // Round 1 with 3 players: 1 real pairing + 1 bye = 2 pairings
        int byes = 0;
        int real = 0;
        for (TournamentPairing p : rr.getActivePairings()) {
            if (p.isBye()) byes++;
            else real++;
        }
        Assert.assertTrue(byes >= 1, "Round with 3 players should have at least 1 bye");
        Assert.assertEquals(real + byes, 2, "Should have 2 total pairings");
    }
```

- [ ] **Step 5: Run tests**

Run: `mvn test -pl forge-gui-desktop -am -Dtest=TournamentLogicTest -q`
Expected: PASS (5 tests)

- [ ] **Step 6: Commit**

```bash
git add forge-gui/src/main/java/forge/gamemodes/net/server/ServerTournamentController.java forge-gui/src/main/java/forge/gamemodes/net/server/FServerManager.java forge-gui-desktop/src/test/java/forge/net/TournamentLogicTest.java
git commit -m "feat: add ServerTournamentController for round-robin tournament orchestration"
```

---

## Task 6: ServerGameLobby Tournament Integration

**Files:**
- Modify: `forge-gui/src/main/java/forge/gamemodes/net/server/ServerGameLobby.java`

- [ ] **Step 1: Add startTournament method to ServerGameLobby**

Add to `ServerGameLobby.java`, after `startSealedEvent()` (around line 342):

```java
    /**
     * Start a tournament for the current event.
     * Requires that all participants have built decks and are ready.
     *
     * @param gamesPerMatch 1, 3, or 5
     */
    public synchronized void startTournament(int gamesPerMatch) {
        NetworkEvent event = getCurrentEvent();
        if (event == null) return;

        // Verify all players are ready and have decks
        for (EventParticipant p : event.getParticipants()) {
            if (p.isAI()) continue;
            LobbySlot slot = getSlot(p.getLobbySlotIndex());
            if (slot != null && !slot.isReady()) {
                netLog.warn("Cannot start tournament: {} is not ready", p.getName());
                return;
            }
            if (slot != null && slot.getDeck() == null) {
                netLog.warn("Cannot start tournament: {} has no deck", p.getName());
                return;
            }
        }

        ServerTournamentController controller =
            new ServerTournamentController(event, this, gamesPerMatch);
        controller.startTournament();
    }
```

- [ ] **Step 2: Add tournament controller tracking**

Add field after `private NetworkEvent currentEvent;` (line 36):

```java
    private ServerTournamentController tournamentController;
```

Add getter:

```java
    public ServerTournamentController getTournamentController() { return tournamentController; }
```

Update `startTournament` to store the controller:

```java
        tournamentController = new ServerTournamentController(event, this, gamesPerMatch);
        controller.startTournament();
```

(Fix: use `tournamentController` instead of local `controller`):

```java
        tournamentController = new ServerTournamentController(event, this, gamesPerMatch);
        tournamentController.startTournament();
```

- [ ] **Step 3: Add scoped onMatchOver for tournament**

Add after the existing `onMatchOver(String matchId)` override (from Plan 1):

```java
    @Override
    protected void onMatchOver(final String matchId) {
        // Check if this is a tournament match
        if (tournamentController != null) {
            // Find the winner from the match
            HostedMatch match = getMatch(matchId);
            String winnerName = null;
            if (match != null && match.getMatch() != null) {
                var winner = match.getMatch().getWinner();
                if (winner != null) {
                    winnerName = winner.getPlayer().getName();
                }
            }
            // Clean up this match's resources
            super.onMatchOver(matchId);
            FServerManager.getInstance().clearPlayerGuis(matchId);
            // Notify tournament controller
            tournamentController.onMatchComplete(matchId, winnerName);
            // Only update lobby if tournament phase changed
            updateView(true);
        } else {
            // Non-tournament path (from Plan 1)
            super.onMatchOver(matchId);
            FServerManager.getInstance().clearPlayerGuis(matchId);
            if (!isMatchActive()) {
                FServerManager.getInstance().updateLobbyState();
            }
        }
    }
```

Note: This replaces the `onMatchOver(String matchId)` from Plan 1 Task 8. The super call goes to `GameLobby.onMatchOver(matchId)` which unregisters from the `MatchRegistry` and clears controllers if no matches remain.

- [ ] **Step 4: Handle player ready during STANDBY**

Add to `ServerGameLobby.java`:

```java
    /**
     * Called when a player toggles ready during tournament STANDBY.
     */
    public void onPlayerReadyTournament(int slotIndex) {
        if (tournamentController != null) {
            tournamentController.onPlayerReady(slotIndex);
        }
    }
```

- [ ] **Step 5: Handle clearCurrentEvent for tournament cleanup**

Update `clearCurrentEvent()` (around line 199) to also cancel tournament:

```java
    public void clearCurrentEvent() {
        if (tournamentController != null) {
            tournamentController.cancelTournament();
            tournamentController = null;
        }
        // ... existing clearCurrentEvent code ...
    }
```

- [ ] **Step 6: Verify compilation**

Run: `mvn compile -pl forge-gui -am -q`
Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add forge-gui/src/main/java/forge/gamemodes/net/server/ServerGameLobby.java
git commit -m "feat: integrate ServerTournamentController into ServerGameLobby"
```

---

## Task 7: Tournament WinLose Controller (Logic Only)

**Files:**
- Create: `forge-gui/src/main/java/forge/gamemodes/net/server/NetworkTournamentWinLose.java`

This is the server-side logic for what happens when a tournament match's game ends. It replaces the standard "Quit/Continue/Restart" flow with tournament-aware behavior.

- [ ] **Step 1: Write NetworkTournamentWinLose**

Create `forge-gui/src/main/java/forge/gamemodes/net/server/NetworkTournamentWinLose.java`:

```java
package forge.gamemodes.net.server;

import forge.game.GameView;
import forge.game.player.Player;
import forge.gamemodes.match.NextGameDecision;

/**
 * Handles WinLose decisions for network tournament matches.
 * Instead of the standard Continue/Restart/Quit flow:
 * - If match is still ongoing (best-of-N): auto-continue to next game
 * - If match is complete: report to ServerTournamentController
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
            // Match is complete — find winner and report to controller
            String winnerName = null;
            for (Player p : gameView.getPlayers()) {
                if (gameView.isMatchWonBy(p.getLobbyPlayer())) {
                    winnerName = p.getName();
                    break;
                }
            }
            controller.onMatchComplete(matchId, winnerName);
            return NextGameDecision.QUIT;
        }
        // Match not over yet — auto-continue to next game
        return NextGameDecision.CONTINUE;
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `mvn compile -pl forge-gui -am -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add forge-gui/src/main/java/forge/gamemodes/net/server/NetworkTournamentWinLose.java
git commit -m "feat: add NetworkTournamentWinLose for tournament match end-of-game handling"
```

---

## Task 8: Integration Tests

**Files:**
- Test: `forge-gui-desktop/src/test/java/forge/net/TournamentLogicTest.java`

- [ ] **Step 1: Add comprehensive tournament logic tests**

Add to `TournamentLogicTest.java`:

```java
    @Test
    public void testStandingsSortByScoreThenOMW() {
        // 4 players: Alice 3-0, Bob 2-1, Charlie 1-2, Diana 0-3
        TournamentPlayer tpAlice = new TournamentPlayer(GamePlayerUtil.createAiPlayer("Alice", 0), 0);
        TournamentPlayer tpBob = new TournamentPlayer(GamePlayerUtil.createAiPlayer("Bob", 0), 1);
        TournamentPlayer tpCharlie = new TournamentPlayer(GamePlayerUtil.createAiPlayer("Charlie", 0), 2);
        TournamentPlayer tpDiana = new TournamentPlayer(GamePlayerUtil.createAiPlayer("Diana", 0), 3);

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
        // Two players with 2-1 records — OMW breaks the tie
        TournamentPlayer tpA = new TournamentPlayer(GamePlayerUtil.createAiPlayer("A", 0), 0);
        TournamentPlayer tpB = new TournamentPlayer(GamePlayerUtil.createAiPlayer("B", 0), 1);
        TournamentPlayer tpC = new TournamentPlayer(GamePlayerUtil.createAiPlayer("C", 0), 2);
        TournamentPlayer tpD = new TournamentPlayer(GamePlayerUtil.createAiPlayer("D", 0), 3);

        List<TournamentPlayer> all = Arrays.asList(tpA, tpB, tpC, tpD);

        // A beat B, lost to C, beat D -> 2-1, opponents: B(1-2), C(3-0), D(0-3)
        tpA.addWin();  tpA.addOpponentIndex(1);
        tpA.addLoss(); tpA.addOpponentIndex(2);
        tpA.addWin();  tpA.addOpponentIndex(3);

        // B lost to A, beat C, beat D -> 2-1, opponents: A(2-1), C(1-2), D(0-3)
        tpB.addLoss(); tpB.addOpponentIndex(0);
        tpB.addWin();  tpB.addOpponentIndex(2);
        tpB.addWin();  tpB.addOpponentIndex(3);

        // C beat A, lost to B, lost to D -> 1-2
        tpC.addWin();  tpC.addOpponentIndex(0);
        tpC.addLoss(); tpC.addOpponentIndex(1);
        tpC.addLoss(); tpC.addOpponentIndex(3);

        // D lost to A, lost to B, beat C -> 1-2
        tpD.addLoss(); tpD.addOpponentIndex(0);
        tpD.addLoss(); tpD.addOpponentIndex(1);
        tpD.addWin();  tpD.addOpponentIndex(2);

        // A's OMW: B(1/3=0.333), C(1/3=0.333), D(1/3=0.333) = 0.333
        // B's OMW: A(2/3=0.667), C(1/3=0.333), D(1/3=0.333) = 0.444
        // B should rank higher than A due to better OMW

        double omwA = tpA.getOMW(all);
        double omwB = tpB.getOMW(all);
        Assert.assertTrue(omwB > omwA,
            "B should have better OMW than A. B=" + omwB + " A=" + omwA);

        List<TournamentPlayer> sorted = new ArrayList<>(all);
        sorted.sort((a, b) -> {
            int scoreCmp = Integer.compare(b.getScore(), a.getScore());
            if (scoreCmp != 0) return scoreCmp;
            return Double.compare(b.getOMW(all), a.getOMW(all));
        });

        Assert.assertEquals(sorted.get(0).getPlayer().getName(), "B", "B should be 1st (same score, better OMW)");
        Assert.assertEquals(sorted.get(1).getPlayer().getName(), "A", "A should be 2nd");
    }
```

- [ ] **Step 2: Run tests**

Run: `mvn test -pl forge-gui-desktop -am -Dtest=TournamentLogicTest -q`
Expected: PASS (7 tests)

- [ ] **Step 3: Commit**

```bash
git add forge-gui-desktop/src/test/java/forge/net/TournamentLogicTest.java
git commit -m "test: add standings sort and tiebreaker tests for tournament logic"
```

---

## Task 9: Full Regression

- [ ] **Step 1: Run all tournament logic tests**

Run: `mvn test -pl forge-gui-desktop -am -Dtest=TournamentLogicTest -q`
Expected: PASS

- [ ] **Step 2: Run existing network tests**

Run: `mvn test -pl forge-gui-desktop -am -Dtest=NetworkPlayIntegrationTest#testServerStartAndStop -q`
Expected: PASS

- [ ] **Step 3: Run multi-match tests from Plan 1**

Run: `mvn test -pl forge-gui-desktop -am -Dtest=MultiMatchTest -q`
Expected: PASS

- [ ] **Step 4: Commit any fixes**

```bash
git add -A
git commit -m "fix: regression fixes from tournament logic layer"
```

---

## Summary

This plan implements the server-side tournament orchestration:

1. **EventPhase** — new phases: `TOURNAMENT_IN_PROGRESS`, `STANDBY`, `TOURNAMENT_COMPLETE`
2. **EventParticipant** — tournament player and deck tracking
3. **TournamentPlayer** — OMW% tiebreaker calculation
4. **NetworkEvent** — tournament and gamesPerMatch fields
5. **ServerTournamentController** — full tournament lifecycle: pairings, parallel match creation, result recording, STANDBY with AFK timer, standings
6. **ServerGameLobby** — tournament integration, scoped onMatchOver for tournament matches
7. **NetworkTournamentWinLose** — auto-continue within match, report to controller on match complete
8. **Tests** — round-robin pairings, byes, OMW%, standings sort, tie scenarios

After this plan, the server can run a complete tournament headlessly. Plan 3 adds the network protocol and client UI to make it playable over the network.
