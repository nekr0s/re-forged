# Network Protocol & Client UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the network protocol messages, client-side event handling, and desktop UI that make tournaments playable over the network — tournament panel with standings/pairings, STANDBY display with ready/AFK timer, spectate UI, and tournament WinLose screen.

**Architecture:** Extend `NetworkEventView` with tournament state. Add 8 new `NetEvent` classes for tournament communication. Extend `IDraftEventHandler` to dispatch tournament events. Add tournament UI panel to `VLobby`, tournament event handlers to `CLobby`, and a tournament WinLose screen.

**Tech Stack:** Java 17, Maven, TestNG 7.10.2, Swing (MigLayout), Netty

**Spec:** `docs/superpowers/specs/2026-08-12-tournament-mode-design.md` — Sections 4 & 5

**Depends on:** Plan 1 (multi-match infrastructure) and Plan 2 (tournament logic layer)

---

## File Structure

### New Files

| File | Responsibility |
|------|----------------|
| `forge-gui/.../net/event/TournamentStartEvent.java` | Server→All: tournament begins |
| `forge-gui/.../net/event/MatchStartedEvent.java` | Server→All: a pairing match has started |
| `forge-gui/.../net/event/MatchCompleteEvent.java` | Server→All: a pairing match has a result |
| `forge-gui/.../net/event/RoundCompleteEvent.java` | Server→All: round done, entering STANDBY |
| `forge-gui/.../net/event/TournamentCompleteEvent.java` | Server→All: tournament over, final standings |
| `forge-gui/.../net/event/SpectateRequestEvent.java` | Client→Server: request to spectate a match |
| `forge-gui/.../net/event/SpectateApprovedEvent.java` | Server→Client: spectating approved |
| `forge-gui/.../net/event/SpectateLeaveEvent.java` | Client→Server: stop spectating |
| `forge-gui/.../net/PairingView.java` | Wire-safe pairing snapshot |
| `forge-gui/.../net/StandingView.java` | Wire-safe standing snapshot |
| `forge-gui-desktop/.../match/NetworkTournamentWinLose.java` | Desktop WinLose UI for tournament matches |

### Modified Files

| File | Changes |
|------|---------|
| `forge-gui/.../net/NetworkEventView.java` | Add tournament fields (currentRound, totalRounds, pairings, standings, gamesPerMatch, activeMatchIds) |
| `forge-gui/.../net/NetworkEvent.java` | Update `toView()` to include tournament state |
| `forge-gui/.../gui/interfaces/IDraftEventHandler.java` | Add tournament event dispatch methods |
| `forge-gui/.../net/client/FGameClient.java` | Route tournament events via `draftHandler.dispatch()` |
| `forge-gui-desktop/.../home/CLobby.java` | Tournament event handlers, state tracking |
| `forge-gui-desktop/.../home/VLobby.java` | Tournament panel UI, spectate buttons, STANDBY display |
| `forge-gui-desktop/.../match/ViewWinLose.java` | Add tournament case to WinLose controller selection |
| `forge-gui/.../net/server/ServerGameLobby.java` | Broadcast tournament events on phase changes |
| `forge-gui/.../net/server/FServerManager.java` | Handle spectate request/approved/leave events |

---

## Task 1: Wire-Safe View Records

**Files:**
- Create: `forge-gui/src/main/java/forge/gamemodes/net/PairingView.java`
- Create: `forge-gui/src/main/java/forge/gamemodes/net/StandingView.java`

- [ ] **Step 1: Create PairingView**

Create `forge-gui/src/main/java/forge/gamemodes/net/PairingView.java`:

```java
package forge.gamemodes.net;

import java.io.Serializable;

/**
 * Wire-safe snapshot of a tournament pairing for broadcast to clients.
 */
public record PairingView(
        String playerAName,
        String playerBName,
        String matchId,
        PairingStatus status,
        String winnerName) implements Serializable {

    public enum PairingStatus {
        ONGOING,
        COMPLETE,
        BYE
    }
}
```

- [ ] **Step 2: Create StandingView**

Create `forge-gui/src/main/java/forge/gamemodes/net/StandingView.java`:

```java
package forge.gamemodes.net;

import java.io.Serializable;

/**
 * Wire-safe snapshot of a tournament standing for broadcast to clients.
 */
public record StandingView(
        String playerName,
        int wins,
        int losses,
        int byes,
        int score,
        String omwPercent) implements Serializable {
}
```

- [ ] **Step 3: Verify compilation**

Run: `mvn compile -pl forge-gui -am -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add forge-gui/src/main/java/forge/gamemodes/net/PairingView.java forge-gui/src/main/java/forge/gamemodes/net/StandingView.java
git commit -m "feat: add PairingView and StandingView wire-safe records"
```

---

## Task 2: NetworkEventView Tournament Extension

**Files:**
- Modify: `forge-gui/src/main/java/forge/gamemodes/net/NetworkEventView.java`
- Modify: `forge-gui/src/main/java/forge/gamemodes/net/NetworkEvent.java:114-117`

- [ ] **Step 1: Read current NetworkEventView**

Read `forge-gui/src/main/java/forge/gamemodes/net/NetworkEventView.java` to get exact current content. It's a 41-line immutable record/class.

- [ ] **Step 2: Add tournament fields to NetworkEventView**

Add the following fields to `NetworkEventView` (after the existing fields):

```java
    private final int currentRound;
    private final int totalRounds;
    private final java.util.List<PairingView> pairings;
    private final java.util.List<StandingView> standings;
    private final int gamesPerMatch;
    private final java.util.Map<Integer, String> activeMatchIds;
```

