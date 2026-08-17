# Online Tournament — Current Implementation State & Known Gaps

> **Status: living document.** This supersedes the earlier tournament specs/plans as a
> description of what is actually implemented on `feature/online-tournament`. The older
> docs (`2026-08-12-tournament-mode-design.md`, `2026-08-15-tournament-round-state-design.md`,
> and the three `2026-08-12-*` plans) are historical: they describe the original intent,
> much of which was intentionally changed during manual bug-fixing. Treat **this** file as
> the source of truth for how the feature behaves today.

## Overview

Forge's online lobby now supports round-robin tournaments for sealed/draft events: 1v1
matches, parallel matches per round, between-round standby where the host starts the next
round once every human is ready, and final standings with OMW% tiebreakers.

The implementation was driven by the multi-match infrastructure (multiple `HostedMatch`
instances on one server, match-scoped routing/cleanup) plus a server-side tournament
controller that uses a **polling loop** to detect match completion (rather than an
event-driven `onMatchOver` hook). The controller is the single authority for tournament
state; clients are informed via dedicated tournament network events.

## What Is Built (as of 2026-08-16)

### 1. Multi-match infrastructure

- `HostedMatch` carries a `matchId` (UUID).
- `MatchRegistry` (`GameLobby.activeMatches`) replaces the single `hostedMatch` field;
  `GameLobby.startMatch(slotIndices, gameType, variants[, autoSpectate])` starts a match on
  a player *subset*.
- `RemoteClient` holds per-match `matchGuis`, per-match `ReplyPool`s, and per-match codec
  trackers; `GuiGameEvent` carries `matchId` so the Netty codec routes events to the right
  match's tracker.
- `FServerManager` has match-scoped `getController(index, matchId)`, `getGui(index, matchId)`,
  `clearPlayerGuis(matchId)`, and `armAfkTimeout(..., matchId)`. `GameServerHandler` routes
  by `matchId`.
