# Tournament Mode for Online Sealed/Draft Events

## Overview

Add a Tournament Mode to Forge's online lobby system that transforms sealed/draft events from a single multiplayer free-for-all into a structured round-robin tournament with 1v1 matches, parallel match execution, spectating, standings with OMW% tiebreakers, and a standby phase between rounds.

## Motivation

Currently, when 4 players complete a sealed/draft event in the online lobby, they play a single 4-player simultaneous free-for-all match. Real prerelease/sealed events use a tournament structure where players play 1v1 matches against each opponent, and the player with the most wins is the champion. This design brings that experience to Forge's online mode.

The design also addresses a deeper architectural limitation: the server currently supports only one concurrent match. By enabling multiple parallel matches, we unlock both tournament play and spectating of ongoing matches.

## Requirements

- **1v1 matches** (2 players per game), replacing the current multiplayer free-for-all for tournament events
- **Round-robin pairing** — everyone plays everyone (4 players = 3 rounds, 6 total pairings, 2 matches per round played in parallel)
- **Host-configurable games per match** — 1, 3, or 5 (best-of-N)
- **Works with both sealed and draft events** — tournament logic is format-agnostic
- **Parallel matches** — two 1v1 matches run simultaneously per round (for 4 players)
- **Spectating** — players who finish their match early can spectate ongoing matches over the network
- **OMW% tiebreaker** — opponent match win percentage used to break ties in standings
- **Between-round standby within TOURNAMENT_IN_PROGRESS** — players can edit decks or take a break, mark themselves ready, with AFK timer enforcement
- **Desktop only** for initial version; mobile gets a "not supported" message

## Architecture

### Section 1: Multi-Match Architecture in FServerManager

#### Problem

`FServerManager` is a singleton with one `ServerGameLobby`, one `HostedMatch` (`GameLobby.hostedMatch`), and all routing goes through a single slot-index lookup. Running two concurrent matches with different player subsets is impossible.

#### Changes

**Match Registry:**

Add a match registry to `FServerManager`:

```
FServerManager
  + localLobby: ServerGameLobby              (unchanged)
  + activeMatches: Map<String, HostedMatch>  (NEW — replaces single hostedMatch)
  + getHostedMatch(matchId): HostedMatch
```

Each `HostedMatch` gets a `matchId` (UUID). `GameLobby.hostedMatch` becomes `GameLobby.activeMatches` (a map). `startGame()` is refactored to `startMatch(playerSlots, gameType, ...)` which creates a `HostedMatch`, registers it in the map, and returns the `matchId`.

**Scoped Cleanup:**

- `clearPlayerGuis(matchId)` — clears only the `RemoteClientGuiGame` instances for clients in that specific match, not all clients
- `onMatchOver(matchId)` — removes the match from the registry, clears only that match's controllers/GUIs, marks only the players in that match as not-ready. In tournament mode, auto-advance logic fires here.
- The lobby stays alive throughout — players return to lobby view between rounds

**Controller Routing:**

`FServerManager.getController(client, matchId)` replaces the current `getController(slotIndex)`. The routing chain becomes:

```
GameServerHandler -> client.getMatchGui(matchId) -> controller
```

instead of the current `-> localLobby.getController(slotIndex)`.

**AFK Timeout Scoping:**

`armAfkTimeout()` currently checks `localLobby.getHostedMatch()`. Refactored to accept a `matchId` parameter and look up the correct match from the registry.

#### Key Files Affected

- `forge-gui/src/main/java/forge/gamemodes/net/server/FServerManager.java` — match registry, scoped cleanup, scoped routing
- `forge-gui/src/main/java/forge/gamemodes/match/GameLobby.java` — `activeMatches` map, `startMatch()` refactor, `onMatchOver(matchId)`
- `forge-gui/src/main/java/forge/gamemodes/match/HostedMatch.java` — `matchId` field
- `forge-gui/src/main/java/forge/gamemodes/net/server/GameServerHandler.java` — match-scoped controller lookup

---

### Section 2: Client-Side Multi-Match & Spectating Support

#### Problem

Each `RemoteClient` has one `RemoteClientGuiGame`, one `GameView`, one codec tracker, and one `ReplyPool`. Two matches sharing a channel would interleave events and corrupt state.

#### Changes

**Per-Client Match GUI Map:**

