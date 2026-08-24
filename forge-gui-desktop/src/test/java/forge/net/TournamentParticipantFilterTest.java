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