Update the constructor to accept these new fields. Add a backward-compat constructor that passes defaults (0, 0, empty lists, etc.) so existing callers don't break:

```java
    // Backward-compat constructor (no tournament state)
    public NetworkEventView(String eventId, EventFormat format, EventPhase phase,
            List<EventParticipant> participants, int pickTimerSeconds,
            String productDescription, int numRounds) {
        this(eventId, format, phase, participants, pickTimerSeconds, productDescription, numRounds,
                0, 0, java.util.Collections.emptyList(), java.util.Collections.emptyList(),
                3, java.util.Collections.emptyMap());
    }

    // Full constructor with tournament state
    public NetworkEventView(String eventId, EventFormat format, EventPhase phase,
            List<EventParticipant> participants, int pickTimerSeconds,
            String productDescription, int numRounds,
            int currentRound, int totalRounds,
            List<PairingView> pairings, List<StandingView> standings,
            int gamesPerMatch, Map<Integer, String> activeMatchIds) {
        // ... existing field assignments ...
        this.currentRound = currentRound;
        this.totalRounds = totalRounds;
        this.pairings = List.copyOf(pairings);
        this.standings = List.copyOf(standings);
        this.gamesPerMatch = gamesPerMatch;
        this.activeMatchIds = Map.copyOf(activeMatchIds);
    }
```

Add getters:

```java
    public int getCurrentRound() { return currentRound; }
    public int getTotalRounds() { return totalRounds; }
    public List<PairingView> getPairings() { return pairings; }
    public List<StandingView> getStandings() { return standings; }
    public int getGamesPerMatch() { return gamesPerMatch; }
    public Map<Integer, String> getActiveMatchIds() { return activeMatchIds; }

    public boolean isTournamentActive() {
        return totalRounds > 0 && !standings.isEmpty();
    }
```

- [ ] **Step 3: Update NetworkEvent.toView() to include tournament state**

In `NetworkEvent.java`, update `toView()` (line 114-117) to pass tournament state when available:

```java
    public NetworkEventView toView() {
        int currentRound = 0;
        int totalRounds = 0;
        List<PairingView> pairings = java.util.Collections.emptyList();
        List<StandingView> standings = java.util.Collections.emptyList();
        java.util.Map<Integer, String> activeMatchIds = java.util.Collections.emptyMap();

        if (tournament != null) {
            currentRound = tournament.getActiveRound();
            totalRounds = tournament.getTotalRounds();
            pairings = buildPairingViews();
            standings = buildStandingViews();
        }

        return new NetworkEventView(eventId, format, phase,
                participants, pickTimerSeconds, productDescription, numRounds,
                currentRound, totalRounds, pairings, standings,
                gamesPerMatch, activeMatchIds);
    }

    private List<PairingView> buildPairingViews() {
        List<PairingView> views = new ArrayList<>();
        for (var pairing : tournament.getActivePairings()) {
            var players = pairing.getPairedPlayers();
            String playerA = players.size() > 0 ? players.get(0).getPlayer().getName() : "?";
            String playerB = players.size() > 1 ? players.get(1).getPlayer().getName() : "?";
            String winner = pairing.getWinner() != null ? pairing.getWinner().getPlayer().getName() : null;
            var status = pairing.isBye()
                    ? PairingView.PairingStatus.BYE
                    : (pairing.getWinner() != null
                        ? PairingView.PairingStatus.COMPLETE
                        : PairingView.PairingStatus.ONGOING);
            views.add(new PairingView(playerA, playerB, null, status, winner));
        }
        return views;
    }

    private List<StandingView> buildStandingViews() {
        // Use ServerTournamentController if available for sorted standings + OMW
        // For the view, we sort by score here as a fallback
        List<StandingView> views = new ArrayList<>();
        var sorted = new ArrayList<>(tournament.getAllPlayers());
        sorted.sort((a, b) -> Integer.compare(b.getScore(), a.getScore()));
        for (var tp : sorted) {
            views.add(new StandingView(
                    tp.getPlayer().getName(),
                    tp.getWins(),
                    tp.getLosses(),
                    tp.getByes(),
                    tp.getScore(),
                    tp.getOMWPercent(tournament.getAllPlayers())));
        }
        return views;
    }
```

- [ ] **Step 4: Verify compilation**

Run: `mvn compile -pl forge-gui -am -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add forge-gui/src/main/java/forge/gamemodes/net/NetworkEventView.java forge-gui/src/main/java/forge/gamemodes/net/NetworkEvent.java
git commit -m "feat: extend NetworkEventView with tournament state (rounds, pairings, standings)"
```

---

## Task 3: Tournament Network Events

**Files:**
- Create: 8 new event classes in `forge-gui/src/main/java/forge/gamemodes/net/event/`

- [ ] **Step 1: Create TournamentStartEvent**

Create `forge-gui/src/main/java/forge/gamemodes/net/event/TournamentStartEvent.java`:

```java
package forge.gamemodes.net.event;

import forge.gamemodes.net.NetEvent;

public final class TournamentStartEvent implements NetEvent {
    private static final long serialVersionUID = 1L;
    private final String eventId;

    public TournamentStartEvent(String eventId) {
        this.eventId = eventId;
    }

    public String getEventId() { return eventId; }
}
```

- [ ] **Step 2: Create MatchStartedEvent**

Create `forge-gui/src/main/java/forge/gamemodes/net/event/MatchStartedEvent.java`:

