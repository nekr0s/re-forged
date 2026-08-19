package forge.net;

import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.gamemodes.match.GameLobby.GameLobbyData;
import forge.gamemodes.net.EventFormat;
import forge.gamemodes.net.EventParticipant;
import forge.gamemodes.net.EventPhase;
import forge.gamemodes.net.NetworkEventView;
import forge.gamemodes.net.client.ClientGameLobby;
import forge.gamemodes.net.event.LobbyUpdateEvent;
import forge.gamemodes.tournament.system.TournamentPlayer;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;

/**
 * Regression test for Bug 2: after the tournament starts, every LobbyUpdateEvent
 * to clients must still serialize. Previously EventParticipant carried a
 * non-serializable TournamentPlayer (and the deck), so the event threw
 * NotSerializableException and was silently dropped — clients never received the
 * ready reset and every ready checkbox stayed checked.
 *
 * <p>Both {@code tournamentPlayer} and {@code deck} are server-only and must be
 * {@code transient}: they must survive on the server (live object) but never
 * travel inside the serialized NetworkEventView.
 */
public class LobbyUpdateSerializationTest {

    @BeforeClass
    public void setUp() {
        TestUtils.ensureFModelInitialized();
    }

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
    public void testLobbyUpdateEventSerializesAfterTournamentStart() throws Exception {
        EventParticipant p = new EventParticipant("Alice", EventParticipant.Type.HUMAN, 0, 0);
        // These are exactly what ServerTournamentController sets once the tournament starts.
        p.setTournamentPlayer(new TournamentPlayer(new LobbyPlayerAi("Alice", null), 0));
        p.setDeck(new Deck());

        NetworkEventView view = new NetworkEventView("event-1", EventFormat.SEALED,
                EventPhase.TOURNAMENT_IN_PROGRESS, List.of(p), 0, "product", 0);

        ClientGameLobby lobby = new ClientGameLobby();
        lobby.getData().setEventView(view);
        GameLobbyData data = lobby.getData();

        // Must not throw NotSerializableException (this is what broke lobby updates).
        LobbyUpdateEvent decoded = (LobbyUpdateEvent) roundTrip(new LobbyUpdateEvent(data));

        EventParticipant received = decoded.getState().getEventView().getParticipants().get(0);
        Assert.assertEquals(received.getName(), "Alice");
        // Server-only fields must not travel to clients.
        Assert.assertNull(received.getTournamentPlayer(),
                "tournamentPlayer must be transient (server-only)");
        Assert.assertNull(received.getDeck(),
                "deck must be transient (server-only)");
    }

    @Test
    public void testNetworkEventViewRoundTripsWithoutEngineObjects() throws Exception {
        EventParticipant p = new EventParticipant("Bob", EventParticipant.Type.AI, 1, -1);
        p.setTournamentPlayer(new TournamentPlayer(new LobbyPlayerAi("Bob", null), 1));

        NetworkEventView view = new NetworkEventView("event-1", EventFormat.SEALED,
                EventPhase.TOURNAMENT_IN_PROGRESS, List.of(p), 0, "product", 0);

        NetworkEventView decoded = (NetworkEventView) roundTrip(view);
        Assert.assertEquals(decoded.getParticipants().get(0).getName(), "Bob");
        Assert.assertNull(decoded.getParticipants().get(0).getTournamentPlayer());
    }
}