```
RemoteClient
  + matchGuis: Map<String, RemoteClientGuiGame>  (NEW — replaces single gui field)
  + activeMatchId: String                         (which match the client is currently viewing)
  + getGui(matchId): RemoteClientGuiGame
```

Each `RemoteClientGuiGame` keeps its own `GameView`, `DeltaSyncManager`, and `GameEventForwarder` — they're already instance-scoped. The client tracks which one is "active" for display.

**Codec Tracker per Match (Match-ID-Prefixed Protocol):**

Every `GuiGameEvent` and game event sync message gets a `matchId` header in the event envelope. The codec decoder on the client side routes to the correct `RemoteClientGuiGame`'s tracker. The `GameProtocolSender` is updated to include `matchId` in outbound messages.

This requires changes to the encoder/decoder but keeps a single Netty channel per client.

**ReplyPool Scoping:**

Each `RemoteClientGuiGame` gets its own `ReplyPool` (instead of sharing the per-client pool). Each match's `sendAndWait` calls use the per-match pool, eliminating ID collision.

**Network Spectating:**

New spectator flow:
1. Non-playing client sends `SpectateRequestEvent(matchId)` to server
2. Server's `FServerManager` creates a read-only `RemoteClientGuiGame` for the spectator and subscribes its `GameEventForwarder` to that match's `Game` event bus
3. Server sends `SpectateApprovedEvent(matchId)` to the spectator
4. Game events flow to the spectator via the same `GameEventForwarder` mechanism, tagged with `matchId`
5. The spectator's `RemoteClientGuiGame` uses a `WatchRemoteGame` controller (network equivalent of `WatchLocalGame`) that no-ops all inputs
6. Spectator can leave at any time via `SpectateLeaveEvent(matchId)`

`HostedMatch.registerSpectator()` (already exists for local spectators at `HostedMatch.java:348-357`) is extended to accept network spectators.

**Client UI — Match View Switching:**

When a player finishes their match early and another match is ongoing, the client UI offers "Spectate [Player A] vs [Player B]". The client switches `activeMatchId` to the spectated match, the `RemoteClientGuiGame` for that match becomes the displayed view, and input is suppressed. When the spectated match ends or the player's next round starts, the client switches back.

#### Key Files Affected

- `forge-gui/src/main/java/forge/gamemodes/net/server/RemoteClient.java` — `matchGuis` map, per-match codec trackers, per-match ReplyPools
- `forge-gui/src/main/java/forge/gamemodes/net/server/RemoteClientGuiGame.java` — multiple instances per client, `WatchRemoteGame` spectator mode
- `forge-gui/src/main/java/forge/gamemodes/match/HostedMatch.java` — `registerSpectator()` extended for network spectators
- `forge-gui/src/main/java/forge/gamemodes/net/server/GameProtocolSender.java` — `matchId` in outbound messages
- `forge-gui/src/main/java/forge/gamemodes/net/protocol/ProtoDecoder.java` and `ProtoEncoder.java` (or equivalent codec classes) — `matchId` routing in event envelopes

---

### Section 3: Tournament Logic Layer

#### Problem

The network event system (`NetworkEvent`, `ServerGameLobby`) has no concept of pairings, rounds, or standings. We need a tournament controller that orchestrates the multi-match flow using Forge's existing `TournamentRoundRobin` engine.

#### Changes

**NetworkEvent Extension:**

```
NetworkEvent
  + tournament: TournamentRoundRobin          (NEW — null for non-tournament events)
  + gamesPerMatch: int                         (NEW — host-configurable: 1, 3, or 5)
```

`EventPhase` gains new values. Phase transitions:

```
LOBBY_GATHER -> POOL_DISTRIBUTION -> TOURNAMENT_IN_PROGRESS
  -> (ROUND_IN_PROGRESS -> TOURNAMENT_IN_PROGRESS)* -> TOURNAMENT_COMPLETE
```

Draft events: `LOBBY_GATHER -> DRAFTING -> POOL_DISTRIBUTION -> TOURNAMENT_IN_PROGRESS -> ...`

`TOURNAMENT_IN_PROGRESS` serves double duty: it's both the initial entry state and the between-rounds standby state (where players edit decks, mark ready, and AFK timer applies). When a round's matches start, the phase moves to `ROUND_IN_PROGRESS`. When all matches in a round complete, the phase returns to `TOURNAMENT_IN_PROGRESS` for the next standby period. After the final round, the phase moves to `TOURNAMENT_COMPLETE`.