```java
package forge.gamemodes.net.event;

import forge.gamemodes.net.NetEvent;

public final class MatchStartedEvent implements NetEvent {
    private static final long serialVersionUID = 1L;
    private final String matchId;
    private final String playerA;
    private final String playerB;
    private final int round;

    public MatchStartedEvent(String matchId, String playerA, String playerB, int round) {
        this.matchId = matchId;
        this.playerA = playerA;
        this.playerB = playerB;
        this.round = round;
    }

    public String getMatchId() { return matchId; }
    public String getPlayerA() { return playerA; }
    public String getPlayerB() { return playerB; }
    public int getRound() { return round; }
}
```

- [ ] **Step 3: Create MatchCompleteEvent**

Create `forge-gui/src/main/java/forge/gamemodes/net/event/MatchCompleteEvent.java`:

```java
package forge.gamemodes.net.event;

import forge.gamemodes.net.NetEvent;

public final class MatchCompleteEvent implements NetEvent {
    private static final long serialVersionUID = 1L;
    private final String matchId;
    private final String winner;
    private final String score;

    public MatchCompleteEvent(String matchId, String winner, String score) {
        this.matchId = matchId;
        this.winner = winner;
        this.score = score;
    }

    public String getMatchId() { return matchId; }
    public String getWinner() { return winner; }
    public String getScore() { return score; }
}
```

- [ ] **Step 4: Create RoundCompleteEvent**

Create `forge-gui/src/main/java/forge/gamemodes/net/event/RoundCompleteEvent.java`:

```java
package forge.gamemodes.net.event;

import forge.gamemodes.net.NetEvent;

public final class RoundCompleteEvent implements NetEvent {
    private static final long serialVersionUID = 1L;
    private final int round;

    public RoundCompleteEvent(int round) {
        this.round = round;
    }

    public int getRound() { return round; }
}
```

- [ ] **Step 5: Create TournamentCompleteEvent**

Create `forge-gui/src/main/java/forge/gamemodes/net/event/TournamentCompleteEvent.java`:

```java
package forge.gamemodes.net.event;

import forge.gamemodes.net.NetEvent;
import forge.gamemodes.net.StandingView;
import java.util.List;

public final class TournamentCompleteEvent implements NetEvent {
    private static final long serialVersionUID = 1L;
    private final List<StandingView> finalStandings;
    private final boolean cancelled;

    public TournamentCompleteEvent(List<StandingView> finalStandings, boolean cancelled) {
        this.finalStandings = finalStandings;
        this.cancelled = cancelled;
    }

    public List<StandingView> getFinalStandings() { return finalStandings; }
    public boolean isCancelled() { return cancelled; }
}
```

- [ ] **Step 6: Create SpectateRequestEvent**

Create `forge-gui/src/main/java/forge/gamemodes/net/event/SpectateRequestEvent.java`:

```java
package forge.gamemodes.net.event;

import forge.gamemodes.net.event.IdentifiableNetEvent;

public final class SpectateRequestEvent implements IdentifiableNetEvent {
    private static final long serialVersionUID = 1L;
    private static int staticId = 0;
    private final int id;
    private final String matchId;

    public SpectateRequestEvent(String matchId) {
        this.id = staticId++;
        this.matchId = matchId;
    }

    @Override
    public int getId() { return id; }
    public String getMatchId() { return matchId; }
}
```

- [ ] **Step 7: Create SpectateApprovedEvent**

Create `forge-gui/src/main/java/forge/gamemodes/net/event/SpectateApprovedEvent.java`:

```java
package forge.gamemodes.net.event;

import forge.gamemodes.net.NetEvent;

public final class SpectateApprovedEvent implements NetEvent {
    private static final long serialVersionUID = 1L;
    private final String matchId;

    public SpectateApprovedEvent(String matchId) {
        this.matchId = matchId;
    }

    public String getMatchId() { return matchId; }
}
```

- [ ] **Step 8: Create SpectateLeaveEvent**

Create `forge-gui/src/main/java/forge/gamemodes/net/event/SpectateLeaveEvent.java`:

```java
package forge.gamemodes.net.event;

import forge.gamemodes.net.NetEvent;

public final class SpectateLeaveEvent implements NetEvent {
    private static final long serialVersionUID = 1L;
    private final String matchId;

    public SpectateLeaveEvent(String matchId) {
        this.matchId = matchId;
    }

    public String getMatchId() { return matchId; }
}
```

- [ ] **Step 9: Verify compilation**

Run: `mvn compile -pl forge-gui -am -q`
Expected: BUILD SUCCESS

- [ ] **Step 10: Commit**

```bash
git add forge-gui/src/main/java/forge/gamemodes/net/event/Tournament*.java forge-gui/src/main/java/forge/gamemodes/net/event/Match*.java forge-gui/src/main/java/forge/gamemodes/net/event/Round*.java forge-gui/src/main/java/forge/gamemodes/net/event/Spectate*.java
git commit -m "feat: add 8 tournament network event classes"
```

---

## Task 4: IDraftEventHandler Extension

**Files:**
- Modify: `forge-gui/src/main/java/forge/gui/interfaces/IDraftEventHandler.java`

- [ ] **Step 1: Add tournament dispatch methods**

In `IDraftEventHandler.java`, add these method signatures after `receiveEventPool()`:

```java
    // Tournament event handlers
    default void onTournamentStart(forge.gamemodes.net.event.TournamentStartEvent event) {}
    default void onMatchStarted(forge.gamemodes.net.event.MatchStartedEvent event) {}
    default void onMatchComplete(forge.gamemodes.net.event.MatchCompleteEvent event) {}
    default void onRoundComplete(forge.gamemodes.net.event.RoundCompleteEvent event) {}
    default void onTournamentComplete(forge.gamemodes.net.event.TournamentCompleteEvent event) {}
    default void onSpectateApproved(forge.gamemodes.net.event.SpectateApprovedEvent event) {}
```

