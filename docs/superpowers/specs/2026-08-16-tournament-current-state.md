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

### Gap 1 — Remote clients never receive the tournament state (confirmed root cause of "panel missing on clients")

> **Status: RESOLVED 2026-08-19.** `TournamentStartEvent` no longer ships the engine object.
> The controller now broadcasts a wire-safe `TournamentUpdateEvent(eventId, round,
> totalRounds, RoundState, List<PairingView>, List<StandingView>)` from every transition
> (round start, match complete, standby, next round, finish/cancel) and clients replace
> their snapshot wholesale. `TournamentStartEvent` shrank to `(eventId, playerNames,
> totalRounds)`. The "make the engine classes `Serializable`" whack-a-mole was reverted.
> History (why this mattered):

Consequences:
- Remote clients never receive `onTournamentStart` → `CLobby.tournament` stays `null` →
  `isInTournament()` false → `updateRightPanelForMode()` never adds `tournamentPanel` → **no
  panel on clients**, while the host's panel works. (Observed symptom: "Tournament Panel does
  not visualize for clients of the lobby".)
- `MatchStartedEvent` / `RoundCompleteEvent` / `TournamentCompleteEvent` *do* serialize (plain
  fields), so clients still get pulled into matches and see the final-results dialog — only
  the in-progress panel is absent.
- Even if the classes were made serializable, the client would hold a **one-time frozen
  snapshot** (pairings never advance past round 1, standings stay 0–0) because nothing pushes
  updates — the "stale" failure mode behind the "dropped" one.

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

> **Status: RESOLVED 2026-09-01.** `ServerTournamentController` now takes
> `gamesPerMatch` and applies it via
> `match.getMatch().getRules().setGamesPerMatch(gamesPerMatch)` in
> `startMatchForPairing`, so best-of-N is tournament-scoped instead of following
> the global `UI_MATCHES_PER_GAME` preference.

`ServerGameLobby.startTournament(int gamesPerMatch)` receives the host's 1/3/5 selection
from the `gamesInMatch` combo but only logs it. `ServerTournamentController` has no such
field, and `HostedMatch` defaults matches to the global `UI_MATCHES_PER_GAME` preference.
Best-of-N is therefore not tournament-scoped.

**Guidance:** pass `gamesPerMatch` into `ServerTournamentController`, and after each match is
created set `match.getMatch().getRules().setGamesPerMatch(gamesPerMatch)` (the approach the
original plan used). Consider passing it through `startMatch`/`HostedMatch` instead so it
lives next to the other rule defaults.

### Gap 3 — Draft tournaments run as `GameType.Constructed`

> **Status: RESOLVED 2026-09-01.** The controller maps event format via a new
> `ServerTournamentController.gameTypeFor(EventFormat)`:
> `SEALED → GameType.Sealed`, `BOOSTER_DRAFT → GameType.Draft`, else `Constructed`,
> matching `GameLobby.startGame`'s limited convention. Covered by
> `ServerTournamentControllerTest`.

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

> **Status: RESOLVED 2026-09-01.** Tournament membership is now an explicit signal,
> not a `GameType` guess. `HostedMatch.setTournamentMatch(true)` marks the match's
> GUIs server-side (the controller calls it in `startMatchForPairing`); the shared
> client GUI is armed from `MatchStartedEvent` in `CLobby.onMatchStarted` and reset
> on every `openView` (`GameClientHandler`) so ordinary network limited games fall
> through to `LimitedWinLose` again. `ViewWinLose` gates on
> `matchUI.isNetGame() && matchUI.isTournamentMatch()`.

`ViewWinLose` selects `NetworkTournamentWinLose` for **any** `isNetGame()` match whose
`GameType` is `Sealed` or `Draft` — including ordinary non-tournament network sealed/draft
events, which previously got `LimitedWinLose`. This is a regression of the backward-compat
requirement.

**Guidance:** gate on actual tournament membership (e.g., resolve the match's controller /
`HostedMatch` and check it is tracked by a `ServerTournamentController`, or thread a boolean
flag through the match startup). The detection must be the same server+client.

### Gap 5 — Spectating is server-complete but client-stubbed