**EventPhase final values:**

```
LOBBY_GATHER, DRAFTING, POOL_DISTRIBUTION, TOURNAMENT_IN_PROGRESS, ROUND_IN_PROGRESS, TOURNAMENT_COMPLETE
```

**EventParticipant Extension:**

```
EventParticipant
  + tournamentPlayer: TournamentPlayer    (NEW — wraps wins/losses/byes/score)
  + deck: Deck                            (NEW — the player's built event deck)
```

When pools are distributed and players build decks, each player's built deck is registered on their `EventParticipant`.

**ServerTournamentController (new class in `forge-gui/.../net/server/`):**

```
ServerTournamentController
  + event: NetworkEvent
  + tournament: TournamentRoundRobin
  + startTournament()     — initializes round-robin, generates first round pairings, enters TOURNAMENT_IN_PROGRESS (standby)
  + startNextRound()      — moves to ROUND_IN_PROGRESS, starts all pairings for the current round (parallel matches)
  + onMatchComplete(matchId, outcome)  — records result, checks if round complete
  + onAllMatchesComplete() — transitions back to TOURNAMENT_IN_PROGRESS (standby), starts AFK timer
  + onPlayerReady(slotIndex) — marks player ready, checks if all ready
  + onAfkTimerExpired() — applies losses to not-ready players, byes to their opponents, starts next round
  + isRoundComplete()     — all pairings in current round have results
  + isTournamentComplete() — all rounds done
  + getStandings()        — sorted list with OMW% tiebreakers
  + getActivePairings()   — current round's pairings for UI display
```

**Tournament Flow:**

1. After all players have built decks and are ready, host clicks "Start Tournament"
2. `startTournament()` creates `TournamentRoundRobin` from participants, sets phase to `TOURNAMENT_IN_PROGRESS` (initial standby), generates round 1 pairings
3. For each pairing, creates a 1v1 `HostedMatch` via the new multi-match `startMatch()` (Section 1), moves to `ROUND_IN_PROGRESS`
4. For 4 players: 2 matches start in parallel (Players A vs B, Players C vs D)
5. As each match completes, `onMatchComplete()` records the winner via `tournament.reportMatchCompletion()`
6. When both matches complete -> `onAllMatchesComplete()` -> transitions back to `TOURNAMENT_IN_PROGRESS` (standby)
7. Players edit decks, mark ready, AFK timer enforces progress
8. When all ready (or AFK penalties applied) -> `startNextRound()` moves to `ROUND_IN_PROGRESS`, generates next pairings (A vs C, B vs D)
9. After 3 rounds (6 total pairings), `isTournamentComplete()` -> `TOURNAMENT_COMPLETE`, standings displayed, champion declared

**OMW% Tiebreaker:**

Added to `TournamentPlayer`:

```
getOMW() — opponent match win rate:
  for each opponent played: opponentWins / opponentTotalMatches
  average across all opponents
```

Final standings sort: primary by score (wins*3 + ties), secondary by OMW%.

**Deck Handling Between Matches:**

Each player builds their deck once from their sealed/draft pool during the `POOL_DISTRIBUTION` -> deck building phase. The full pool is retained as the deck's sideboard throughout the tournament.

- Between games within a match (best-of-3/5): Sideboarding allowed (existing `GameType.Sealed`/`Draft` already supports `canSideboard=true`)
- Between matches (rounds): Players can freely edit their deck — swap cards between main deck and sideboard using their full card pool. This applies to all formats including best-of-1
- During `TOURNAMENT_IN_PROGRESS` (standby), players open `CEditorLimited` with their full pool to edit. The deck is re-registered on their `EventParticipant` before the next round starts
- The same built deck is reused for all matches in the tournament (standard for sealed/draft tournaments)

**Between-Round Standby (within TOURNAMENT_IN_PROGRESS):**

After a round completes, the tournament returns to `TOURNAMENT_IN_PROGRESS` (acting as standby):

- All players return to the lobby and are marked not-ready
- Players can edit their deck (open `CEditorLimited` with full pool) or take a break
- Each player clicks "Ready" when prepared for the next round
- AFK timer applies: a configurable countdown starts (host sets duration, e.g., 5 minutes). When it expires:
  - Any not-ready player receives an automatic loss for the next round
  - Their scheduled opponent receives a bye (counts as a win)
  - If both players in a pairing are AFK, both receive losses and the pairing is void
