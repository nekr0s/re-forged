# Online Tournament Spectating — Design

**Date:** 2026-09-01
**Branch:** `feature/online-tournament`
**Status:** Approved (pending review)
**Scope:** Gap 5 of `docs/superpowers/specs/2026-08-16-tournament-current-state.md`

## Background

The server half of online-tournament spectating already exists and works:
`SpectateRequestEvent` → `FServerManager.handleSpectateRequest` creates a read-only
`RemoteClientGuiGame`, `HostedMatch.registerNetworkSpectator` wires a `WatchRemoteGame` +
`GameEventForwarder`, and `SpectateApprovedEvent` is returned with a **real** `matchId`
(`PairingView.matchId` is populated post-Gap 1/2). The client half is stubbed: pairings
render inert `[Spectate]` text, `requestSpectate` has no caller, and `onSpectateApproved`
only pops an info dialog.

Two latent blockers must be fixed as part of this work:

1. `GameClientHandler.beforeCall` (`openView` case) iterates `myPlayers` with no null guard;
   the spectator path sends `openView(null)`, which NPEs on the IO thread and kills the
   connection.
2. `AbstractGuiGame.mayView` returns `true` when the GUI has no local players, so an
   unmodified spectator would see **both hands** (cheating vector).

The desktop client is single-game-view by design: `FGameClient` shares one `CMatchUI`, and
`GameProtocolHandler.getToInvoke(ctx, matchId)` ignores matchId and routes every game message
to it. Lobby-only spectating therefore fits the architecture cleanly.

## Scope decisions (confirmed 2026-09-01)

1. **Lobby-spectating only** — one active game view per client at a time (spectate during
   standby / after finishing / as a non-participant). Concurrent spectating while also
   playing your own match requires multi-tracker wire codecs on both ends and is out of scope.
2. **Hidden hands, client-side** — spectators see only public zones, rendered via a spectator
   flag on the GUI rather than server-side per-viewer filtering in `DeltaSyncManager`.
3. **Ongoing match list + Spectate button** in the tournament panel (round-robin can have
   multiple simultaneous matches; the user must pick which one to watch).
4. **Auto-return to the lobby + a "Stop spectating" button**; spectators never see
   `ViewWinLose`.
5. **Anyone in the lobby** can spectate (auto-approve, no host gate).
6. **Implementation strategy: client-led, flag on the shared GUI** (Approach A).

## Architecture & data flow

### Starting a spectate (lobby → watch)

1. The tournament panel shows an **Ongoing Matches** list: each ONGOING pairing
   (`PairingView` with real `matchId`). The user selects one and clicks **Spectate** →
   `CLobby.requestSpectate(matchId)` sends `SpectateRequestEvent(matchId)`.
2. Server `handleSpectateRequest` applies the guards in the Server Lifecycle section, creates
   a `RemoteClientGuiGame`, calls `HostedMatch.registerNetworkSpectator(...)`, and sends
   `SpectateApprovedEvent(matchId)`. Registration order means the spectator's `openView(null)`
   + `setGameView` + delta stream arrive on the client immediately around the approval event.
3. **Client-side self-synchronizing spectator mode.** The desktop client has one shared
   `CMatchUI` that receives every game message. Spectator mode is armed/cleared from the
   existing `openView` signal:
   - `openView(null)` ⇒ spectator (no local players) ⇒ arm `spectatorMode`.
   - `openView(non-null)` ⇒ own match ⇒ clear `spectatorMode`.
   This automatically switches the user out of spectator mode when their own next-round match
   starts — no separate switch needed.
4. `onSpectateApproved(matchId)` stores the spectated `matchId` client-side (for the Stop
   button and the leave event) and may show a brief "Now spectating …" status.
   `onSpectateApproved(null)` (server rejection) shows "Cannot spectate that match" and does
   nothing else.

### Leaving / auto-return (watch → lobby)

- **Stop Spectating** (Game-menu item on the match screen) while in spectator mode →
  `leaveSpectating()`: sends `SpectateLeaveEvent(spectatedMatchId)`, clears flags, closes the
  game tab (same teardown as `afterGameEnd`).
- **Spectated match ends** → the spectator GUI receives `finishGame`/`afterGameEnd`;
  `finishGame` is gated so a spectator never sees `ViewWinLose`; `afterGameEnd` funnels into
  `leaveSpectating()` (also sends the leave event so the server cleans up).
