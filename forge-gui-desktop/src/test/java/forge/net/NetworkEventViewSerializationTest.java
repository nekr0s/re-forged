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