- When all players are ready (or AFK penalties applied), the next round auto-starts (moves to `ROUND_IN_PROGRESS`)
- Host can extend/reset the AFK timer or force-start the next round

The AFK timer reuses the existing `armAfkTimeout()` infrastructure from `FServerManager`, scoped to the tournament's `TOURNAMENT_IN_PROGRESS` standby phase.

**Tournament WinLose Controller:**

Instead of the standard "Quit/Continue/Restart" WinLose screen, tournament matches show a TournamentWinLose screen:

- Shows match result (win/loss, game-by-game score)
- Shows current tournament standings
- If your match finished early and other matches are ongoing: "Spectate" button
- If round is complete: transitions back to TOURNAMENT_IN_PROGRESS standby (host auto-starts next round when all ready)
- If tournament is complete: final standings + "Return to Lobby"

#### Key Files Affected

- `forge-gui/src/main/java/forge/gamemodes/net/NetworkEvent.java` — tournament field, gamesPerMatch
- `forge-gui/src/main/java/forge/gamemodes/net/EventPhase.java` — new phase values
- `forge-gui/src/main/java/forge/gamemodes/net/EventParticipant.java` — tournamentPlayer, deck
- `forge-gui/src/main/java/forge/gamemodes/net/server/ServerTournamentController.java` — NEW
- `forge-gui/src/main/java/forge/gamemodes/tournament/system/TournamentRoundRobin.java` — reused as-is
- `forge-gui/src/main/java/forge/gamemodes/tournament/system/TournamentPlayer.java` — OMW% added
- `forge-gui/src/main/java/forge/gamemodes/match/input/InputPassPriority.java` — AFK timeout matchId scoping

---

### Section 4: Network Protocol & Client UI

#### Problem

Clients currently receive a single `NetworkEventView` snapshot and have no concept of tournament state, match progress, or spectating. We need new protocol messages and UI to keep all clients informed.

#### Changes

**NetworkEventView Extension:**

```
NetworkEventView
  (existing phase field carries TOURNAMENT_IN_PROGRESS / ROUND_IN_PROGRESS / TOURNAMENT_COMPLETE)
  + currentRound: int                   (NEW)
  + totalRounds: int                    (NEW)
  + pairings: List<PairingView>         (NEW — current round's pairings)
  + standings: List<StandingView>       (NEW — sorted standings with OMW%)
  + gamesPerMatch: int                  (NEW)
  + activeMatchIds: Map<playerSlot, matchId>  (NEW — which match each player is in)
```

New lightweight view records (Serializable):
- `PairingView(playerAName, playerBName, matchId, status: ONGOING/COMPLETE/A_BYE, winnerName)`
- `StandingView(playerName, wins, losses, byes, score, omwPercent)`

These are broadcast via the existing `LobbyUpdateEvent` mechanism — no new channel needed.

**New Network Events:**

| Event | Direction | Purpose |
|-------|-----------|---------|
| `TournamentStartEvent(eventId)` | Server -> All | Signals tournament beginning |
| `MatchStartedEvent(matchId, playerA, playerB, round)` | Server -> All | Notifies all clients of a new pairing starting |
| `MatchCompleteEvent(matchId, winner, score)` | Server -> All | Notifies all clients of a match result |
| `RoundCompleteEvent(round)` | Server -> All | All matches in round finished, back to TOURNAMENT_IN_PROGRESS (standby) |
| `TournamentCompleteEvent(standings)` | Server -> All | Tournament over, final standings |
| `SpectateRequestEvent(matchId)` | Client -> Server | Player requests to spectate an ongoing match |
| `SpectateApprovedEvent(matchId)` | Server -> Client | Spectating granted, game events will follow |
| `SpectateLeaveEvent(matchId)` | Client -> Server | Player stops spectating |

**Client-Side Tournament Display:**

New UI panel in the lobby (replaces/augments the event panel during tournament).

