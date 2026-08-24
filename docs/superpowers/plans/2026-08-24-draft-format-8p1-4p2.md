# Draft Format Support (8-Player-Pick-One / 4-Player-Pick-Two) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let an online draft-tournament host choose between an 8-player pick-one draft and a 4-player pick-two draft, with the draft pod always padded to the chosen size by non-playable AI fillers while the round-robin tournament counts only real seated players/bots.

**Architecture:** Introduce an explicit `DraftStyle` enum (pod size + pick rule). Wire the existing offline `DraftOptions.DoublePick.ALWAYS` "pick 2 then pass" logic into the network `BoosterDraftHost` (which today always passes after one pick). Restrict `ServerTournamentController` to real seated participants (`lobbySlotIndex >= 0`), excluding draft-filler AI, so a 4-human 8P1 event becomes an 8-seat draft but a 4-player tournament. Build tournament decks for playable AI bots from their drafted pools.

**Tech Stack:** Java 17, Maven, TestNG 7.10.2

**Context / spec:** Current behavior is documented in `docs/superpowers/specs/2026-08-16-tournament-current-state.md`. This plan implements the format-selection design agreed with the host user on 2026-08-24.

---

## File Structure

### New Files

| File | Responsibility |
|------|----------------|
| `forge-gui/src/main/java/forge/gamemodes/net/DraftStyle.java` | The two draft formats: `EIGHT_PLAYER_PICK_ONE` (pod 8, pick 1) and `FOUR_PLAYER_PICK_TWO` (pod 4, pick 2). |
| `forge-gui-desktop/src/test/java/forge/net/DraftStyleTest.java` | Unit test for the `DraftStyle` mapping. |
| `forge-gui-desktop/src/test/java/forge/net/BoosterDraftHostDoublePickTest.java` | Unit tests for the double-pick keep/pass decision and pass-direction helpers. |
| `forge-gui-desktop/src/test/java/forge/net/NetworkEventViewSerializationTest.java` | Wire-safety test for `NetworkEventView` carrying `draftStyle`. |
| `forge-gui-desktop/src/test/java/forge/net/TournamentParticipantFilterTest.java` | Unit test for the real-vs-filler participant filter. |

### Modified Files

| File | Changes |
|------|---------|
| `forge-gui/.../limited/BoosterDraft.java` | Add `getDoublePickDuringDraft()` / `setDoublePickDuringDraft()`. |
| `forge-gui/.../net/draft/BoosterDraftHost.java` | Keep pack on even picks for double-pick; refactor pass direction; build decks for playable AI. |
| `forge-gui/.../net/NetworkEvent.java` | Add `draftStyle` field + accessors; pass through `toView()`. |
| `forge-gui/.../net/NetworkEventView.java` | Add `draftStyle` field + constructor param + getter; bump `serialVersionUID`. |
| `forge-gui/.../net/server/ServerTournamentController.java` | Add `realParticipants()` filter; exclude draft fillers from the tournament. |
| `forge-gui/.../net/server/ServerGameLobby.java` | Draft pod size from `DraftStyle`; override double-pick; overfill + min-player guards; drop `DRAFT_POD_SIZE`. |
| `forge-gui-desktop/.../home/CLobby.java` | Prompt for draft format during event setup. |
| `forge-gui/res/languages/en-US.properties` | New format labels + prompt keys. |

---

## Task 1: `DraftStyle` enum

**Files:**
- Create: `forge-gui/src/main/java/forge/gamemodes/net/DraftStyle.java`
- Test: `forge-gui-desktop/src/test/java/forge/net/DraftStyleTest.java`

- [ ] **Step 1: Write the failing test**

Create `forge-gui-desktop/src/test/java/forge/net/DraftStyleTest.java`:

```java
package forge.net;

import forge.card.DraftOptions;
import forge.gamemodes.net.DraftStyle;
import org.testng.Assert;
import org.testng.annotations.Test;

public class DraftStyleTest {

    @Test
    public void eightPlayerPickOne() {
        Assert.assertEquals(DraftStyle.EIGHT_PLAYER_PICK_ONE.podSize(), 8);
        Assert.assertEquals(DraftStyle.EIGHT_PLAYER_PICK_ONE.doublePick(), DraftOptions.DoublePick.NEVER);
        Assert.assertFalse(DraftStyle.EIGHT_PLAYER_PICK_ONE.isDoublePick());
    }

    @Test
    public void fourPlayerPickTwo() {
        Assert.assertEquals(DraftStyle.FOUR_PLAYER_PICK_TWO.podSize(), 4);
        Assert.assertEquals(DraftStyle.FOUR_PLAYER_PICK_TWO.doublePick(), DraftOptions.DoublePick.ALWAYS);
        Assert.assertTrue(DraftStyle.FOUR_PLAYER_PICK_TWO.isDoublePick());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl forge-gui-desktop -am -Dtest=DraftStyleTest -q`