> **Scope decisions (2026-09-01, for the future client-side phase):**
> 1. **Lobby-spectating only** — one active game view per client at a time
>    (spectate during standby / after finishing / as a non-participant). Concurrent
>    spectating while also playing your own match would require multi-tracker wire
>    codecs on both ends (high effort) and is out of scope.
> 2. **Hidden hands, client-side** — spectators see only public zones (battlefield,
>    graveyard, stack, life totals), rendered via a spectator flag on the GUI rather
>    than server-side per-viewer filtering in `DeltaSyncManager`. (Today
>    `AbstractGuiGame.mayView` returns `true` for a GUI with no local players, so an
>    unmodified spectator would see both hands — a cheating vector.)

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

> **Status: RESOLVED 2026-09-01.** `TournamentPairing` now carries a `MatchResult`
> (`PENDING/WIN/DRAW/VOID`): a match that ends with no winner (`getWinner() == null`)
> becomes a **DRAW** (both players get a tie), a missing match or an unmatched
> winner name becomes a **VOID** (no points, no opponents recorded) instead of
> silently handing the match to player A. `determineWinner` logs loudly on every
> non-WIN outcome. The tie/void scoring lives in
> `TournamentRoundRobin.reportMatchCompletion`; the outcome mapping is the pure,
> unit-tested `ServerTournamentController.resolveMatchOutcome`. Pairings render as
> "Draw"/"Void" in the tournament panel (`PairingView.PairingStatus`).

`determineWinner` falls back to `pairedPlayers.get(0)` whenever the match winner is `null`
(e.g., a draw in the final game, or a name mismatch) or the match object is gone. If both
players in a pairing disconnect, the first player still gets the match win. Draws cannot be
represented in the flow at all (`TournamentPlayer.addTie` exists but nothing calls it).

**Guidance:** decide the match-level draw policy (best-of-N final game drawn → count as a
tie) and add a no-show/void path (both players AWOL → void the pairing, no points) instead of
silently awarding to player A. At minimum log loudly on the fallback so it is never invisible.

### Gap 9 — No end-to-end test for the tournament flow

> **Status: RESOLVED 2026-09-01.** `TournamentEndToEndTest` boots a real server +
> `ServerGameLobby`, sets up a 4-AI-participant sealed event with fast minimal
> decks, starts a best-of-1 round-robin tournament, lets it run to completion
> (all-AI auto-advances between rounds), and asserts: 3 rounds played by every
> player, the full broadcast chain (`TournamentStart/Update`, `MatchStarted/
> Complete`, `RoundComplete`, `TournamentComplete`), non-empty pairings/standings
> snapshots, and score-sorted final standings.

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

### Gap 11 — Server-initiated ready writes bypass the notification funnel (confirmed root cause of the ready-checkbox bug)

> **Status: RESOLVED (verified 2026-09-01).** The funnel fix landed in code before
> this document was updated: `enterBetweenRoundStandby` routes through
> `lobby.setPlayerReady(...)` → `applyToSlot` (the single authorized writer),
> `startMatchForPairing` no longer pre-sets ready, and
> `ServerGameLobby.onMatchOver(matchId)` skips the reset during tournaments (the
> controller owns the standby reset). No silent `slot.setIsReady(...)` writes
> remain in the tournament path.

The ready flag lives once on the server (`LobbySlot.isReady`) and is displayed on every
screen. All **player-initiated** changes flow through a single funnel
(`applyToSlot` → `updateView` → refresh the host's own screen **and** broadcast
`LobbyUpdateEvent` to remote clients). The tournament controller instead writes the flag
**directly** — `slot.setIsReady(true)` in `startMatchForPairing`, `slot.setIsReady(false)` in
`enterBetweenRoundStandby`, and again in `ServerGameLobby.onMatchOver(matchId)` — and only
emits the network half (`server.updateLobbyState()`), never the host's in-process refresh.

Result (observed symptom: "ready button stays checked between rounds, we must uncheck it"):
- The server's truth is correct (not-ready), but the host's screen was last rendered *before*
  the reset — when `startMatchForPairing` marked everyone ready — so the host's lobby shows
  everyone checked and the "Start Next Round" button disabled.
- Remote clients *do* get the broadcast and show unchecked, so the same slot disagrees across
  screens (host checked, client unchecked).
- Manually unchecking/re-checking routes through the normal funnel and finally re-syncs the
  host's screen — the "we need to uncheck it" workaround.