- `ServerGameLobby.onMatchOver(matchId)` does scoped cleanup (only that match's players).

### 2. Tournament engine (server)

`ServerTournamentController` (`forge-gui/.../net/server/`) is created per event and owns a
`TournamentRoundRobin`. Key behaviors:

- **Start**: `startTournament()` sets `EventPhase.TOURNAMENT_IN_PROGRESS`, broadcasts
  `TournamentStartEvent(eventId, tournament)`, then immediately starts round 1 matches.
- **Round start**: each non-bye pairing becomes a 1v1 `HostedMatch` via
  `lobby.startMatch(slotIndices, gameType, EnumSet.noneOf(...), hasHuman)`. `hasHuman`
  controls the local auto-spectator (suppressed for all-AI pairings). Each player's built
  deck is copied onto the slot before the match.
- **Completion detection**: a 500 ms `scheduleAtFixedRate` poll (`checkCompletedMatches`)
  watches the tracked `HostedMatch`es. A finished match's winner is resolved by **name**
  matching (see Bug-Fix Log); the result is recorded and `MatchCompleteEvent` is broadcast.
- **Round end**: when no tracked matches remain the poll stops, `RoundCompleteEvent(round)`
  is broadcast, and the controller enters **between-round standby** — all human slots are
  marked not-ready, and the host starts the next round via the "Start Next Round" button
  (enabled only when `isAllHumanPlayersReady()`). All-AI tournaments auto-advance.
- **Byes**: odd player counts produce a "BYE" pairing handled inline (`handleBye`).
- **Finish**: `onTournamentComplete()` sets `TOURNAMENT_COMPLETE`, builds sorted standings
  (score, then OMW%), and broadcasts `TournamentCompleteEvent(finalStandings, cancelled=false)`.
- **Cancel**: `shutdown()` ends active matches and broadcasts
  `TournamentCompleteEvent(..., cancelled=true)`.

### 3. State model

- `EventPhase` (stable tournament lifecycle): `LOBBY_GATHER`, `DRAFTING`,
  `POOL_DISTRIBUTION`, `TOURNAMENT_IN_PROGRESS`, `TOURNAMENT_COMPLETE`. `ROUND_IN_PROGRESS`
  was removed.
- `RoundState` (`NONE`/`ACTIVE`/`COMPLETE`) tracks the current round, but it is **not**
  carried on the wire via `NetworkEventView`. `NetworkEventView` no longer contains any
  tournament fields (rounds/pairings/standings/roundState were removed again on 2026-08-16
  because reusing the lobby-update channel produced inconsistent screens). Round state is
  instead derived client-side from the tournament events (see next section).

### 4. Wire messages

- `TournamentStartEvent(eventId, TournamentRoundRobin)` — server → all. **Carries the whole
  mutable engine object** (see Gap 1).
- `MatchStartedEvent(matchId, playerA, playerB, round)` — server → all.
- `MatchCompleteEvent(matchId, winner, score)` — server → all.
- `RoundCompleteEvent(round)` — server → all (round = the round that just finished, captured
  as `activeRound` at the top of the poll cycle).
- `TournamentCompleteEvent(finalStandings, cancelled)` — server → all.
- `SpectateRequestEvent(matchId)` (client → server), `SpectateApprovedEvent(matchId)`
  (server → client), `SpectateLeaveEvent(matchId)` (client → server).
- `PairingView` / `StandingView` are wire-safe records used inside the events (e.g. final
  standings).

Host receives its own broadcasts via `FServerManager.dispatchToLocalListener` (host does not
loop back through the network channel).

### 5. Client (desktop)

- `CLobby` implements `IDraftEventHandler` + `ITournamentEventHandler`; `dispatch` tries
  draft first, then tournament. It keeps the `TournamentRoundRobin` received in
  `TournamentStartEvent` and a `currentRoundState` flipped by the events
  (`ACTIVE` on `MatchStarted`, `COMPLETE` on `RoundComplete`, cleared on
  start/complete/cancel).
- `VLobby.refreshTournamentPanel()` renders the round title, standings, and pairings by
  reading the client-held `TournamentRoundRobin` and converting it with `buildPairingViews` /
  `buildStandingViews`. Host controls: "Start Tournament", "Start Next Round" (gated on all
  humans ready), "Cancel Tournament". Players ready during standby via the normal per-player
  ready checkbox.
- `ViewWinLose` routes any network `Sealed`/`Draft` game to a desktop
  `NetworkTournamentWinLose` (no restart; Continue/Quit).

### 6. Bots

- Sealed bots get an auto-built deck (`SealedDeckBuilder`) stored on
  `EventParticipant.deck`.
- All-AI pairings suppress the local auto-spectator (`autoSpectate=false`).

## Known Gaps & Remediation Guidance

### Gap 1 — Remote clients render a frozen tournament snapshot (the root of the "inconsistent screens" bug)

`TournamentStartEvent` serializes the entire `TournamentRoundRobin`. On the **host** this is
the *same live object* (in-process broadcast), so the panel stays accurate. On **remote
clients** it is a one-time snapshot: `getActiveRound()` stays 1, pairings never advance past
round 1, standings stay 0–0, and the round-complete text reads "Round 0 complete". The
`MatchStarted/MatchComplete/RoundComplete` events only flip `RoundState`; they carry no data
to rebuild pairings or standings.

**Guidance:** stop shipping the engine object. Make the server the single source of truth and
broadcast a wire-safe snapshot on every state change — either a new
`TournamentUpdateEvent(round, totalRounds, roundState, List<PairingView>, List<StandingView>)`
sent from every transition (`startRoundMatches`, `checkCompletedMatches`,
`enterBetweenRoundStandby`, `onTournamentComplete`), or equivalently by enriching the
existing match/round events with the full current standings+pairings. Clients then **replace
their snapshot wholesale** and never mutate or re-derive tournament state. This removes the
host/remote divergence by construction and lets you delete the client-side
`buildPairingViews`/`buildStandingViews` re-derivation.

Implementation notes:
- Give `PairingView` a real `matchId` (the client currently passes `null`) so spectate
  (Gap 5) becomes possible.
- Prefer the controller's OMW-aware sort for standings in the snapshot (it already exists in
  `buildFinalStandings`; today the per-round standings path is score-only).
- `TournamentStartEvent` can shrink to `(eventId, playerNames, totalRounds)`; `RoundState`
  can be folded into the update event and removed from `CLobby` state.

### Gap 2 — `gamesPerMatch` is dead

`ServerGameLobby.startTournament(int gamesPerMatch)` receives the host's 1/3/5 selection
from the `gamesInMatch` combo but only logs it. `ServerTournamentController` has no such
field, and `HostedMatch` defaults matches to the global `UI_MATCHES_PER_GAME` preference.
Best-of-N is therefore not tournament-scoped.

**Guidance:** pass `gamesPerMatch` into `ServerTournamentController`, and after each match is
created set `match.getMatch().getRules().setGamesPerMatch(gamesPerMatch)` (the approach the
original plan used). Consider passing it through `startMatch`/`HostedMatch` instead so it
lives next to the other rule defaults.

### Gap 3 — Draft tournaments run as `GameType.Constructed`