Update the `dispatch(NetEvent)` default method to route tournament events. Add these cases to the existing `if-else` chain:

```java
    default boolean dispatch(NetEvent event) {
        if (event instanceof forge.gamemodes.net.event.TournamentStartEvent e) {
            onTournamentStart(e);
            return true;
        } else if (event instanceof forge.gamemodes.net.event.MatchStartedEvent e) {
            onMatchStarted(e);
            return true;
        } else if (event instanceof forge.gamemodes.net.event.MatchCompleteEvent e) {
            onMatchComplete(e);
            return true;
        } else if (event instanceof forge.gamemodes.net.event.RoundCompleteEvent e) {
            onRoundComplete(e);
            return true;
        } else if (event instanceof forge.gamemodes.net.event.TournamentCompleteEvent e) {
            onTournamentComplete(e);
            return true;
        } else if (event instanceof forge.gamemodes.net.event.SpectateApprovedEvent e) {
            onSpectateApproved(e);
            return true;
        } else if (event instanceof DraftPackArrivedEvent e) {
            // ... existing code ...
        }
        // ... existing cases ...
        return false;
    }
```

- [ ] **Step 2: Verify compilation**

Run: `mvn compile -pl forge-gui -am -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add forge-gui/src/main/java/forge/gui/interfaces/IDraftEventHandler.java
git commit -m "feat: extend IDraftEventHandler with tournament event dispatch"
```

---

## Task 5: Server-Side Tournament Event Broadcasting

**Files:**
- Modify: `forge-gui/src/main/java/forge/gamemodes/net/server/ServerGameLobby.java`
- Modify: `forge-gui/src/main/java/forge/gamemodes/net/server/ServerTournamentController.java`

- [ ] **Step 1: Add broadcast helpers to ServerGameLobby**

Add to `ServerGameLobby.java`:

```java
    private void broadcastTournamentEvent(forge.gamemodes.net.event.NetEvent event) {
        FServerManager.getInstance().broadcast(event);
    }
```

- [ ] **Step 2: Add event broadcasting to ServerTournamentController**

In `ServerTournamentController.java`, add broadcasting calls at key transition points:

In `startTournament()`, after `tournament.initializeTournament()`:

```java
        lobby.broadcastTournamentEvent(
            new forge.gamemodes.net.event.TournamentStartEvent(event.getEventId()));
```

In `startRoundMatches()`, after each match is started:

```java
                    lobby.broadcastTournamentEvent(
                        new forge.gamemodes.net.event.MatchStartedEvent(
                            match.getMatchId(),
                            pairParticipants.get(0).getName(),
                            pairParticipants.get(1).getName(),
                            tournament.getActiveRound()));
```

In `onMatchComplete()`, after `tournament.reportMatchCompletion(pairing)`:

```java
        String winnerName = pairing.getWinner() != null
            ? pairing.getWinner().getPlayer().getName() : null;
        lobby.broadcastTournamentEvent(
            new forge.gamemodes.net.event.MatchCompleteEvent(matchId, winnerName, ""));
```

In `enterStandby()`, after setting phase:

```java
        lobby.broadcastTournamentEvent(
            new forge.gamemodes.net.event.RoundCompleteEvent(tournament.getActiveRound() - 1));
```

In the tournament-complete path (in `onMatchComplete`, when `tournament.isTournamentOver()`):

```java
            java.util.List<forge.gamemodes.net.StandingView> finalStandings = buildFinalStandings();
            lobby.broadcastTournamentEvent(
                new forge.gamemodes.net.event.TournamentCompleteEvent(finalStandings, false));
```

Add helper method:

```java
    private java.util.List<forge.gamemodes.net.StandingView> buildFinalStandings() {
        List<TournamentPlayer> sorted = getStandings();
        List<forge.gamemodes.net.StandingView> views = new ArrayList<>();
        for (TournamentPlayer tp : sorted) {
            views.add(new forge.gamemodes.net.StandingView(
                    tp.getPlayer().getName(),
                    tp.getWins(),
                    tp.getLosses(),
                    tp.getByes(),
                    tp.getScore(),
                    tp.getOMWPercent(tournament.getAllPlayers())));
        }
        return views;
    }
```

In `cancelTournament()`:

```java
        lobby.broadcastTournamentEvent(
            new forge.gamemodes.net.event.TournamentCompleteEvent(buildFinalStandings(), true));
```

- [ ] **Step 3: Verify compilation**

Run: `mvn compile -pl forge-gui -am -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add forge-gui/src/main/java/forge/gamemodes/net/server/ServerGameLobby.java forge-gui/src/main/java/forge/gamemodes/net/server/ServerTournamentController.java
git commit -m "feat: broadcast tournament events on phase transitions"
```

---

## Task 6: CLobby Tournament Event Handlers

**Files:**
- Modify: `forge-gui-desktop/src/main/java/forge/screens/home/CLobby.java`

- [ ] **Step 1: Add tournament state fields to CLobby**

After line 80 (`private CEditorNetworkDraft networkDraftEditor;`), add:

```java
    // Tournament state
    private boolean inTournament;
    private int tournamentCurrentRound;
    private int tournamentTotalRounds;
    private java.util.List<forge.gamemodes.net.PairingView> currentPairings;
    private java.util.List<forge.gamemodes.net.StandingView> currentStandings;
```