Expected: FAIL with `cannot find symbol ... DraftStyle`.

- [ ] **Step 3: Create the enum**

Create `forge-gui/src/main/java/forge/gamemodes/net/DraftStyle.java`:

```java
package forge.gamemodes.net;

import forge.card.DraftOptions;

/**
 * The two supported online draft formats.
 * <p>
 * Each format fixes the draft pod size (the number of seats that draft and pass
 * packs) and the pick rule (how many cards a player takes from a pack before it
 * passes to the next seat). Non-playable AI fillers pad the pod to the format's
 * size; the round-robin tournament only counts real seated players/bots.
 */
public enum DraftStyle {
    EIGHT_PLAYER_PICK_ONE(8, DraftOptions.DoublePick.NEVER),
    FOUR_PLAYER_PICK_TWO(4, DraftOptions.DoublePick.ALWAYS);

    private final int podSize;
    private final DraftOptions.DoublePick doublePick;

    DraftStyle(int podSize, DraftOptions.DoublePick doublePick) {
        this.podSize = podSize;
        this.doublePick = doublePick;
    }

    public int podSize() { return podSize; }
    public DraftOptions.DoublePick doublePick() { return doublePick; }

    /** True when each player takes two cards per pack before passing. */
    public boolean isDoublePick() { return doublePick == DraftOptions.DoublePick.ALWAYS; }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl forge-gui-desktop -am -Dtest=DraftStyleTest -q`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add forge-gui/src/main/java/forge/gamemodes/net/DraftStyle.java forge-gui-desktop/src/test/java/forge/net/DraftStyleTest.java
git commit -m "feat: add DraftStyle enum for 8-player pick-one and 4-player pick-two draft formats"
```

---

## Task 2: Pure double-pick helpers in `BoosterDraftHost`

**Files:**
- Modify: `forge-gui/src/main/java/forge/gamemodes/net/draft/BoosterDraftHost.java`
- Test: `forge-gui-desktop/src/test/java/forge/net/BoosterDraftHostDoublePickTest.java`

- [ ] **Step 1: Write the failing test**

Create `forge-gui-desktop/src/test/java/forge/net/BoosterDraftHostDoublePickTest.java`:

```java
package forge.net;

import forge.card.DraftOptions;
import forge.gamemodes.net.draft.BoosterDraftHost;
import org.testng.Assert;
import org.testng.annotations.Test;

public class BoosterDraftHostDoublePickTest {

    @Test
    public void pickOneNeverKeepsPack() {
        for (int taken = 0; taken < 5; taken++) {
            Assert.assertFalse(BoosterDraftHost.keepPackForDoublePick(
                    DraftOptions.DoublePick.NEVER, taken));
        }
    }

    @Test
    public void pickTwoKeepsPackOnEvenPicksTaken() {
        // picksTaken is the number of cards already removed from the pack before
        // this pick (0-based pick index). Even index -> 1st of a pair -> keep.
        Assert.assertTrue(BoosterDraftHost.keepPackForDoublePick(DraftOptions.DoublePick.ALWAYS, 0));
        Assert.assertFalse(BoosterDraftHost.keepPackForDoublePick(DraftOptions.DoublePick.ALWAYS, 1));
        Assert.assertTrue(BoosterDraftHost.keepPackForDoublePick(DraftOptions.DoublePick.ALWAYS, 2));
        Assert.assertFalse(BoosterDraftHost.keepPackForDoublePick(DraftOptions.DoublePick.ALWAYS, 3));
    }

    @Test
    public void pickTwoFirstPickOptionIsNotKeptByHelper() {
        // The network host only uses NEVER (8P1) and ALWAYS (4P2); FIRST_PICK is
        // not a supported online format, so the helper returns false.
        Assert.assertFalse(BoosterDraftHost.keepPackForDoublePick(DraftOptions.DoublePick.FIRST_PICK, 0));
    }

