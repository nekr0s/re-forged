package forge.net;

import forge.gamemodes.net.PairingView;
import forge.gamemodes.net.RoundState;
import forge.gamemodes.net.StandingView;
import forge.gamemodes.net.event.TournamentStartEvent;
import forge.gamemodes.net.event.TournamentUpdateEvent;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Arrays;
import java.util.List;

/**
 * Wire-safety regression for the tournament events (Gap 1): the events that
 * travel to remote clients must serialize with plain Java serialization and
 * round-trip intact. Previously {@code TournamentStartEvent} carried the whole
 * {@code TournamentRoundRobin} engine object and threw
 * {@code NotSerializableException}, silently dropping the event.
 */
public class TournamentUpdateEventSerializationTest {

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
    public void testTournamentUpdateEventRoundTrips() throws Exception {
        PairingView ongoing = new PairingView("Alice", "Bob", "match-1",
                PairingView.PairingStatus.ONGOING, null);
        PairingView bye = new PairingView("Charlie", "?", null,
                PairingView.PairingStatus.BYE, null);
        StandingView standing = new StandingView("Alice", 1, 0, 0, 3, "67%");

        TournamentUpdateEvent event = new TournamentUpdateEvent(
                "event-42", 2, 3, RoundState.ACTIVE,
                Arrays.asList(ongoing, bye),
                List.of(standing));

        TournamentUpdateEvent decoded = (TournamentUpdateEvent) roundTrip(event);

        Assert.assertEquals(decoded.getEventId(), "event-42");
        Assert.assertEquals(decoded.getRound(), 2);
        Assert.assertEquals(decoded.getTotalRounds(), 3);
        Assert.assertEquals(decoded.getRoundState(), RoundState.ACTIVE);
        Assert.assertEquals(decoded.getPairings().size(), 2);
        Assert.assertEquals(decoded.getPairings().get(0).playerAName(), "Alice");
        Assert.assertEquals(decoded.getPairings().get(0).matchId(), "match-1");
        Assert.assertEquals(decoded.getPairings().get(0).status(), PairingView.PairingStatus.ONGOING);
        Assert.assertEquals(decoded.getPairings().get(1).status(), PairingView.PairingStatus.BYE);
        Assert.assertEquals(decoded.getStandings().size(), 1);
        Assert.assertEquals(decoded.getStandings().get(0).omwPercent(), "67%");
    }

    @Test
    public void testTournamentStartEventRoundTrips() throws Exception {
        TournamentStartEvent event = new TournamentStartEvent(
                "event-42", Arrays.asList("Alice", "Bob", "Charlie"), 3);

        TournamentStartEvent decoded = (TournamentStartEvent) roundTrip(event);

        Assert.assertEquals(decoded.getEventId(), "event-42");
        Assert.assertEquals(decoded.getPlayerNames(), Arrays.asList("Alice", "Bob", "Charlie"));
        Assert.assertEquals(decoded.getTotalRounds(), 3);
    }
}