**Guidance:** route every server-initiated ready change through the same slot-update path the
player path uses so the funnel's host-refresh half always fires; and stop pre-setting ready as
a match-starting mechanism — the flag should only mean "ready in standby" (see the appendix
trail below).

### Gap 12 — Tournament start lacks the legacy ready/deck/legality gate and failure feedback

> **Status: RESOLVED 2026-09-01.** `startTournament` now collects every problem —
> per-human ready/deck/empty-main via the pure `ServerGameLobby.startProblemFor`,
> plus a Limited-format legality check for all participants via
> `ServerGameLobby.legalityProblemFor` when `ENFORCE_DECK_LEGALITY` is on (reusing
> the legacy `GameLobby.legalityProblemEntry` / `confirmIgnoreDeckLegality`, now
> `protected`). On failure it broadcasts a chat `MessageEvent` (no more silent
> no-op button) and shows the host the Ignore/Cancel legality dialog. Both helpers
> are unit-tested in `ServerGameLobbyStartGateTest`.

The legacy "Start Match" path (`GameLobby.startGame`) validates before starting and tells the
host *why* it refuses: per-slot "Player X is not ready" and "Please specify player deck" dialogs,
plus a deck-legality gate (`DeckFormat.Limited.getDeckConformanceProblem` per slot, collected
into a list and shown in an Ignore/Cancel dialog — Cancel aborts the start). The tournament path
lost most of this:

- `ServerGameLobby.startTournament` checks ready + deck for humans, but on failure only does
  `netLog.warn(...)` and returns — **the host's "Start Tournament" click silently does nothing**
  if someone isn't ready or has no deck.
- The **deck-legality check is commented out** in `startTournament` (lines ~405–414) — scaffolded
  but never finished.
- `ServerTournamentController.startMatchForPairing` does no ready/deck/legality validation per
  round; it only skips pairings with fewer than 2 slots (log-only).

**Guidance:**
- Rebuild the start gate in `ServerGameLobby.startTournament`: for every human participant,
  collect ready problems and deck problems, plus `DeckFormat.Limited.getDeckConformanceProblem(slot.getDeck())`
  for all participants when `ENFORCE_DECK_LEGALITY` is on. Present the collected problems to the
  host with an Ignore/Cancel affordance; only proceed if accepted. Because decks are frozen for
  the whole tournament (no between-round editing today), **one check at tournament start covers
  every round** — re-check per round only if Gap 7's between-round deck editing is added later.
- On failure, surface feedback: the legacy-style host dialog **and/or** a broadcast chat
  `MessageEvent` so every player in the lobby knows why the tournament didn't start. A silent
  no-op button is the worst failure mode.
- Reuse the legacy dialog helpers: `confirmIgnoreDeckLegality` / `legalityProblemEntry` are
  currently `private` in `GameLobby` — make them `protected` so `ServerGameLobby` can reuse them
  and the two flows stay identical. If they can't be reused, replicate the small dialog verbatim.
- **Round-level nicety (optional):** "Start Next Round" is already gated on
  `areAllHumansReadyForTournament`, but the *disabled-with-no-explanation* button is confusing.
  Add a tooltip ("Waiting for all players to ready") or a chat message when the host attempts to
  start while not everyone is ready.
- Skip the legacy min-players/teams and variant (schemes/planes/vanguard) checks — not applicable
  to sealed/draft tournaments.
- Since `startTournament`/`startMatchForPairing` are already being touched (Gap 2/3, Gap 11),
  fold this gate into `startTournament` as the single validation point before the controller is
  created.

## Appendix A — The Ready-Button Flow Trail (how to trace Symptom 1)

### The working funnel (player clicks "Ready")

```
PlayerPanel.chkReady ──► VLobby.setReady(index, ready)
                             │  sends UpdateLobbyPlayerEvent.isReadyUpdate(ready)  [request, not local change]
                             ▼
                     FServerManager.updateSlot ──► GameLobby.applyToSlot  [single legitimate writer]
                                                          │  slot.apply(event)  →  isReady = ...
                                                          │  if changed ──► updateView(false)
                                                          ▼
                                              NetConnectUtil.host listener = the FUNNEL
                                                   │                            │
                                                   ▼                            ▼
                                      view.update(...)                server.updateLobbyState()
                                   (refresh host's screen)           (broadcast LobbyUpdateEvent)
                                                                              │
                                                                              ▼
                                                         remote client: FGameClient.LobbyUpdateHandler
                                                                              │  listener.update(state, slot)
                                                                              ▼
                                                              ClientGameLobby.setData ──► updateView
                                                                              │
                                                                              ▼
                                                              VLobby.updateImpl ──► panel.setIsReady(slot.isReady())
                                                                              ▼
                                                          PlayerPanel renders checkbox from server truth
```