- **Own match starts while spectating** → `CLobby.onMatchStarted` detects the spectator flag
  and sends `SpectateLeaveEvent` before switching (the `openView(non-null)` clears the flag).

## Component changes

### Client

- `AbstractGuiGame`
  - Add `spectatorMode` flag + `setSpectatorMode(boolean)` / `isSpectatorMode()`.
  - Hidden-hands gating in `mayView` / `mayFlip` (see Hidden-hands rule).
  - Reset `spectatorMode` in `resetForNewMatch()`.
- `GameClientHandler.beforeCall` (`openView` case)
  - Null-guard `myPlayers`; only call `client.setGameControllers(...)` when non-null.
- `CMatchUI`
  - `openView(myPlayers)`: set `spectatorMode = (myPlayers == null)` before `initMatch`
    (self-synchronizing). `spectatingMatchId` lifecycle stays on `CLobby` (see below).
  - `finishGame()`: when spectator mode, suppress `ViewWinLose` and route to the
    leave/teardown path.
- `GameMenu` (match title-bar "Game" menu, built per-`CMatchUI` via `CMatchUIMenus`)
  - Add a **Stop Spectating** menu item; visible/enabled only in spectator mode (toggled on
    spectator-mode transitions via the existing menu `MenuListener.menuSelected` pattern).
- `CLobby`
  - Wire the new tournament-panel UI → `requestSpectate`.
  - `onSpectateApproved`: store `spectatingMatchId` + show status.
  - Add `leaveSpectating()` (clears `spectatingMatchId`); `onMatchStarted` sends
    `SpectateLeaveEvent` if currently spectating and clears `spectatingMatchId`.
- `VLobby` (tournament panel)
  - Replace the inert `[Spectate]` text with an **Ongoing Matches** list
    ("Alice vs Bob — LIVE") + **Spectate** button; enabled only when a live pairing is
    selected; keep the existing `refreshTournamentPanel` rebuild model.

### Server

- `FServerManager.handleSpectateRequest`
  - Add the guards (see Server Lifecycle).
  - Register the spectator GUI under `"spectate:" + matchId`.
  - Auto-leave any prior spectate before attaching a new one.
- `FServerManager.handleSpectateLeave`
  - Full teardown: `client.removeMatchGui("spectate:" + matchId)` +
    `HostedMatch.unregisterNetworkSpectator`.
- `HostedMatch`
  - Add `unregisterNetworkSpectator(RemoteClientGuiGame)`: remove the `WatchRemoteGame`
    controller from `humanControllers`, `deleteObserver` the `GameEventForwarder` from all
    human controllers' input queues, unsubscribe the forwarder from the game event bus, and
    `shutdownForwarder()`.

No change to client routing — everything already flows to the one `CMatchUI`. The
`spectatingMatchId` lives on `CLobby` (client session state).

## Hidden-hands rule

Only two render predicates change, both in `AbstractGuiGame`:

**`mayView(CardView c)`** — when `spectatorMode`, return `true` only if every player in the
game can see the card ("public info"), instead of the current `!hasLocalPlayers() → true`:

```
if (spectatorMode) {
    for (PlayerView p : gameView.getPlayers()) {
        if (!c.canBeShownTo(p)) return false;
    }
    return true;
}
```

`CardView.canBeShownTo` already encodes zone rules, so a spectator sees: battlefield
(face-up), graveyard, stack, command, face-up exile, revealed cards, life totals, and
hand/library **counts** — but not hand identities, library, face-down cards, or cards only one
player "may look at".

**`mayFlip(CardView cv)`** — when `spectatorMode`, return `!cv.isFaceDown()`. A face-down
card's face is private; a face-up double-faced card's back is public. (Without this, the
existing `canFaceDownBeShownToAny(empty viewers)` path returns `true` and reveals
manifested/morph faces.)

The "visible to all players" check must use `gameView.getPlayers()` (all seats), never the
spectator's own empty player list.

## Server lifecycle & guards

**Guards in `handleSpectateRequest(client, matchId)` (auto-approve, but sane):**

1. **Match must exist and be running** (already present) — else `SpectateApprovedEvent(null)`.
2. **Client must not be playing their own match** — reject with `SpectateApprovedEvent(null)`
   if the client has a match GUI under a non-spectate key. (Lobby-only: the client's single
   game view is busy.)
