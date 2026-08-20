# Tournament & Round State Model

> **STATUS: PARTIALLY SUPERSEDED.** The core idea (separate `RoundState` from `EventPhase`,
> remove `ROUND_IN_PROGRESS`) was implemented — but the **"roundState on the wire via
> `NetworkEventView`"** part was reverted on 2026-08-16 after causing inconsistent screens.
> Today `NetworkEventView` carries no tournament fields; `RoundState` is tracked client-side
> from the dedicated tournament events (`MatchStarted` → `ACTIVE`, `RoundComplete` →
> `COMPLETE`). See **`2026-08-16-tournament-current-state.md`** for the accurate model and the
> plan to move to a server-authoritative snapshot event.

## Overview

Currently tournament and round lifecycle state is conflated into a single `EventPhase`
enum. This spec separates the two into distinct, orthogonal state dimensions:

1. **`EventPhase`** — the lifecycle of the *event / tournament* (stable across rounds).
2. **`RoundState`** — the lifecycle of the *current round* (toggles as matches start and finish).

The round state is carried on the wire so clients can render the tournament panel without
re-deriving it, enabling the richer tournament UI (per-match spectate buttons, ready
indicators, and an explicit "round in progress vs. round complete" signal).

## Motivation

The existing model introduced `ROUND_IN_PROGRESS` as a value of `EventPhase`, alongside
`TOURNAMENT_IN_PROGRESS`. This conflated two independent facts:

- Is the tournament underway?
- Are the current round's matches running?

As a result the meaning of the two values drifted (they were effectively reversed relative to
their intended semantics), and no consumer could cleanly distinguish "round active" from
"between rounds" without parsing pairings. A tournament and a round have separate lifecycles
and should be modeled separately.

## Requirements

- **Tournament lifecycle** is a stable, monotonically-advancing state: not started → in
  progress → complete/cancelled. It does **not** flip between rounds.
- **Round lifecycle** tracks whether the current round's matches are active or all finished.
- **Both** are observable on the wire so the client can render the tournament panel accurately.
- Non-tournament events are unaffected: `RoundState` is `NONE` and never changes their behavior.
- Removing `ROUND_IN_PROGRESS` from `EventPhase` must not break existing draft/sealed/constructed
  flows (which only use `LOBBY_GATHER`, `DRAFTING`, `POOL_DISTRIBUTION`).

## Architecture

### Two orthogonal state dimensions

```
EventPhase (tournament lifecycle — stable during a tournament)
  LOBBY_GATHER -> DRAFTING | POOL_DISTRIBUTION -> TOURNAMENT_IN_PROGRESS -> TOURNAMENT_COMPLETE

RoundState  (current round lifecycle — toggles while tournament is in progress)
  NONE -> ACTIVE -> COMPLETE -> (ACTIVE -> COMPLETE)* -> COMPLETE
```

`TOURNAMENT_IN_PROGRESS` stays set for the entire tournament regardless of which round is being
played. Only `RoundState` changes as rounds begin and end. This directly encodes the observation
that "the tournament's state never changed — it was in progress throughout."

### `EventPhase` final values (removing the overloaded value)

```
LOBBY_GATHER, DRAFTING, POOL_DISTRIBUTION, TOURNAMENT_IN_PROGRESS, TOURNAMENT_COMPLETE
```

`ROUND_IN_PROGRESS` is removed from `EventPhase`. Round activity is represented by `RoundState`.

### New `RoundState` enum

```java
public enum RoundState {
    NONE,     // no current round (tournament not started, or finished/cancelled)
    ACTIVE,   // the current round's matches are running
    COMPLETE  // all of the current round's matches finished; waiting for the next round
}
```

### State transitions (server, `ServerTournamentController`)

| Event | `EventPhase` | `RoundState` |
|-------|--------------|--------------|
| Tournament starts, round 1 matches begin | `TOURNAMENT_IN_PROGRESS` | `ACTIVE` |
| All matches in a round finish | `TOURNAMENT_IN_PROGRESS` (unchanged) | `COMPLETE` |
| Host starts the next round | `TOURNAMENT_IN_PROGRESS` (unchanged) | `ACTIVE` |
| Final round completes | `TOURNAMENT_COMPLETE` | `COMPLETE` |
| Tournament cancelled | `TOURNAMENT_COMPLETE` | `COMPLETE` |

Key property: `EventPhase` does **not** change during a round — it remains
`TOURNAMENT_IN_PROGRESS`. Only `RoundState` toggles between `ACTIVE` and `COMPLETE`.

### Wire representation