ROUND_IN_PROGRESS state (matches active):
```
+---------------------------------------------+
|  Tournament - Round 2 of 3                  |
|                                             |
|  Standings:                                 |
|    1. Alice    2-0  (OMW: 67%)              |
|    2. Bob      1-1  (OMW: 50%)              |
|    3. Charlie  1-1  (OMW: 50%)              |
|    4. Diana    0-2  (OMW: 33%)              |
|                                             |
|  Current Round:                             |
|    Alice vs Charlie  [Spectate]             |
|    Bob vs Diana      [Spectate]             |
|                                             |
|  Your match: In progress / Waiting          |
+---------------------------------------------+
```

TOURNAMENT_IN_PROGRESS (standby between rounds):
```
+---------------------------------------------+
|  Tournament - Round 2 Complete              |
|  Next round starts when all ready           |
|  AFK Timer: 4:32 remaining                  |
|                                             |
|  Standings:                                 |
|    1. Alice    2-0  (OMW: 67%)              |
|    2. Bob      1-1  (OMW: 50%)              |
|    3. Charlie  1-1  (OMW: 50%)              |
|    4. Diana    0-2  (OMW: 33%)              |
|                                             |
|  Ready Status:                              |
|    Alice    [Ready]                         |
|    Bob      [Not Ready]                     |
|    Charlie  [Ready]                         |
|    Diana    [Not Ready]                     |
|                                             |
|  [Build Deck]  [Ready]                      |
+---------------------------------------------+
```

COMPLETE state:
- Final standings, champion highlighted
- "Return to Lobby" button

States:
- ROUND_IN_PROGRESS: Shows standings, current pairings with spectate buttons. If your match is ongoing, you're in the match UI. If you finished early, spectate buttons are enabled for ongoing matches.
- TOURNAMENT_IN_PROGRESS (standby): Shows standings from completed round, "Build Deck" button opens `CEditorLimited` with full pool, "Ready" button to mark ready. AFK timer visible to all. When all ready, next round auto-starts (moves to ROUND_IN_PROGRESS).
- TOURNAMENT_COMPLETE: Final standings, champion highlighted, "Return to Lobby" button.

**Host Controls:**

- "Start Tournament" button (replaces "Start Match" when tournament mode is configured)
- During TOURNAMENT_IN_PROGRESS (standby): sees ready indicators, can extend/reset AFK timer, can force-start the next round (applying losses/byes to not-ready players)
- Between rounds: auto-starts next round when all players ready
- Can cancel tournament at any time (returns to normal lobby)

**CLobby Integration:**

`CLobby` gains:
- `onTournamentUpdate(view)` — handles `NetworkEventView` changes with tournament fields, refreshes the tournament panel
- `onMatchStarted(event)` — if the player is in the match, switches to match UI; if not, updates the spectate panel
- `onMatchComplete(event)` — updates standings, shows result notification
- `onRoundComplete(event)` — transitions to between-round standby display
- `onTournamentComplete(event)` — shows final standings
- `onSpectateApproved(event)` — switches `activeMatchId` to spectated match, opens spectator view
- Tournament state tracked via `lastTournamentView` on `CLobby`

**IDraftEventHandler Extension:**

The existing `IDraftEventHandler` interface is extended with tournament event dispatch:

```
IDraftEventHandler
  + onTournamentStart(event)       (NEW)
  + onMatchStarted(event)          (NEW)
  + onMatchComplete(event)         (NEW)
  + onRoundComplete(event)         (NEW)
  + onTournamentComplete(event)    (NEW)
  + onSpectateApproved(event)      (NEW)
  + dispatch(NetEvent)  — extended to route tournament events
```

This keeps the existing event dispatch architecture — tournament events flow through the same `FGameClient` -> `IDraftEventHandler.dispatch()` path.

#### Key Files Affected

- `forge-gui/src/main/java/forge/gamemodes/net/NetworkEventView.java` — tournament fields
- `forge-gui/src/main/java/forge/gamemodes/net/event/` — 8 new event classes
- `forge-gui/src/main/java/forge/gui/interfaces/IDraftEventHandler.java` — tournament dispatch methods
- `forge-gui-desktop/src/main/java/forge/screens/home/CLobby.java` — tournament event handlers, UI state
- `forge-gui-desktop/src/main/java/forge/screens/home/VLobby.java` — tournament panel UI

---

### Section 5: Error Handling, Edge Cases & Testing

#### Player Disconnection During a Match