3. **Already spectating another match** — auto-leave the previous one (same teardown as
   `SpectateLeaveEvent`) before attaching the new spectator. One spectate per client.
4. **Requesting your own match** — treated like a normal spectate request (harmless; covered
   by guard 2's collision-free bookkeeping).

**Key convention:** the spectator GUI is registered in `client.matchGuis` under
`"spectate:" + matchId` (unifies the half-existing `"spectate:"` prefix already used for
`activeMatchId`). This lets guards 2/3 distinguish spectator entries from a player's own match
GUI (registered under the bare `matchId`).

**Teardown (`handleSpectateLeave(matchId)`):**
- `client.removeMatchGui("spectate:" + matchId)` (existing logic re-arms `activeMatchId`).
- `HostedMatch.unregisterNetworkSpectator(spectatorGui)`.
- Fire-and-forget; log the leave.

**Match-end without explicit leave:** when a spectated match's game ends,
`HostedMatch.endCurrentGame()` already calls `shutdownForwarder()` for every
`RemoteClientGuiGame` in `humanControllers` (the spectator's included), so the event stream
stops. The client's `finishGame`/`afterGameEnd` gate triggers `leaveSpectating()`, which sends
`SpectateLeaveEvent` to clear the remaining `matchGui` entry. Best-of-N: `afterGameEnd` fires
between games and closes the tab; the next game's `openView` re-opens it — the client stays
attached across games unless the match itself ends.

**Disconnect:** if a spectating client disconnects, the existing disconnect path removes the
`RemoteClient`; we also clear its `matchGui` entries. The spectator controller remains in
`humanControllers` until the match ends (forwarder already torn down by `endCurrentGame`),
which is acceptable — the controller is inert once its GUI is gone. Full disconnect-spectator
cleanup can ride along the existing disconnect handler later.

## Edge cases & error handling

- **Spectate a match that already ended** (stale click) → null match/game →
  `SpectateApprovedEvent(null)` → client shows "Cannot spectate that match"; `MatchCompleteEvent`
  refreshes the panel and removes it from the Ongoing list.
- **Multiple spectators** → each client gets its own `RemoteClientGuiGame` + forwarder;
  `registerNetworkSpectator` is per-GUI, so N spectators work with no shared state.
- **No ongoing matches** (standby / round complete) → Ongoing list empty → Spectate button
  disabled.
- **Client playing their own match** → guard rejects; the lobby isn't visible while playing,
  so the button is unreachable in practice.
- **Guest (non-participant) in the lobby** → allowed (decision 5).
- **Spectating your own match** → allowed, but hidden-hands still applies (consistent
  public-info view).
- **First-frame hand flash** → prevented by EDT ordering: `openView` arms the flag before
  `initMatch`/paint, and `setGameView`/deltas arrive in-order on the same connection.
  Fallback if a race shows up: also arm `spectatorMode` in `onSpectateApproved`.
- **Reconnect while spectating** → **out of scope** this phase; a reconnected spectator must
  re-request. Documented limitation.

## Testing (TDD)

- **Unit — hidden hands:** `mayView`/`mayFlip` with a two-player `GameView` (hands,
  battlefield, graveyard, stack, face-down cards) asserting the public-info rule. Uses the
  existing headless GUI / test-utils pattern.
- **Unit — server guards:** `handleSpectateRequest` (reject while in own match, auto-leave
  prior, missing match → null ack) and `handleSpectateLeave` teardown via the existing
  `UnifiedNetworkHarness`.
- **Unit — `HostedMatch.unregisterNetworkSpectator`:** controller removed from
  `humanControllers`, forwarder unsubscribed from bus + input queues.
- **Unit — client `openView` null-guard:** `GameClientHandler.beforeCall` with `null`
  `myPlayers` does not throw.
- **End-to-end — spectate flow:** extend the headless harness — a client spectates an ongoing
  AI-vs-AI pairing, asserts real matchId in `SpectateApprovedEvent`, receives `openView` +
  `setGameView` + events on the spectator GUI, hidden-hands holds, and match end auto-returns
  to the lobby. Mirrors `TournamentEndToEndTest`.

## Out of scope (this phase)

- Concurrent spectating while playing your own match (multi-tracker codecs).
- Server-side hand filtering on the wire (stronger integrity, high effort).
- Reconnect-while-spectating.
- Host-approval gate for spectate requests.
- Libgdx/mobile spectate UI (desktop `CMatchUI` only).