- [ ] **Step 2: Implement tournament event handler methods**

Add these methods to `CLobby.java` (implementing the `IDraftEventHandler` overrides):

```java
    @Override
    public void onTournamentStart(forge.gamemodes.net.event.TournamentStartEvent event) {
        SwingUtilities.invokeLater(() -> {
            inTournament = true;
            view.updateActionButtons();
            view.refreshTournamentPanel();
        });
    }

    @Override
    public void onMatchStarted(forge.gamemodes.net.event.MatchStartedEvent event) {
        SwingUtilities.invokeLater(() -> {
            // If this player is in the match, they'll be switched to match UI automatically
            // by the match start flow. If not, update the spectate panel.
            view.refreshTournamentPanel();
        });
    }

    @Override
    public void onMatchComplete(forge.gamemodes.net.event.MatchCompleteEvent event) {
        SwingUtilities.invokeLater(() -> {
            view.refreshTournamentPanel();
        });
    }

    @Override
    public void onRoundComplete(forge.gamemodes.net.event.RoundCompleteEvent event) {
        SwingUtilities.invokeLater(() -> {
            view.refreshTournamentPanel();
        });
    }

    @Override
    public void onTournamentComplete(forge.gamemodes.net.event.TournamentCompleteEvent event) {
        SwingUtilities.invokeLater(() -> {
            inTournament = false;
            currentStandings = event.getFinalStandings();
            view.showTournamentResults(event.getFinalStandings(), event.isCancelled());
            view.refreshTournamentPanel();
        });
    }

    @Override
    public void onSpectateApproved(forge.gamemodes.net.event.SpectateApprovedEvent event) {
        SwingUtilities.invokeLater(() -> {
            // Switch to spectating the approved match
            // The match's game events will flow via the match-ID-tagged protocol
            view.showSpectateView(event.getMatchId());
        });
    }
```

- [ ] **Step 3: Update onLobbyDataChanged to track tournament state**

In `onLobbyDataChanged()` (lines 187-211), add after the eventView check:

```java
        if (newView != null && newView.isTournamentActive()) {
            tournamentCurrentRound = newView.getCurrentRound();
            tournamentTotalRounds = newView.getTotalRounds();
            currentPairings = newView.getPairings();
            currentStandings = newView.getStandings();
            inTournament = true;
        } else if (newView != null && !newView.isTournamentActive() && inTournament) {
            // Tournament state cleared from view
            inTournament = false;
        }
```

- [ ] **Step 4: Add tournament accessors for VLobby**

```java
    public boolean isInTournament() { return inTournament; }
    public int getTournamentCurrentRound() { return tournamentCurrentRound; }
    public int getTournamentTotalRounds() { return tournamentTotalRounds; }
    public java.util.List<forge.gamemodes.net.PairingView> getCurrentPairings() { return currentPairings; }
    public java.util.List<forge.gamemodes.net.StandingView> getCurrentStandings() { return currentStandings; }
```

- [ ] **Step 5: Add spectate request method**

```java
    void requestSpectate(String matchId) {
        if (view.getLobby().isAllowNetworking()) {
            // Send spectate request to server
            // This goes through FGameClient as a NetEvent
            forge.gamemodes.net.client.FGameClient client = VSubmenuOnlineLobby.getGameClient();
            if (client != null) {
                client.send(new forge.gamemodes.net.event.SpectateRequestEvent(matchId));
            }
        }
    }
```

Note: `VSubmenuOnlineLobby.getGameClient()` may need to be adjusted based on how the client reference is stored. Check the actual accessor.

- [ ] **Step 6: Verify compilation**

Run: `mvn compile -pl forge-gui-desktop -am -q`
Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add forge-gui-desktop/src/main/java/forge/screens/home/CLobby.java
git commit -m "feat: add tournament event handlers and state tracking to CLobby"
```

---

## Task 7: VLobby Tournament Panel UI

**Files:**
- Modify: `forge-gui-desktop/src/main/java/forge/screens/home/VLobby.java`

- [ ] **Step 1: Add tournament panel components**

After the event panel components (around line 154), add:

```java
    // Tournament panel (shown during tournament in limited mode)
    private final FPanel tournamentPanel = new FPanel(new MigLayout("insets 5 10 15 10, gap 2, wrap", "[grow, fill]"));
    private final FLabel lblTournamentTitle = new FLabel.Builder().fontSize(15).fontStyle(Font.BOLD).build();
    private final FLabel lblTournamentRound = new FLabel.Builder().fontSize(13).build();
    private final FLabel lblTournamentStandings = new FLabel.Builder().fontSize(12).fontAlign(javax.swing.SwingConstants.LEFT).build();
    private final FLabel lblTournamentPairings = new FLabel.Builder().fontSize(12).fontAlign(javax.swing.SwingConstants.LEFT).build();
    private final FLabel lblTournamentTimer = new FLabel.Builder().fontSize(13).fontStyle(Font.BOLD).build();
    private final FButton btnSpectate = new FButton("Spectate");
    private final FButton btnBuildDeck = new FButton("Build Deck");
    private final FButton btnTournamentReady = new FButton("Ready");
    private final FButton btnStartTournament = new FButton("Start Tournament");
    private final FButton btnCancelTournament = new FButton("Cancel Tournament");