Key rule: **every write ends in "tell every screen, both channels"** (host in-process +
network broadcast). `applyToSlot` is the only authorized writer.

### The bypass (tournament's silent writes)

```
ServerTournamentController.startMatchForPairing   ──► slot.setIsReady(true)      ✗ direct write
ServerTournamentController.enterBetweenRoundStandby ─► slot.setIsReady(false)     ✗ direct write
ServerGameLobby.onMatchOver(matchId)                ─► slot.setIsReady(false)     ✗ direct write
        └── then only: server.updateLobbyState()  (network half; host's own screen never re-renders)
```

`HostedMatch`'s `onMatchOver` callback (fired on the EDT when a match ends) re-renders the
host's screen **before** the poll thread's standby reset runs, giving the host one final stale
"everyone ready" render — after which nothing re-renders it.

### Class trail (in reading order)

| # | Class | Where | What it does in this flow |
|---|-------|-------|---------------------------|
| 1 | `PlayerPanel` | `forge-gui-desktop/.../home/PlayerPanel.java` | Per-player panel; `chkReady` listener (line ~601) starts the flow; `setIsReady(boolean)` re-renders the checkbox from server truth. |
| 2 | `VLobby` | `forge-gui-desktop/.../home/VLobby.java` | Lobby view. `setReady` (line 549) sends the request via `playerChangeListener`; `updateImpl` (line 458) is the only place a screen re-renders ready from `slot.isReady()`. |
| 3 | `NetConnectUtil` | `forge-gui/.../net/NetConnectUtil.java` | Wiring hub. `host()` line 76 binds the player-change hook to `server::updateSlot`; lines 65–73 are the funnel (host refresh + broadcast); `join()` lines 184–197 bind incoming state to `lobby.setData`. |
| 4 | `FServerManager` | `forge-gui/.../net/server/FServerManager.java` | `updateSlot` (line 579) applies the request; `updateLobbyState` (line 572) is the network broadcast half. |
| 5 | `GameLobby` | `forge-gui/.../match/GameLobby.java` | `applyToSlot` (line 127) = the single legitimate writer → `updateView` (line 362) → funnel; `setData` (line 79) is the client's incoming entry. |
| 6 | `LobbySlot` | `forge-gui/.../match/LobbySlot.java` | The flag's home: `isReady` field, `setIsReady` (line 140), `isReady()` (line 137, true for AI), `apply` (line 45, the authorized setter). |
| 7 | `ServerGameLobby` | `forge-gui/.../net/server/ServerGameLobby.java` | `updateView` (line 51) stamps the event view then calls super; `onMatchOver(matchId)` (line 155) is one of the silent writers. |
| 8 | `FGameClient` | `forge-gui/.../net/client/FGameClient.java` | Remote side: `LobbyUpdateHandler.channelRead` (line 192) → `listener.update(state, slot)`. |
| 9 | `ClientGameLobby` | `forge-gui/.../net/client/ClientGameLobby.java` | Client lobby; `setData` (inherited) triggers the view refresh. |
| 10 | `ServerTournamentController` | `forge-gui/.../net/server/ServerTournamentController.java` | The silent writer: `startMatchForPairing` (`slot.setIsReady(true)`), `enterBetweenRoundStandby` (`slot.setIsReady(false)`), then only `updateLobbyState()`. |
| 11 | `HostedMatch` | `forge-gui/.../match/HostedMatch.java` | Timing: fires `onMatchOver` (line 574) on the EDT before the poll's standby reset, so the host's screen renders stale "ready" one last time. |

### Where to focus

Compare step 5 (`applyToSlot` → `updateView` → both channels) with step 10's direct
`slot.setIsReady(...)` + network-only `updateLobbyState()`. That asymmetry — the funnel's
host-refresh half vs. the tournament's network-only half — is the entire bug.

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
- **Confirmed (symptom: tournament panel missing on clients):** `TournamentStartEvent` can't be
  serialized — `TournamentPlayer`/`TournamentPairing` aren't `Serializable` — so it's dropped
  server-side and remote clients never get tournament state. See Gap 1.