`startMatchForPairing` maps `SEALED → GameType.Sealed` and **draft → `GameType.Constructed`**.
The established network limited convention (`GameLobby.startGame`) uses `GameType.Draft` for
all limited events. Consequences: draft tournament matches get constructed-format deck
semantics, and `ViewWinLose`'s tournament detection (`isNetGame() && (Sealed || Draft)`)
does **not** match them, so draft tournament matches fall through to the default
`ControlWinLose` (restart enabled, wrong buttons).

**Guidance:** use `GameType.Draft` for draft events, and make WinLose selection (and any
other tournament detection) not depend on `GameType` alone — carry an explicit
"this is a tournament match" signal (see Gap 4).

### Gap 4 — `ViewWinLose` hijacks every network limited game

`ViewWinLose` selects `NetworkTournamentWinLose` for **any** `isNetGame()` match whose
`GameType` is `Sealed` or `Draft` — including ordinary non-tournament network sealed/draft
events, which previously got `LimitedWinLose`. This is a regression of the backward-compat
requirement.

**Guidance:** gate on actual tournament membership (e.g., resolve the match's controller /
`HostedMatch` and check it is tracked by a `ServerTournamentController`, or thread a boolean
flag through the match startup). The detection must be the same server+client.

### Gap 5 — Spectating is server-complete but client-stubbed

The server half exists and works: `SpectateRequestEvent` → `handleSpectateRequest` creates a
read-only `RemoteClientGuiGame`, `HostedMatch.registerNetworkSpectator` wires a
`WatchRemoteGame` + `GameEventForwarder`, and `SpectateApprovedEvent` is returned. The client
half does not: pairings render `"[Spectate]"` as inert text, `PairingView.matchId` is `null`
so there is no id to request, `requestSpectate` has no caller, and `onSpectateApproved` only
pops an info dialog — it never switches `activeMatchId`, opens the spectator match view, or
subscribes to the match's event stream.

**Guidance:** this is the largest remaining feature. Sequence:
1. Land Gap 1 so `PairingView.matchId` is real and the panel can render a per-pairing
   "Spectate" button for ongoing matches.
2. On `SpectateApprovedEvent`, set the client's active match to the spectated match and open
   the game screen for that `RemoteClientGuiGame` (the existing multi-match GUI map supports
   this); suppression of input is already server-side via `WatchRemoteGame`.
3. Return to the tournament panel when the spectated match ends or the player's next round
   starts. Define the "leave" UX (`SpectateLeaveEvent` already exists).
4. The original spec's between-round standby with ready/AFK countdown (see Gap 7) can wait —
   spectate only applies during `ROUND`/`ACTIVE` play.

### Gap 6 — Dead code & unfulfilled "auto-continue"

- The server-side `NetworkTournamentWinLose` (`forge-gui/.../net/server/`) — `determineNextAction()`
  — is never called. Best-of-N games do **not** auto-continue: each player clicks
  "Continue"/"Next Game" on the desktop `NetworkTournamentWinLose`.
- `ServerGameLobby.onPlayerReadyTournament` has no callers (the controller's `onPlayerReady`
  is already a documented no-op).

**Guidance:** either wire the server-side auto-continue (server advances the match between
games so the WinLose screen never blocks on `Continue`), or delete the dead class and update
the docs/comments to state that advancing between games is manual. Don't leave both "auto"
claims and manual behavior.

### Gap 7 — No standby AFK timer (deliberate simplification, now undocumented)

The original design's between-round AFK countdown (auto-loss for not-ready, bye for
opponent, void when both AWOL) was replaced by host-controlled round starts. Consequences:
- A disconnected player during standby simply never readies; the host can force-start, but
  there is no automatic penalty or "drop player + bye" path.
- There is no deck-editing step between rounds (no "Build Deck" → `CEditorLimited`), so
  decks are fixed after pool building.

**Guidance:** decide and document this intentionally. If host-controlled standby stays,
consider at least a host-visible indicator of who is missing and a "drop player (award bye)"
action. Re-adding the AFK timer is a reasonable follow-up but not required for the feature to
function with co-operative hosts.

### Gap 8 — Winner determination silently awards wins

`determineWinner` falls back to `pairedPlayers.get(0)` whenever the match winner is `null`
(e.g., a draw in the final game, or a name mismatch) or the match object is gone. If both
players in a pairing disconnect, the first player still gets the match win. Draws cannot be
represented in the flow at all (`TournamentPlayer.addTie` exists but nothing calls it).

**Guidance:** decide the match-level draw policy (best-of-N final game drawn → count as a
tie) and add a no-show/void path (both players AWOL → void the pairing, no points) instead of
silently awarding to player A. At minimum log loudly on the fallback so it is never invisible.

### Gap 9 — No end-to-end test for the tournament flow