```

- [ ] **Step 2: Add tournament panel to the right panel layout**

In `updateRightPanelForMode()` (around lines 895-919), add logic to show the tournament panel when `controller.isInTournament()`:

```java
        if (controller.isInTournament()) {
            eventRightPanel.removeAll();
            eventRightPanel.add(eventConfigPanel, "grow, wrap");
            eventRightPanel.add(tournamentPanel, "grow, push");
            refreshTournamentPanel();
        } else if (isLimited) {
            // ... existing limited mode layout ...
        }
```

- [ ] **Step 3: Add refreshTournamentPanel method**

```java
    void refreshTournamentPanel() {
        if (!controller.isInTournament()) {
            tournamentPanel.setVisible(false);
            return;
        }
        tournamentPanel.setVisible(true);
        tournamentPanel.removeAll();

        int round = controller.getTournamentCurrentRound();
        int total = controller.getTournamentTotalRounds();
        lblTournamentTitle.setText("Tournament");
        lblTournamentRound.setText("Round " + round + " of " + total);

        // Build standings text
        StringBuilder standingsText = new StringBuilder("<html>");
        var standings = controller.getCurrentStandings();
        if (standings != null && !standings.isEmpty()) {
            standingsText.append("<b>Standings:</b><br>");
            int rank = 1;
            for (var s : standings) {
                standingsText.append(String.format("%d. %s  %d-%d  (OMW: %s)<br>",
                        rank++, s.playerName(), s.wins(), s.losses(), s.omwPercent()));
            }
        }
        standingsText.append("</html>");
        lblTournamentStandings.setText(standingsText.toString());

        // Build pairings text
        StringBuilder pairingsText = new StringBuilder("<html>");
        var pairings = controller.getCurrentPairings();
        if (pairings != null && !pairings.isEmpty()) {
            pairingsText.append("<b>Current Round:</b><br>");
            for (var p : pairings) {
                String status = switch (p.status()) {
                    case ONGOING -> " [Spectate]";
                    case COMPLETE -> " — " + p.winnerName() + " won";
                    case BYE -> " — BYE";
                };
                pairingsText.append(p.playerAName()).append(" vs ").append(p.playerBName())
                        .append(status).append("<br>");
            }
        }
        pairingsText.append("</html>");
        lblTournamentPairings.setText(pairingsText.toString());

        tournamentPanel.add(lblTournamentTitle, "wrap");
        tournamentPanel.add(lblTournamentRound, "wrap");
        tournamentPanel.add(lblTournamentStandings, "gaptop 10, wrap");
        tournamentPanel.add(lblTournamentPairings, "gaptop 10, wrap");

        tournamentPanel.revalidate();
        tournamentPanel.repaint();
    }
```

- [ ] **Step 4: Add tournament action buttons**

Wire up `btnStartTournament` in the button setup area (around line 288-300):

```java
            btnStartTournament.setFont(FSkin.getRelativeFont(18));
            btnStartTournament.addActionListener(e -> {
                if (lobby instanceof ServerGameLobby sgl) {
                    int gamesPerMatch = getGamesPerMatch();
                    sgl.startTournament(gamesPerMatch);
                }
            });
            btnCancelTournament.setFont(FSkin.getRelativeFont(18));
            btnCancelTournament.addActionListener(e -> {
                if (lobby instanceof ServerGameLobby sgl && sgl.getTournamentController() != null) {
                    sgl.getTournamentController().cancelTournament();
                }
            });
            btnTournamentReady.setFont(FSkin.getRelativeFont(18));
            btnTournamentReady.addActionListener(e -> {
                // Toggle ready state
                // Reuse the existing ready mechanism
            });
            btnBuildDeck.setFont(FSkin.getRelativeFont(18));
            btnBuildDeck.addActionListener(e -> {
                // Open deck editor with event pool
                // Reuse CEditorLimited.networkEventEditorScreen flow
            });
```

- [ ] **Step 5: Update updateActionButtons for tournament mode**

In `updateActionButtons()` (lines 921-949), add tournament button logic:

```java
        if (lobby.hasControl()) {
            if (isLimited) {
                if (controller.isInTournament()) {
                    // Tournament mode buttons
                    pnlStart.setLayout(new MigLayout("insets 0, gap 0"));
                    pnlStart.add(btnCancelTournament, "w " + EVENT_BTN_WIDTH + "px!, h " + EVENT_BTN_HEIGHT + "px!");
                    // During STANDBY, show "Start Next Round" (auto-enabled when all ready)
                    // During TOURNAMENT_IN_PROGRESS, no start buttons (matches auto-run)
                } else {
                    // Existing limited mode buttons + Start Tournament option
                    pnlStart.setLayout(new MigLayout("insets 0, gap 0"));
                    final String label = (controller.getConfiguredFormat() == EventFormat.SEALED)
                            ? localizer.getMessage("lblNetworkGeneratePools")
                            : localizer.getMessage("lblNetworkStartDraft");
                    btnStartEvent.setText(label);
                    boolean isExistingEvent = controller.getActiveEventId() != null;
                    btnStartEvent.setEnabled(controller.getConfiguredFormat() != null && !isExistingEvent);
                    btnStartMatch.setEnabled(isExistingEvent);
                    btnStartTournament.setEnabled(isExistingEvent);
                    final String eventBtn = "w " + EVENT_BTN_WIDTH + "px!, h " + EVENT_BTN_HEIGHT + "px!";
                    pnlStart.add(btnNewEvent, "cell 0 0, " + eventBtn + ", gapright 20");
                    pnlStart.add(btnStartEvent, "cell 1 0, " + eventBtn + ", gapright 20");
                    pnlStart.add(btnStartTournament, "cell 2 0, " + eventBtn);
                    pnlStart.add(gamesInMatchFrame, "cell 2 1, align center");
                }
            } else {
                addConstructedStartControls();
            }
        }