- When a player disconnects mid-match, only their `HostedMatch` is affected
- The `disconnectGraceSeconds` timer (already on `NetworkEvent`) applies to that specific match
- If grace period expires: the disconnected player's slot is converted to AI (existing `convertToAI()` logic, scoped to `matchId`), match continues
- If all human players in a match disconnect: match is voided, no result recorded, pairing is re-scheduled in the next round (or opponent gets a bye if the player can't reconnect)
- Other matches in the round are unaffected

#### Player Disconnection During Between-Round Standby

- Player is marked not-ready (can't click ready if disconnected)
- AFK timer applies normally — if it expires, they get a loss and their opponent a bye
- If they reconnect before the next round starts, they can ready up (if timer hasn't expired)

#### Host Disconnection

- If the host's client disconnects but the server process stays alive (headless host scenario): tournament continues, host's slot treated like any other player disconnect
- If the server process dies: tournament is over. Clients detect connection loss and return to main menu. Tournament state is lost (in-memory only)

#### Tournament Cancellation

Host can cancel the tournament at any point:
- All active matches are terminated (no results recorded for incomplete matches)
- `TournamentCompleteEvent` is broadcast with partial standings and a "cancelled" flag
- Clients return to normal lobby mode
- The `NetworkEvent` is cleared

#### Bye Handling

In round-robin with 4 players, byes shouldn't occur (even player count). But edge cases:
- A player disconnects permanently and is removed -> 3 players, odd count -> one bye per round
- The `TournamentRoundRobin` engine already handles byes (adds a "BYE" player when odd count)
- Bye = automatic win, no match created, `ServerTournamentController` records it immediately via `reportMatchCompletion()`

#### Persistence

For the initial version: no persistence. Tournament state lives in memory on `NetworkEvent.tournament`. If the server crashes or host quits, the tournament is lost. This matches the current behavior of network events (no persistence of event state).

Future enhancement: serialize `TournamentRoundRobin` state (it's already XStream-serializable via `TournamentIO`) and allow resuming.

#### Backward Compatibility

Non-tournament sealed/draft events continue to work exactly as before:
- `NetworkEvent.tournament` is null -> standard single-match flow
- `EventPhase.TOURNAMENT_IN_PROGRESS` / `ROUND_IN_PROGRESS` / `TOURNAMENT_COMPLETE` never appear
- The multi-match registry in `FServerManager` works fine with a single match (just a map with one entry)
- Old clients connecting to a server running a tournament would need the updated client (protocol changes require both sides updated)

#### Testing Strategy

**Unit tests:**
- `TournamentRoundRobin` with 4 players — verify 3 rounds, 6 pairings, correct pairings per round
- OMW% calculation — construct scenarios with tied records, verify tiebreaker ordering
- AFK penalty logic — verify losses/byes applied correctly when timer expires
- Bye handling — 3-player scenario with odd count

**Integration tests (headless):**
- Full tournament simulation: 4 AI players, best-of-1, verify standings and champion
- Disconnection mid-match -> AI conversion -> match completes -> tournament continues
- TOURNAMENT_IN_PROGRESS (standby) -> AFK timer expiry -> next round starts with penalties

**Manual testing:**
- 4-player network tournament with real clients
- Spectating flow between matches
- Deck editing during between-round standby
- Host cancellation mid-tournament

#### Scope Boundaries (Not Building)

- No Swiss or bracket pairing styles (round-robin only, extensible later)
- No tournament persistence/resume across server restarts
- No prizes or rewards (just standings)
- No AI players in network tournaments (human only — AI is only used as disconnect fallback)
- No mobile UI changes in the initial version (desktop only, mobile gets a "not supported" message for tournament mode)

## Implementation Order (High-Level)

1. **Multi-match infrastructure** (Section 1) — FServerManager match registry, scoped cleanup/routing, GameLobby refactor
2. **Client-side multi-match support** (Section 2) — per-client match GUI map, match-ID protocol, ReplyPool scoping
3. **Network spectating** (Section 2) — WatchRemoteGame, spectator event flow
4. **Tournament logic layer** (Section 3) — ServerTournamentController, NetworkEvent extension, between-round standby/AFK handling
5. **Network protocol & events** (Section 4) — new events, NetworkEventView extension, IDraftEventHandler extension
6. **Client UI** (Section 4) — tournament panel, between-round standby display, spectate UI, host controls
7. **Tournament WinLose controller** (Section 3) — replaces standard WinLose for tournament matches
8. **Testing** (Section 5) — unit, integration, manual

Each phase should be independently testable before moving to the next.
