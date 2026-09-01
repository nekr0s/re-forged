package forge.net;

import forge.gamemodes.net.server.FServerManager;
import forge.gamemodes.net.server.RemoteClient;
import forge.gamemodes.net.server.RemoteClientGuiGame;
import forge.gamemodes.net.server.ServerGameLobby;
import io.netty.channel.embedded.EmbeddedChannel;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * Server-side spectate lifecycle: the pure guard helpers and the leave teardown.
 * The prefixed "spectate:" key must let the guards tell a spectator GUI entry
 * from a player's own-match GUI entry.
 */
public class SpectateServerTest {

    @BeforeClass
    public void setUp() {
        TestUtils.ensureFModelInitialized();
    }

    @Test
    public void inOwnMatchIsFalseForEmptyClient() {
        RemoteClient client = new RemoteClient(new EmbeddedChannel());
        Assert.assertFalse(FServerManager.isClientInOwnMatch(client),
                "a client with no match GUIs is not in a match");
    }

    @Test
    public void inOwnMatchIsTrueForBareKey() {
        RemoteClient client = new RemoteClient(new EmbeddedChannel());
        // A player's own match GUI is registered under the BARE matchId.
        new RemoteClientGuiGame(client, "match-1");
        Assert.assertTrue(FServerManager.isClientInOwnMatch(client),
                "a bare match-GUI key means the client is playing their own match");
    }

    @Test
    public void inOwnMatchIsTrueForDefaultKey() {
        RemoteClient client = new RemoteClient(new EmbeddedChannel());
        // A playing participant's own-match GUI is keyed "default" by the legacy
        // single-arg getGui path — the guard must treat that as "in a match".
        new RemoteClientGuiGame(client);
        Assert.assertTrue(FServerManager.isClientInOwnMatch(client),
                "the legacy 'default' key means the client is playing their own match");
    }

    @Test
    public void rekeyActiveMatchGuiMovesDefaultToMatchId() {
        RemoteClient client = new RemoteClient(new EmbeddedChannel());
        // Tournament match start creates the player GUI via the legacy path → "default".
        new RemoteClientGuiGame(client);
        Assert.assertEquals(client.getActiveMatchId(), "default");

        client.rekeyActiveMatchGui("match-9");
        Assert.assertNotNull(client.getMatchGui("match-9"));
        Assert.assertNull(client.getMatchGui("default"));
        Assert.assertEquals(client.getActiveMatchId(), "match-9");
        Assert.assertTrue(FServerManager.isClientInOwnMatch(client),
                "while their rekeyed match is running a participant is in a match");

        // clearPlayerGuis(matchId) at match end removes it, freeing the participant.
        client.removeMatchGui("match-9");
        Assert.assertFalse(FServerManager.isClientInOwnMatch(client),
                "after the match ends the participant is free to spectate");
    }

    @Test
    public void matchEndClearsDefaultGui() {
        RemoteClient client = new RemoteClient(new EmbeddedChannel());
        new RemoteClientGuiGame(client);
        Assert.assertTrue(FServerManager.isClientInOwnMatch(client));
        // onMatchOver(matchId) now clears the "default" entry for a finished participant.
        client.removeMatchGui("default");
        Assert.assertFalse(FServerManager.isClientInOwnMatch(client),
                "after a match ends the participant is free to spectate");
    }

    @Test
    public void inOwnMatchIsFalseForPrefixedKeyOnly() {
        RemoteClient client = new RemoteClient(new EmbeddedChannel());
        // A spectator GUI is registered under the PREFIXED key (mirroring
        // handleSpectateRequest, which removes the ctor's bare-key registration).
        RemoteClientGuiGame spec = new RemoteClientGuiGame(client, "match-1");
        client.removeMatchGui("match-1");
        client.setMatchGui("spectate:match-1", spec);
        Assert.assertFalse(FServerManager.isClientInOwnMatch(client),
                "a client that is only spectating is not in a match of their own");
    }

    @Test
    public void guardRejectsUnknownMatch() {
        RemoteClient client = new RemoteClient(new EmbeddedChannel());
        Assert.assertEquals(
                FServerManager.spectateProblemFor(client, "match-1", null),
                "no running match",
                "A missing HostedMatch must be rejected");
    }

    @Test
    public void leaveRemovesPrefixedMatchGui() {
        RemoteClient client = new RemoteClient(new EmbeddedChannel());
        RemoteClientGuiGame spec = new RemoteClientGuiGame(client, "match-1");
        client.removeMatchGui("match-1");
        client.setMatchGui("spectate:match-1", spec);
        client.setActiveMatchId("spectate:match-1");

        FServerManager server = FServerManager.getInstance();
        server.setLobby(new ServerGameLobby());
        server.handleSpectateLeave("match-1", client);

        Assert.assertNull(client.getMatchGui("spectate:match-1"),
                "leave must remove the prefixed spectator GUI");
        Assert.assertFalse(FServerManager.isClientInOwnMatch(client),
                "after leave the client has no match-GUI keys at all");
    }
}