`RoundState` is added as an explicit field on `NetworkEventView` (and the underlying
`NetworkEvent`), alongside the existing tournament fields. It is `NONE` for all non-tournament
events.

```
NetworkEvent / NetworkEventView
  + roundState: RoundState      (NEW)
```

### Round number semantics

`NetworkEventView.currentRound` means "the round the UI should display," derived from the
tournament engine's `activeRound` together with `roundState`. Because the engine advances
`activeRound` when a round's last match completes (not when the next round starts), the
displayed number must account for the between-round window:

| `RoundState` | Displayed round | Source |
|--------------|-----------------|--------|
| `ACTIVE` | N (matches running) | `activeRound` |
| `COMPLETE` | N (the round that just finished) | `activeRound - 1` |

The subtraction is safe: the `COMPLETE` window only exists for `activeRound >= 2`, so
`activeRound - 1 >= 1` is always valid. The final round uses a different rendering path
(`TOURNAMENT_COMPLETE` -> final standings), where the engine calls `endTournament()` without
incrementing, so the rule is not applied there.

`NetworkEvent.toView()` computes `currentRound` from `roundState` and `activeRound`, so clients
receive the correct number for the current state and need no off-by-one logic of their own.

## Files Affected

| File | Change |
|------|--------|
| `forge-gui/.../net/EventPhase.java` | Remove `ROUND_IN_PROGRESS` |
| `forge-gui/.../net/RoundState.java` | NEW enum (`NONE`, `ACTIVE`, `COMPLETE`) |
| `forge-gui/.../net/NetworkEvent.java` | Add `roundState` field + getter/setter; include in `toView()` |
| `forge-gui/.../net/NetworkEventView.java` | Add `roundState` field + getter; update both constructors |
| `forge-gui/.../net/server/ServerTournamentController.java` | Set `roundState` on transitions; remove `ROUND_IN_PROGRESS` `setPhase` calls |
| `forge-gui-desktop/.../home/CLobby.java` | Track `roundState` from `NetworkEventView`; expose accessor |
| `forge-gui-desktop/.../home/VLobby.java` | Render round state distinctly in the tournament panel |

## Client Behavior

The tournament panel distinguishes two states using `roundState`:

- **`ACTIVE`** — "Round N of M in progress". Per-match spectate buttons are enabled for
  `PairingView.status == ONGOING`. If this player is in an ongoing match they are in the match UI.
- **`COMPLETE`** — "Round N complete — waiting for host to start round N+1". Per-player ready
  indicators shown. The host's "Start Next Round" button becomes enabled when all human players
  are ready.

`CLobby` stores `roundState` (mirroring how it already stores `currentRound`, `pairings`,
`standings`) and exposes an accessor; `VLobby.refreshTournamentPanel()` uses it to render the
appropriate state text and controls.

## Error Handling & Edge Cases

- **Non-tournament events**: `roundState` is `NONE`; the tournament panel is hidden and no
  behavior changes.
- **Host cancels mid-round**: `roundState` becomes `COMPLETE` and `EventPhase` becomes
  `TOURNAMENT_COMPLETE` (as today).
- **No human players**: round state transitions still occur; the controller auto-advances as it
  already does for all-AI tournaments.
- **Backward compatibility**: removing `ROUND_IN_PROGRESS` from the enum changes the serialized
  ordinal space of `EventPhase`. Since events are in-memory only (no persistence across server
  restarts), this is safe. The wire value is transmitted as an enum, so both client and server
  must be updated together (already required for tournament protocol changes).

## Scope Boundaries (Not Building)

- No change to pairing, standings, or match-completion logic — only the state representation.
- No changes to draft/sealed/constructed flows beyond removing the unused `ROUND_IN_PROGRESS`
  enum value.
- No persistence of tournament or round state.

## Testing Strategy

- **Compilation**: `mvn compile -pl forge-gui-desktop -am`.
- **Unit tests**: verify `RoundState` transitions in `ServerTournamentController` are correct
  (round active when matches start, complete when the round finishes, stable `EventPhase`).
- **Regression**: `TournamentLogicTest` and existing network integration tests must still pass;
  confirm no production code references `ROUND_IN_PROGRESS` after the change.

## Implementation Order

1. Add `RoundState` enum; remove `ROUND_IN_PROGRESS` from `EventPhase`.
2. Add `roundState` to `NetworkEvent` and `NetworkEventView` (both constructors + `toView()`).
3. Update `ServerTournamentController` to set `roundState` on transitions and drop the
   `ROUND_IN_PROGRESS` `setPhase` call.
4. Update `CLobby` / `VLobby` to track and render `roundState`.
5. Compile and run tournament + network tests.