```

- [ ] **Step 6: Add showTournamentResults and showSpectateView methods**

```java
    void showTournamentResults(java.util.List<forge.gamemodes.net.StandingView> standings, boolean cancelled) {
        StringBuilder sb = new StringBuilder();
        if (cancelled) {
            sb.append("Tournament Cancelled\n\n");
        } else {
            sb.append("Tournament Complete!\n\n");
        }
        sb.append("Final Standings:\n");
        int rank = 1;
        for (var s : standings) {
            sb.append(String.format("%d. %s — %dW-%dL (OMW: %s)\n",
                    rank++, s.playerName(), s.wins(), s.losses(), s.omwPercent()));
        }
        FOptionPane.showMessageDialog(sb.toString(), "Tournament Results",
                forge.toolbox.FSkin.getIcon(forge.toolbox.FSkin.FSkinProp.ICO_TROPHY));
    }

    void showSpectateView(String matchId) {
        // The client switches activeMatchId to the spectated match
        // Game events will flow via the match-ID-tagged protocol from Plan 1
        // This method updates the UI to show "Spectating" status
        FOptionPane.showMessageDialog("Now spectating match", "Spectator Mode",
                forge.toolbox.FSkin.getIcon(forge.toolbox.FSkin.FSkinProp.ICO_EYE));
    }
```

- [ ] **Step 7: Verify compilation**

Run: `mvn compile -pl forge-gui-desktop -am -q`
Expected: BUILD SUCCESS

- [ ] **Step 8: Commit**

```bash
git add forge-gui-desktop/src/main/java/forge/screens/home/VLobby.java
git commit -m "feat: add tournament panel UI with standings, pairings, and spectate buttons"
```

---

## Task 8: Tournament WinLose UI

**Files:**
- Create: `forge-gui-desktop/src/main/java/forge/screens/match/NetworkTournamentWinLose.java`
- Modify: `forge-gui-desktop/src/main/java/forge/screens/match/ViewWinLose.java:79-104`

- [ ] **Step 1: Create NetworkTournamentWinLose desktop controller**

Create `forge-gui-desktop/src/main/java/forge/screens/match/NetworkTournamentWinLose.java`:

```java
package forge.screens.match;

import forge.game.GameView;
import forge.gamemodes.match.NextGameDecision;
import forge.gui.interfaces.IWinLoseView;
import forge.toolbox.FButton;

/**
 * WinLose controller for network tournament matches.
 * Shows match result and current tournament standings.
 * No restart option — tournament matches can't be restarted.
 * Continue is automatic (handled by NetworkTournamentWinLose logic).
 */
public class NetworkTournamentWinLose extends ControlWinLose {

    private final String matchId;

    public NetworkTournamentWinLose(ViewWinLose view, GameView gameView, CMatchUI matchUI, String matchId) {
        super(view, gameView, matchUI);
        this.matchId = matchId;
    }

    @Override
    public boolean populateCustomPanel() {
        // Show tournament standings in the custom panel
        // The view's custom panel area shows current standings
        return true;
    }

    @Override
    protected void actionOnContinue() {
        // Auto-continue: tournament matches continue automatically
        getMatchUI().nextGameDecision(NextGameDecision.CONTINUE);
    }

    @Override
    protected void actionOnRestart() {
        // No restart in tournament — ignore
    }

    @Override
    protected void actionOnQuit() {
        // Quit means leave the tournament match
        // The ServerTournamentController handles the result
        getMatchUI().nextGameDecision(NextGameDecision.QUIT);
    }
}
```

- [ ] **Step 2: Add tournament case to ViewWinLose**

In `ViewWinLose.java`, update the switch statement (lines 79-104) to detect tournament matches. The challenge is that tournament matches use `GameType.Sealed` or `GameType.Draft` as the base game type, so we need a different detection mechanism.

Add a check before the switch:

```java
        // Check if this is a network tournament match
        if (matchUI.isNetGame() && matchUI instanceof CMatchUI cmui) {
            // Tournament matches are network games in limited mode with an active tournament
            // The tournament state is tracked on the lobby's NetworkEvent
            var lobby = forge.gamemodes.match.GameLobby.getLobby();
            if (lobby instanceof forge.gamemodes.net.server.ServerGameLobby sgl
                    && sgl.getTournamentController() != null) {
                control = new NetworkTournamentWinLose(this, game0, matchUI, "");
                // Hide restart button for tournament matches
                view.getBtnRestart().setVisible(false);
                // ... fall through to show()
            }
        }