    @Test
    public void passDirectionAlternatesByPackNumber() {
        Assert.assertEquals(BoosterDraftHost.nextSeat(0, 4, 1), 1); // odd pack  -> right
        Assert.assertEquals(BoosterDraftHost.nextSeat(0, 4, 2), 3); // even pack -> left
        Assert.assertEquals(BoosterDraftHost.nextSeat(3, 4, 1), 0); // wraps around
        Assert.assertEquals(BoosterDraftHost.nextSeat(1, 8, 2), 0);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl forge-gui-desktop -am -Dtest=BoosterDraftHostDoublePickTest -q`
Expected: FAIL with `cannot find symbol ... keepPackForDoublePick`.

- [ ] **Step 3: Add the static helpers**

In `forge-gui/src/main/java/forge/gamemodes/net/draft/BoosterDraftHost.java`:

Add the import at the top (alphabetical, after the `forge.deck.*` imports):

```java
import forge.card.DraftOptions;
```

Add these two static methods inside the class (place them right before the existing private `passToNext` method, i.e. just after `captureInitialPackSize`):

```java
    /**
     * Whether the pack should be kept for a second pick instead of passing.
     * Double-pick (4P2) keeps the pack on even pick indices (the 1st of a pair)
     * and passes on odd indices (the 2nd of a pair). All other modes pass every
     * pick.
     *
     * @param mode             the draft's double-pick mode
     * @param picksTakenFromPack cards already removed from this pack before the
     *                          current pick (0-based pick index)
     */
    public static boolean keepPackForDoublePick(DraftOptions.DoublePick mode, int picksTakenFromPack) {
        return mode == DraftOptions.DoublePick.ALWAYS && picksTakenFromPack % 2 == 0;
    }

    /**
     * The next seat a pack travels to for a given pack number: odd packs pass
     * right (+1), even packs pass left (-1) — MTG convention.
     */
    public static int nextSeat(int fromSeat, int podSize, int packNumber) {
        int dir = (packNumber % 2 == 1) ? 1 : -1;
        return ((fromSeat + dir) % podSize + podSize) % podSize;
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl forge-gui-desktop -am -Dtest=BoosterDraftHostDoublePickTest -q`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add forge-gui/src/main/java/forge/gamemodes/net/draft/BoosterDraftHost.java forge-gui-desktop/src/test/java/forge/net/BoosterDraftHostDoublePickTest.java
git commit -m "feat: add double-pick keep/pass and pass-direction helpers to BoosterDraftHost"
```

---

## Task 3: Wire double-pick into `BoosterDraftHost` + `BoosterDraft` accessors

**Files:**
- Modify: `forge-gui/src/main/java/forge/gamemodes/limited/BoosterDraft.java`
- Modify: `forge-gui/src/main/java/forge/gamemodes/net/draft/BoosterDraftHost.java`

- [ ] **Step 1: Add double-pick accessors to `BoosterDraft`**

In `forge-gui/src/main/java/forge/gamemodes/limited/BoosterDraft.java`, the private field `doublePickDuringDraft` is declared at line 70. Add a getter and setter next to the existing `getPodSize()` method (around line 420-422):

```java
    public int getPodSize() {
        return this.podSize;
    }

    /** The current double-pick mode (null when never set — treat as NEVER). */
    public DraftOptions.DoublePick getDoublePickDuringDraft() {
        return doublePickDuringDraft;
    }

    /** Override the double-pick mode (e.g. from the host's chosen draft format). */
    public void setDoublePickDuringDraft(DraftOptions.DoublePick mode) {
        this.doublePickDuringDraft = mode;
    }
```

(`DraftOptions` is already imported at the top of `BoosterDraft.java`, line 22.)

- [ ] **Step 2: Track the double-pick mode in `BoosterDraftHost`**

In `forge-gui/src/main/java/forge/gamemodes/net/draft/BoosterDraftHost.java`:

Add a field next to the other fields (near line 55, after `private final NetworkEvent event;`):

```java
    private final DraftOptions.DoublePick doublePickMode;
```

In the constructor (lines 83-96), after `this.participants = new ArrayList<>(event.getParticipants());`, initialize the mode from the draft (null-safe):

```java
        DraftOptions.DoublePick mode = draft.getDoublePickDuringDraft();
        this.doublePickMode = mode != null ? mode : DraftOptions.DoublePick.NEVER;
```

- [ ] **Step 3: Keep the pack on even picks in `applyPickAndPass`**

Replace the existing `applyPickAndPass` method (lines 173-182) with:

```java
    private void applyPickAndPass(LimitedPlayer player, int seatIndex, PaperCard card) {
        DraftPack head = player.nextChoice();
        int picksTaken = head == null ? 0 : initialPackSize - head.size();
        Boolean passPack = player.draftCard(card, DeckSection.Sideboard);
        picksMadePerSeat[seatIndex]++;
        // Double-pick (4P2): the 1st card of a pair keeps the pack for a 2nd pick.
        boolean keepForDoublePick = keepPackForDoublePick(doublePickMode, picksTaken);
        if (!Boolean.FALSE.equals(passPack) && !keepForDoublePick) {
            DraftPack passed = player.passPack();
            if (passed != null && !passed.isEmpty()) {
                passToNext(seatIndex, passed);
            }
        }
    }
```

- [ ] **Step 4: Route passing through `nextSeat`**

Replace the existing `passToNext` method (lines 269-274) with:

```java
    private void passToNext(int fromSeat, DraftPack pack) {
        int podSize = draft.getAllPlayers().size();
        draft.getAllPlayers().get(nextSeat(fromSeat, podSize, currentPackNumber)).receiveOpenedPack(pack);
    }
```

- [ ] **Step 5: Verify compile + tests**

Run: `mvn -pl forge-gui-desktop -am compile -q`
Expected: BUILD SUCCESS.

Run: `mvn test -pl forge-gui-desktop -am -Dtest=BoosterDraftHostDoublePickTest,DraftStyleTest -q`
Expected: PASS (6 tests).

- [ ] **Step 6: Commit**

```bash
git add forge-gui/src/main/java/forge/gamemodes/limited/BoosterDraft.java forge-gui/src/main/java/forge/gamemodes/net/draft/BoosterDraftHost.java
git commit -m "feat: wire pick-two-then-pass into the network draft host"
```

---

## Task 4: Carry `draftStyle` on `NetworkEvent` / `NetworkEventView`

**Files:**
- Modify: `forge-gui/src/main/java/forge/gamemodes/net/NetworkEvent.java`
- Modify: `forge-gui/src/main/java/forge/gamemodes/net/NetworkEventView.java`
- Test: `forge-gui-desktop/src/test/java/forge/net/NetworkEventViewSerializationTest.java`

- [ ] **Step 1: Write the failing serialization test**

Create `forge-gui-desktop/src/test/java/forge/net/NetworkEventViewSerializationTest.java`:

```java
package forge.net;

import forge.gamemodes.net.DraftStyle;
import forge.gamemodes.net.EventFormat;
import forge.gamemodes.net.EventParticipant;
import forge.gamemodes.net.EventPhase;
import forge.gamemodes.net.NetworkEventView;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;

public class NetworkEventViewSerializationTest {

    private static Object roundTrip(Object original) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(original);
        }
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(baos.toByteArray()))) {
            return ois.readObject();
        }
    }

    @Test
    public void testDraftStyleRoundTrips() throws Exception {
        List<EventParticipant> participants = List.of(
                new EventParticipant("Host", EventParticipant.Type.HUMAN, 0, 0),
                new EventParticipant("Bob", EventParticipant.Type.HUMAN, 1, 1));
        NetworkEventView view = new NetworkEventView(
                "evt-1", EventFormat.BOOSTER_DRAFT, EventPhase.LOBBY_GATHER,
                participants, 60, "Full", 3, DraftStyle.FOUR_PLAYER_PICK_TWO);

        NetworkEventView decoded = (NetworkEventView) roundTrip(view);

        Assert.assertEquals(decoded.getDraftStyle(), DraftStyle.FOUR_PLAYER_PICK_TWO);
        Assert.assertEquals(decoded.getNumDraftRounds(), 3);
        Assert.assertEquals(decoded.getFormat(), EventFormat.BOOSTER_DRAFT);
    }

    @Test
    public void testDefaultDraftStyleIsEightPlayerPickOne() throws Exception {
        List<EventParticipant> participants = List.of(
                new EventParticipant("Host", EventParticipant.Type.HUMAN, 0, 0));
        NetworkEventView view = new NetworkEventView(
                "evt-2", EventFormat.BOOSTER_DRAFT, EventPhase.LOBBY_GATHER,
                participants, 60, "Full", 3, DraftStyle.EIGHT_PLAYER_PICK_ONE);

        NetworkEventView decoded = (NetworkEventView) roundTrip(view);

        Assert.assertEquals(decoded.getDraftStyle(), DraftStyle.EIGHT_PLAYER_PICK_ONE);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl forge-gui-desktop -am -Dtest=NetworkEventViewSerializationTest -q`
Expected: FAIL — `NetworkEventView` constructor has no `draftStyle` parameter.

- [ ] **Step 3: Add `draftStyle` to `NetworkEvent`**

In `forge-gui/src/main/java/forge/gamemodes/net/NetworkEvent.java`, add a field next to `numRounds` (line 40) and its accessors next to `getNumRounds()` (lines 74-75):

```java
    private DraftStyle draftStyle = DraftStyle.EIGHT_PLAYER_PICK_ONE;
    ...
    public DraftStyle getDraftStyle() { return draftStyle; }
    public void setDraftStyle(DraftStyle draftStyle) { this.draftStyle = draftStyle; }
```

Update `toView()` (lines 113-116) to pass it through:

```java
    public NetworkEventView toView() {
        return new NetworkEventView(eventId, format, phase,
                participants, pickTimerSeconds, productDescription, numRounds, draftStyle);
    }
```

- [ ] **Step 4: Add `draftStyle` to `NetworkEventView`**

In `forge-gui/src/main/java/forge/gamemodes/net/NetworkEventView.java`:

- Bump the serial version to `2L` (line 12).
- Add the field after `numDraftRounds` (line 20):

```java
    private final DraftStyle draftStyle;
```

- Extend the constructor to accept it as the last parameter and store it (lines 24-34):

```java
    public NetworkEventView(String eventId, EventFormat format, EventPhase phase,
            List<EventParticipant> participants, int pickTimerSeconds,
            String productDescription, int numDraftRounds, DraftStyle draftStyle) {
        this.eventId = eventId;
        this.format = format;
        this.phase = phase;
        this.participants = List.copyOf(participants);
        this.pickTimerSeconds = pickTimerSeconds;
        this.productDescription = productDescription;
        this.numDraftRounds = numDraftRounds;
        this.draftStyle = draftStyle;
    }
```

- Add the getter next to `getNumDraftRounds()` (line 42):

```java
    public DraftStyle getDraftStyle() { return draftStyle; }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn test -pl forge-gui-desktop -am -Dtest=NetworkEventViewSerializationTest -q`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add forge-gui/src/main/java/forge/gamemodes/net/NetworkEvent.java forge-gui/src/main/java/forge/gamemodes/net/NetworkEventView.java forge-gui-desktop/src/test/java/forge/net/NetworkEventViewSerializationTest.java
git commit -m "feat: carry draftStyle on NetworkEvent and NetworkEventView"
```

---

## Task 5: Exclude draft fillers from the tournament

**Files:**
- Modify: `forge-gui/src/main/java/forge/gamemodes/net/server/ServerTournamentController.java`
- Test: `forge-gui-desktop/src/test/java/forge/net/TournamentParticipantFilterTest.java`

- [ ] **Step 1: Write the failing test**

Create `forge-gui-desktop/src/test/java/forge/net/TournamentParticipantFilterTest.java`:

```java
package forge.net;

import forge.gamemodes.net.EventParticipant;
import forge.gamemodes.net.server.ServerTournamentController;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.List;

public class TournamentParticipantFilterTest {

    @Test
    public void excludesDraftFillerSeats() {
        // Host (slot 0), playable bot (slot 1), and remote Bob (slot 2) are real.
        // "Filler" has lobbySlotIndex -1 (draft-pod padding AI) and must be excluded.
        List<EventParticipant> participants = Arrays.asList(
                new EventParticipant("Host", EventParticipant.Type.HUMAN, 0, 0),
                new EventParticipant("BotA", EventParticipant.Type.AI, 1, 1),
                new EventParticipant("Filler", EventParticipant.Type.AI, 2, -1),
                new EventParticipant("Bob", EventParticipant.Type.HUMAN, 3, 2));

        List<EventParticipant> real = ServerTournamentController.realParticipants(participants);

        Assert.assertEquals(real.size(), 3);
        Assert.assertEquals(real.get(0).getName(), "Host");
        Assert.assertEquals(real.get(1).getName(), "BotA");
        Assert.assertEquals(real.get(2).getName(), "Bob");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl forge-gui-desktop -am -Dtest=TournamentParticipantFilterTest -q`
Expected: FAIL with `cannot find symbol ... realParticipants`.

- [ ] **Step 3: Add the filter and use it in the constructor**

In `forge-gui/src/main/java/forge/gamemodes/net/server/ServerTournamentController.java`:

Add a static helper method:

```java
    /**
     * Participants that take part in the tournament: real seated players and
     * host-added playable bots ({@code lobbySlotIndex >= 0}). Draft-pod padding
     * AI fillers ({@code lobbySlotIndex == -1}) only draft and pass packs; they
     * never play tournament matches.
     */
    public static List<EventParticipant> realParticipants(List<EventParticipant> participants) {
        List<EventParticipant> out = new ArrayList<>();
        for (EventParticipant ep : participants) {
            if (ep.getLobbySlotIndex() >= 0) {
                out.add(ep);
            }
        }
        return out;
    }
```

Replace the constructor's participant loop (lines 47-58) with:

```java
        List<TournamentPlayer> players = new ArrayList<>();
        for (EventParticipant ep : realParticipants(event.getParticipants())) {
            LobbyPlayer lobbyPlayer;
            if (ep.isHuman()) {
                lobbyPlayer = GamePlayerUtil.getGuiPlayer(ep.getName(), -1, -1, false);
            } else {
                lobbyPlayer = new LobbyPlayerAi(ep.getName(), null);
            }
            TournamentPlayer tp = new TournamentPlayer(lobbyPlayer, ep.getSeatIndex());
            ep.setTournamentPlayer(tp);
            players.add(tp);
        }
```

(`EventParticipant` is already imported via `forge.gamemodes.net.*`.)

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl forge-gui-desktop -am -Dtest=TournamentParticipantFilterTest -q`
Expected: PASS.

- [ ] **Step 5: Run the existing tournament logic tests**

Run: `mvn test -pl forge-gui-desktop -am -Dtest=TournamentLogicTest -q`
Expected: PASS (existing tests — the 4-player round robin math is unchanged).

- [ ] **Step 6: Commit**

```bash
git add forge-gui/src/main/java/forge/gamemodes/net/server/ServerTournamentController.java forge-gui-desktop/src/test/java/forge/net/TournamentParticipantFilterTest.java
git commit -m "feat: exclude draft-pod filler AI from the tournament field"
```

---

## Task 6: Draft pod size + double-pick override + overfill guard in `ServerGameLobby`

**Files:**
- Modify: `forge-gui/src/main/java/forge/gamemodes/net/server/ServerGameLobby.java`

- [ ] **Step 1: Remove the hardcoded pod size constant**

Delete the `DRAFT_POD_SIZE` constant (line 37):

```java
    private static final int DRAFT_POD_SIZE = 8;
```

- [ ] **Step 2: Size the pod from `DraftStyle`, override double-pick, guard overfill**

In `startDraftEvent()` (lines 306-361), replace the body up through the `draft.setHumanSeats(...)` + `initializeBoosters()` block. The current code is:

```java
        populateParticipants();
        fillRemainingWithAI(DRAFT_POD_SIZE);
        shuffleSeatPositions();

        List<EventParticipant> participants = event.getParticipants();
        int podSize = participants.size();

        BoosterDraft draft = event.getDraft();
        if (draft == null) return null;

        if (podSize != draft.getPodSize()) {
            draft.setPodSize(podSize);
        }
```

Replace it with:

```java
        populateParticipants();

        DraftStyle draftStyle = event.getDraftStyle();
        int realPlayers = ServerTournamentController.realParticipants(event.getParticipants()).size();
        if (realPlayers > draftStyle.podSize()) {
            netLog.warn("Cannot start draft — {} real players exceeds {} pod size",
                    realPlayers, draftStyle);
            FServerManager.getInstance().broadcast(new MessageEvent(
                    "Cannot start draft: " + draftStyle + " supports up to "
                            + draftStyle.podSize() + " players."));
            return null;
        }

        fillRemainingWithAI(draftStyle.podSize());
        shuffleSeatPositions();

        List<EventParticipant> participants = event.getParticipants();
        int podSize = participants.size();

        BoosterDraft draft = event.getDraft();
        if (draft == null) return null;

        if (podSize != draft.getPodSize()) {
            draft.setPodSize(podSize);
        }
        // Host's chosen format always wins over the set's recommended pod size /
        // double-pick setting baked into the draft during product generation.
        draft.setDoublePickDuringDraft(draftStyle.doublePick());
```

(`DraftStyle` resolves via the existing `import forge.gamemodes.net.*;` at line 11; `MessageEvent` is already imported at line 20.)

- [ ] **Step 3: Verify compile**

Run: `mvn -pl forge-gui-desktop -am compile -q`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add forge-gui/src/main/java/forge/gamemodes/net/server/ServerGameLobby.java
git commit -m "feat: size draft pod from DraftStyle, override double-pick, guard overfilled pods"
```

---

## Task 7: Build tournament decks for playable AI draft participants

**Files:**
- Modify: `forge-gui/src/main/java/forge/gamemodes/net/draft/BoosterDraftHost.java`

- [ ] **Step 1: Add the `IBoosterDraft` import**

In `forge-gui/src/main/java/forge/gamemodes/net/draft/BoosterDraftHost.java`, add the import (alphabetical, near the other `forge.gamemodes.limited.*` imports):

```java
import forge.gamemodes.limited.IBoosterDraft;
```

- [ ] **Step 2: Build decks for playable AI seats in `addFinishDraft`**

Replace the loop in `addFinishDraft` (lines 337-349) with:

```java
        for (int i = 0; i < players.size(); i++) {
            LimitedPlayer player = players.get(i);
            EventParticipant participant = EventParticipant.findBySeat(participants, i);
            if (participant == null) continue;

            if (player instanceof LimitedPlayerAI ai) {
                // Draft-filler seats (-1) don't play in the tournament — no deck needed.
                if (participant.getLobbySlotIndex() < 0) continue;

                // Playable bot: build a tournament deck from its drafted pool,
                // mirroring the sealed path (ServerGameLobby.generateAndDistributeSealedPools).
                if (ai.getDeck() == null || ai.getDeck().get(DeckSection.Sideboard) == null
                        || ai.getDeck().get(DeckSection.Sideboard).isEmpty()) {
                    netLog.warn("AI {} has empty drafted pool — skipping deck build",
                            participant.getName());
                    continue;
                }
                String landCode = IBoosterDraft.LAND_SET_CODE[0] != null
                        ? IBoosterDraft.LAND_SET_CODE[0].getCode() : null;
                Deck aiDeck = ai.buildDeck(landCode);
                NetworkEvent.setEventTags(aiDeck, event);
                participant.setDeck(aiDeck);
                netLog.info("Built tournament deck for AI {} ({} cards)",
                        participant.getName(), aiDeck.getMain().countAll());
                continue;
            }

            Deck pool = new Deck(player.getDeck(), NetworkEvent.poolNameFor(event));
            NetworkEvent.setEventTags(pool, event);
            int slot = participant.getLobbySlotIndex();
            dispatches.add(() -> FServerManager.getInstance().sendToSlot(slot,
                    new ReceiveEventPoolEvent(eventId, pool)));
        }
```

- [ ] **Step 3: Verify compile**

Run: `mvn -pl forge-gui-desktop -am compile -q`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add forge-gui/src/main/java/forge/gamemodes/net/draft/BoosterDraftHost.java
git commit -m "feat: build tournament decks for playable AI draft participants"
```

---

## Task 8: Minimum real players guard for the tournament

**Files:**
- Modify: `forge-gui/src/main/java/forge/gamemodes/net/server/ServerGameLobby.java`

- [ ] **Step 1: Add the guard in `startTournament`**

In `startTournament(int gamesPerMatch)` (lines 383-428), right after the existing `if (event == null) return;` (line 385), insert:

```java
        long realPlayers = ServerTournamentController.realParticipants(event.getParticipants()).size();
        if (realPlayers < 2) {
            netLog.warn("Cannot start tournament — need at least 2 real players, have {}", realPlayers);
            FServerManager.getInstance().broadcast(new MessageEvent(
                    "Cannot start tournament: need at least 2 real players."));
            return;
        }
```

- [ ] **Step 2: Verify compile**

Run: `mvn -pl forge-gui-desktop -am compile -q`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add forge-gui/src/main/java/forge/gamemodes/net/server/ServerGameLobby.java
git commit -m "feat: require at least 2 real players to start a tournament"
```

---

## Task 9: Host UI format selection + localizer keys

**Files:**
- Modify: `forge-gui-desktop/src/main/java/forge/screens/home/CLobby.java`
- Modify: `forge-gui/res/languages/en-US.properties`

- [ ] **Step 1: Add the `DraftOptions` import to `CLobby`**

`CLobby` already imports `forge.gamemodes.net.*` (covers `DraftStyle`) and `forge.gamemodes.limited.*` (covers `BoosterDraft`). Add `forge.card.DraftOptions` to the import block (alphabetically, after `forge.deck.DeckProxy`):

```java
import forge.card.DraftOptions;
```

- [ ] **Step 2: Prompt for the draft format in `openEventConfigDialog`**

In `openEventConfigDialog()` (lines 277-368), insert this block between the `NetworkEvent event = serverLobby.getCurrentEvent(); if (event == null) return;` lines (322-323) and the `int timerSeconds = ...` line (325):

```java
        // Step 3b: For draft, choose the draft format (pod size / pick rule).
        // The set's recommended double-pick mode pre-selects the default, but the
        // host's explicit choice always wins.
        DraftStyle draftStyle = DraftStyle.EIGHT_PLAYER_PICK_ONE;
        if (isDraft && draft != null) {
            String lbl8 = localizer.getMessage("lblNetworkDraftStyle8P1");
            String lbl4 = localizer.getMessage("lblNetworkDraftStyle4P2");
            boolean recommendPickTwo =
                    draft.getDoublePickDuringDraft() == DraftOptions.DoublePick.ALWAYS;
            String recTag = " (recommended)";
            String[] formatOptions = recommendPickTwo
                    ? new String[] { lbl4 + recTag, lbl8 }
                    : new String[] { lbl8 + recTag, lbl4 };
            String chosen = GuiChoose.oneOrNone(
                    localizer.getMessage("lblNetworkDraftFormatPrompt"), formatOptions);
            if (chosen == null) return;
            draftStyle = chosen.startsWith(lbl4)
                    ? DraftStyle.FOUR_PLAYER_PICK_TWO
                    : DraftStyle.EIGHT_PLAYER_PICK_ONE;
            event.setDraftStyle(draftStyle);
        }
```

- [ ] **Step 3: Add the localizer keys**

In `forge-gui/res/languages/en-US.properties`, add these keys right after `lblNetworkChooseDraftFormat` (line 3070):

```
lblNetworkDraftFormatPrompt=Choose draft format:
lblNetworkDraftStyle8P1=8-Player Pick One
lblNetworkDraftStyle4P2=4-Player Pick Two
```

(Other locale files fall back to English for missing keys; translating them is optional.)

- [ ] **Step 4: Verify compile**

Run: `mvn -pl forge-gui-desktop -am compile -q`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add forge-gui-desktop/src/main/java/forge/screens/home/CLobby.java forge-gui/res/languages/en-US.properties
git commit -m "feat: host picks draft format during online draft setup"
```

---

## Task 10: Manual end-to-end verification

This plan has no headless harness covering the full network draft flow (Gap 9 in `2026-08-16-tournament-current-state.md`); the pure decision logic is covered by Tasks 1-5. Verify the integrated behavior manually on desktop:

- [ ] **Step 1: 8-Player-Pick-One with 4 humans**

1. Host a lobby, set mode to Limited (Draft), open "Set Up Event" → Draft.
2. Choose the format `8-Player Pick One`.
3. Host + 3 remote players join (4 real). Start Draft.
4. Expect: pod shows 8 seats (4 humans + 4 AI fillers). Each player picks **1** card, then the pack passes to the next seat.
5. Build decks, then Start Tournament.
6. Expect: round robin of exactly **4** players, 3 rounds, 2 parallel matches per round; fillers never appear in pairings or standings.

- [ ] **Step 2: 4-Player-Pick-Two with 3 humans + 1 playable AI**

1. Host a lobby, set mode to Limited (Draft), open "Set Up Event" → Draft.
2. Add one AI slot so the lobby has 3 humans + 1 bot. Choose the format `4-Player Pick Two`.
3. Start Draft.
4. Expect: pod shows 4 seats (no fillers needed). Each player picks **2** cards from a pack — after the 1st pick the pack stays ("Pick 2" in the overlay), after the 2nd it passes to the next seat.
5. Build decks, then Start Tournament.
6. Expect: round robin of **4** players (including the bot), 3 rounds; the bot plays its built deck.

- [ ] **Step 3: Overfilled pod refusal**

1. In a 4P2 lobby, have 5 real players attempt to start the draft.
2. Expect: a chat message "Cannot start draft: FOUR_PLAYER_PICK_TWO supports up to 4 players." and the draft does not start.

- [ ] **Step 4: Set-recommendation default**

1. Choose a set whose `DraftOptions` recommend pod size 4 / double-pick (e.g. a set with `whenpodsizeis4`). The format prompt should list `4-Player Pick Two (recommended)` first.
2. Choose `8-Player Pick One` instead; the draft must run as an 8-seat, pick-one pod (host choice wins).

---

## Self-Review Notes

- **Spec coverage:** Two formats (Task 1/6/9), pick-2 wiring (Task 3), filler exclusion from tournament (Task 5), playable-AI decks (Task 7), overfill refusal (Task 6), set-recommendation default (Task 9), min real players (Task 8) — all decisions from the 2026-08-24 design discussion are covered.
- **Known follow-ups (out of scope):** draft tournament matches still run as `GameType.Constructed` (Gap 3) and WinLose detection is not tournament-aware (Gap 4); mobile online lobby remains desktop-only; no headless end-to-end draft harness (Gap 9).