- **Confirmed (symptom: ready checkbox stuck checked on the host between rounds):** the
  controller writes `slot.setIsReady` directly without the `applyToSlot`/`updateView` funnel,
  so only the network half fires and the host's own screen never re-renders. See Gap 11 and
  Appendix A.

## Files of Interest

| File | Role |
|------|------|
| `forge-gui/.../net/server/ServerTournamentController.java` | Tournament orchestration (poll-based) |
| `forge-gui/.../net/server/ServerGameLobby.java` | `startTournament`, standby gates, `onMatchOver(matchId)` |
| `forge-gui/.../net/server/FServerManager.java` | broadcast to host+clients, spectate request/leave handling |
| `forge-gui/.../net/NetConnectUtil.java` | wiring hub: host funnel + client binding (see Appendix A) |
| `forge-gui/.../match/GameLobby.java` | `MatchRegistry`, `startMatch(subset, ..., autoSpectate)`, `applyToSlot` (single ready writer) |
| `forge-gui/.../match/LobbySlot.java` | the ready flag's home; `isReady()`/`setIsReady()`/`apply()` |
| `forge-gui/.../net/event/*` | Tournament event classes |
| `forge-gui/.../net/{PairingView,StandingView,RoundState,EventPhase}.java` | Wire records / enums |
| `forge-gui-desktop/.../home/{CLobby,VLobby}.java` | Client state + tournament panel |
| `forge-gui-desktop/.../home/PlayerPanel.java` | ready checkbox (request out / render in) |
| `forge-gui-desktop/.../match/{ViewWinLose,NetworkTournamentWinLose}.java` | WinLose integration |
| `forge-gui-desktop/src/test/java/forge/net/TournamentLogicTest.java` | Engine/unit coverage |

## Recommended Next Steps (priority order)

1. ~~**Gap 1** — server-authoritative `TournamentUpdateEvent` snapshot; delete client-side
   re-derivation and the engine object on the wire.~~ **Done 2026-08-19.** The update event is
   broadcast on every transition; `TournamentStartEvent` is a wire-safe summary; client
   re-derivation (`buildPairingViews`/`buildStandingViews`) and the Serializable whack-a-mole
   were removed. A headless end-to-end test now pins the flow (see Gap 9).
2. ~~**Gap 11** — route server-initiated ready writes through the `applyToSlot`/`updateView`
   funnel (fixes the host's stale ready checkbox).~~ **Done (verified 2026-09-01).**
3. ~~**Gap 12** — rebuild the legacy ready/deck/legality start gate + failure feedback in
   `startTournament` (reuse `confirmIgnoreDeckLegality` by making it `protected`).~~
   **Done 2026-09-01** — gate collects all problems, broadcasts on failure, reuses the legacy
   legality dialog; helpers unit-tested.
4. ~~**Gap 2 + Gap 3 + Gap 4** — wire `gamesPerMatch`, fix draft game type, and gate WinLose
   on real tournament membership.~~ **Done 2026-09-01** — `gamesPerMatch` is applied to match
   rules, `gameTypeFor()` maps draft → `GameType.Draft`, and `ViewWinLose` gates on the new
   `isTournamentMatch()` flag (armed from `MatchStartedEvent`, reset on `openView`).
5. ~~**Gap 8** — draw/void policy for winner determination.~~ **Done 2026-09-01** —
   `TournamentPairing.MatchResult` (WIN/DRAW/VOID), ties on draw, void on no-match/desync,
   loud logging, panel shows Draw/Void.
6. ~~**Gap 9** — headless end-to-end tournament test.~~ **Done 2026-09-01** —
   `TournamentEndToEndTest` runs a full 4-player best-of-1 round-robin to completion and
   asserts the event chain + final standings.
7. **Gap 5** — complete the client side of spectating. Scope decided (2026-09-01): lobby-only
   spectating with client-side hidden hands. Remaining work: clickable `[Spectate]` button,
   per-match GUI routing on the client, open/leave spectator view, and the hidden-hands
   spectator flag.
8. **Gap 6/Gap 7** — clean up dead code, document the manual-continue and host-controlled
   standby choices.