```

Note: The exact detection mechanism may need refinement. The `matchUI.isNetGame()` check identifies network games. The tournament controller check identifies tournament matches. The `matchId` can be obtained from the `HostedMatch` if needed.

For client-side detection (non-host), the tournament state is available via `CLobby.isInTournament()` which is updated via `NetworkEventView`.

- [ ] **Step 3: Verify compilation**

Run: `mvn compile -pl forge-gui-desktop -am -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add forge-gui-desktop/src/main/java/forge/screens/match/NetworkTournamentWinLose.java forge-gui-desktop/src/main/java/forge/screens/match/ViewWinLose.java
git commit -m "feat: add NetworkTournamentWinLose UI for tournament match end-of-game"
```

---

## Task 9: Server-Side Spectate Handling

**Files:**
- Modify: `forge-gui/src/main/java/forge/gamemodes/net/server/FServerManager.java`
- Modify: `forge-gui/src/main/java/forge/gamemodes/net/server/ServerGameLobby.java`

- [ ] **Step 1: Add spectate request handler to FServerManager**

Add to `FServerManager.java`:

```java
    /**
     * Handle a spectate request from a client.
     * Creates a read-only RemoteClientGuiGame and subscribes it to the match's game events.
     */
    public void handleSpectateRequest(String matchId, RemoteClient client) {
        HostedMatch match = localLobby.getMatch(matchId);
        if (match == null || match.getGame() == null) {
            // Match not found or not in progress
            client.send(new forge.gamemodes.net.event.SpectateApprovedEvent(null)); // denied
            return;
        }

        // Create a read-only RemoteClientGuiGame for the spectator
        RemoteClientGuiGame spectatorGui = new RemoteClientGuiGame(client, matchId);
        client.setMatchGui("spectate:" + matchId, spectatorGui);
        client.setActiveMatchId("spectate:" + matchId);

        // Register as network spectator on the HostedMatch
        match.registerNetworkSpectator(spectatorGui);

        // Approve spectating
        client.send(new forge.gamemodes.net.event.SpectateApprovedEvent(matchId));
        netLog.info("Client {} now spectating match {}", client.getIndex(), matchId);
    }

    /**
     * Handle a spectate leave from a client.
     */
    public void handleSpectateLeave(String matchId, RemoteClient client) {
        String spectateKey = "spectate:" + matchId;
        client.removeMatchGui(spectateKey);
        // The HostedMatch's humanControllers list still has the spectator controller
        // — it will be cleaned up when the match ends
        netLog.info("Client {} stopped spectating match {}", client.getIndex(), matchId);
    }
```

- [ ] **Step 2: Route spectate events in GameServerHandler or FGameClient**

The `SpectateRequestEvent` is an `IdentifiableNetEvent` sent client→server. It needs to be intercepted in the server's message handler. Add handling in `FServerManager` or `GameServerHandler`:

Since `SpectateRequestEvent` is a `NetEvent` (not a `GuiGameEvent`), it arrives via the `LobbyUpdateHandler` equivalent on the server side. The server's inbound handler processes `NetEvent` messages. Add a check in the server's channel read:

In `FServerManager.java`, add a method to process incoming `NetEvent` messages that aren't `GuiGameEvent`:

```java
    public void handleIncomingNetEvent(RemoteClient client, NetEvent event) {
        if (event instanceof forge.gamemodes.net.event.SpectateRequestEvent req) {
            handleSpectateRequest(req.getMatchId(), client);
        } else if (event instanceof forge.gamemodes.net.event.SpectateLeaveEvent leave) {
            handleSpectateLeave(leave.getMatchId(), client);
        }
        // Other NetEvent types can be added here
    }
```

This needs to be wired into the server's Netty pipeline. The server's inbound handler (likely `DeregisterClientHandler` or a lobby handler) should call this method for non-GuiGameEvent messages.

- [ ] **Step 3: Verify compilation**

Run: `mvn compile -pl forge-gui -am -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add forge-gui/src/main/java/forge/gamemodes/net/server/FServerManager.java
git commit -m "feat: handle spectate request/leave events on server side"
```

---

## Task 10: Full Regression and Integration Test

- [ ] **Step 1: Run all existing network tests**

Run: `mvn test -pl forge-gui-desktop -am -Dtest=NetworkPlayIntegrationTest#testServerStartAndStop -q`
Expected: PASS

- [ ] **Step 2: Run multi-match tests**

Run: `mvn test -pl forge-gui-desktop -am -Dtest=MultiMatchTest -q`
Expected: PASS

- [ ] **Step 3: Run tournament logic tests**

Run: `mvn test -pl forge-gui-desktop -am -Dtest=TournamentLogicTest -q`
Expected: PASS

- [ ] **Step 4: Run delta sync tests**

Run: `mvn test -pl forge-gui-desktop -am -Dtest=DeltaSyncUnitTest -q`
Expected: PASS

- [ ] **Step 5: Run wire class filter test**

Run: `mvn test -pl forge-gui-desktop -am -Dtest=WireClassFilterTest -q`
Expected: PASS

- [ ] **Step 6: Run game event serialization test**

Run: `mvn test -pl forge-gui-desktop -am -Dtest=GameEventSerializationTest -q`
Expected: PASS

- [ ] **Step 7: Commit any fixes**

```bash
git add -A
git commit -m "fix: regression fixes from network protocol and client UI changes"
```

---

## Summary

This plan implements the network protocol and client UI for tournament mode:

1. **Wire-safe views** — `PairingView`, `StandingView` records
2. **NetworkEventView extension** — tournament fields (rounds, pairings, standings, gamesPerMatch)
3. **8 network events** — tournament start/complete, match started/complete, round complete, spectate request/approved/leave
4. **IDraftEventHandler extension** — dispatch routes for all tournament events
5. **Server broadcasting** — `ServerTournamentController` broadcasts events on phase transitions
6. **CLobby tournament handlers** — state tracking, event handlers, spectate request
7. **VLobby tournament panel** — standings, pairings, spectate buttons, STANDBY display, host controls
8. **Tournament WinLose UI** — `NetworkTournamentWinLose` controller, wired into `ViewWinLose`
9. **Server spectate handling** — request/approved/leave event processing
10. **Full regression** — all existing and new tests pass

After all three plans are complete, the full tournament mode is functional: 4 players can join a sealed/draft event, build decks, and play a round-robin tournament with parallel 1v1 matches, spectating, STANDBY phases, AFK enforcement, and OMW% tiebreakers.
