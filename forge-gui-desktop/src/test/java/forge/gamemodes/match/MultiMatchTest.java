package forge.gamemodes.match;

import forge.gamemodes.net.event.GuiGameEvent;
import forge.gamemodes.net.ProtocolMethod;
import forge.gamemodes.net.GameProtocolSender;
import forge.gamemodes.net.server.RemoteClient;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Integration test: verify multi-match infrastructure works correctly.
 */
public class MultiMatchTest {

    @Test
    public void testMatchRegistryHoldsTwoMatches() {
        MatchRegistry registry = new MatchRegistry();
        HostedMatch m1 = new HostedMatch();
        HostedMatch m2 = new HostedMatch();
        registry.register(m1);
        registry.register(m2);
        Assert.assertEquals(registry.size(), 2, "Registry should hold 2 matches");
        Assert.assertNotNull(registry.get(m1.getMatchId()), "Match 1 should be retrievable");
        Assert.assertNotNull(registry.get(m2.getMatchId()), "Match 2 should be retrievable");
        Assert.assertNotEquals(m1.getMatchId(), m2.getMatchId(), "Match IDs must differ");
    }

    @Test
    public void testScopedUnregisterKeepsOtherMatch() {
        MatchRegistry registry = new MatchRegistry();
        HostedMatch m1 = new HostedMatch();
        HostedMatch m2 = new HostedMatch();
        registry.register(m1);
        registry.register(m2);
        registry.unregister(m1.getMatchId());
        Assert.assertEquals(registry.size(), 1, "Only 1 match should remain");
        Assert.assertNull(registry.get(m1.getMatchId()), "Match 1 should be gone");
        Assert.assertNotNull(registry.get(m2.getMatchId()), "Match 2 should still be registered");
    }

    @Test
    public void testGuiGameEventCarriesMatchId() {
        GuiGameEvent event = new GuiGameEvent(ProtocolMethod.passPriority, "match-123");
        Assert.assertEquals(event.getMatchId(), "match-123",
            "GuiGameEvent should carry matchId");

        GuiGameEvent legacyEvent = new GuiGameEvent(ProtocolMethod.passPriority);
        Assert.assertNull(legacyEvent.getMatchId(),
            "Legacy GuiGameEvent (no matchId) should have null matchId");
    }

    @Test
    public void testRemoteClientMultiMatchGuis() {
        // RemoteClient is constructed with a null channel in tests
        RemoteClient client = new RemoteClient(null);
        Assert.assertNull(client.getActiveMatchGui(),
            "New client should have no active match GUI");

        // Setting active match ID doesn't create a GUI
        client.setActiveMatchId("match-A");
        Assert.assertEquals(client.getActiveMatchId(), "match-A");

        // Clearing all match GUIs is safe even when empty
        client.clearAllMatchGuis();
        Assert.assertNull(client.getActiveMatchGui());
    }

    @Test
    public void testGameProtocolSenderConstruction() {
        // Verify GameProtocolSender can be constructed with matchId
        GameProtocolSender sender = new GameProtocolSender(null, "match-456");
        Assert.assertNotNull(sender);
    }
}