Coverage today: unit tests for round-robin pairings, byes, OMW%, standings sort, and the
winner-name-matching bug (`TournamentLogicTest`), plus multi-match primitives
(`MatchRegistryTest`, `HostedMatchIdTest`, `MultiMatchTest`, `WatchRemoteGameTest`). There is
no test that boots a server, runs a tournament round, and asserts the broadcasts and
standings. The manual bug-by-bug workflow has been productive, but the feature now has enough
moving parts that a headless integration test would pay off.

**Guidance:** extend the existing test harness (`UnifiedNetworkHarness` / `HeadlessNetworkClient`)
with a scenario: 4 players → tournament starts → round 1 matches run → results recorded →
standby → next round → final standings broadcast. Assert the emitted events and their
payloads. This will also pin down Gap 1 (remote-client snapshot) once the snapshot event
exists.

### Gap 10 — Misc robustness

- `findNewMatch()` identifies a just-started match by scanning the registry for anything not
  yet tracked; `startMatch` should return the created `HostedMatch` (or its id) directly.
- `RoundCompleteEvent(round)`'s value depends on the poll capturing `activeRound` before any
  completion is processed. It is correct today, but the round number should come from the
  snapshot (Gap 1) rather than being captured implicitly.
- Standings are sorted by score only in the completion chat message while
  `buildFinalStandings()` uses OMW — unify on the OMW sort everywhere.
- `TournamentStartEvent` also leaks server-only objects (`LobbyPlayer`/`LobbyPlayerAi`) onto
  the wire; fixed by Gap 1.

## Bug-Fix Log (manual fixes that shaped the current design)

- **Winner by name, not `equals()`.** Tournament players used `LobbyPlayerAi`/`LobbyPlayerHuman`
  while match winners are fresh `LobbyPlayer` instances; `equals()` failed across classes.
  Fixed by name matching, with regression tests
  (`testWinnerMatchingByNameNotByEquals`, `testWinnerMatchingAcrossLobbyPlayerTypes`).
- **All-AI pairings spawned spurious auto-spectators; bots had no decks.** Fixed by passing
  `hasHuman` → `autoSpectate` and auto-building sealed decks for AI participants.
- **Round state conflated with event phase.** `ROUND_IN_PROGRESS` was removed from
  `EventPhase`; a separate `RoundState` tracks the current round, with the convention that
  the displayed round is `activeRound - 1` during the `COMPLETE` window.
- **Host-controlled next round.** `onPlayerReady` became a no-op; the host explicitly starts
  the next round, gated on all humans ready.
- **Inconsistent screens.** Tournament state was removed from `NetworkEventView`/`LobbyUpdateEvent`
  (it caused divergent host/client panels) and replaced by dedicated tournament events plus
  an `ITournamentEventHandler`; `NetConnectUtil` now registers the draft/tournament handler
  via `addNetEventHandler`. *Note: Gap 1 is the remaining half of this fix — the client still
  derives its panel from the one-shot `TournamentStartEvent` snapshot.*

## Files of Interest

| File | Role |
|------|------|
| `forge-gui/.../net/server/ServerTournamentController.java` | Tournament orchestration (poll-based) |
| `forge-gui/.../net/server/ServerGameLobby.java` | `startTournament`, standby gates, `onMatchOver(matchId)` |
| `forge-gui/.../net/server/FServerManager.java` | broadcast to host+clients, spectate request/leave handling |
| `forge-gui/.../net/match/GameLobby.java` | `MatchRegistry`, `startMatch(subset, ..., autoSpectate)` |
| `forge-gui/.../net/event/*` | Tournament event classes |
| `forge-gui/.../net/{PairingView,StandingView,RoundState,EventPhase}.java` | Wire records / enums |
| `forge-gui-desktop/.../home/{CLobby,VLobby}.java` | Client state + tournament panel |
| `forge-gui-desktop/.../match/{ViewWinLose,NetworkTournamentWinLose}.java` | WinLose integration |
| `forge-gui-desktop/src/test/java/forge/net/TournamentLogicTest.java` | Engine/unit coverage |

## Recommended Next Steps (priority order)

1. **Gap 1** — server-authoritative `TournamentUpdateEvent` snapshot; delete client-side
   re-derivation and the engine object on the wire. This resolves the recurring
   "inconsistent screens" class of bug at the root.
2. **Gap 2 + Gap 3 + Gap 4** — wire `gamesPerMatch`, fix draft game type, and gate WinLose
   on real tournament membership. Small, high-value correctness fixes.
3. **Gap 5** — complete the client side of spectating (depends on Gap 1's real `matchId`).
4. **Gap 8** — draw/void policy for winner determination.
5. **Gap 9** — headless end-to-end tournament test.
6. **Gap 6/Gap 7** — clean up dead code, document the manual-continue and host-controlled
   standby choices.