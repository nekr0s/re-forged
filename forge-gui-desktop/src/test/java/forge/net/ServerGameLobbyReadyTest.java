package forge.net;

import forge.gamemodes.match.LobbySlot;
import forge.gamemodes.match.LobbySlotType;
import forge.gamemodes.net.server.ServerGameLobby;
import forge.interfaces.IUpdateable;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * Regression test for the ready-checkbox desync (Gap 11): every ready change —
 * player-initiated or server-initiated — must route through the
 * {@code applyToSlot} funnel so the host's own screen refreshes in addition to
 * the network broadcast. {@link ServerGameLobby#setPlayerReady} is the
 * server-initiated entry point the tournament controller uses.
 */
public class ServerGameLobbyReadyTest {

    @BeforeClass
    public void setUp() {
        TestUtils.ensureFModelInitialized();
    }

    @Test
    public void testSetPlayerReadyRoutesThroughFunnelOnlyWhenChanged() {
        final ServerGameLobby lobby = new ServerGameLobby();
        final int[] hostRefreshes = {0};
        lobby.setListener(new IUpdateable() {
            @Override
            public void update(final boolean fullUpdate) {
                hostRefreshes[0]++;
            }
            @Override
            public void update(final int slot, final LobbySlotType type) {
                // deck-refresh half of the funnel; ready updates never hit this
            }
        });

        final LobbySlot host = lobby.getSlot(0);
        Assert.assertNotNull(host, "Host slot should exist");
        Assert.assertFalse(host.isReady(), "Host slot should start not-ready");

        // Server-initiated ready change must fire the funnel's host-refresh half
        // (the half the old direct slot.setIsReady(...) write skipped).
        lobby.setPlayerReady(0, true);
        Assert.assertTrue(host.isReady(), "setPlayerReady(true) should mark the slot ready");
        Assert.assertEquals(hostRefreshes[0], 1, "Host screen should be refreshed once on change");

        // An unchanged value must not re-trigger the funnel.
        lobby.setPlayerReady(0, true);
        Assert.assertEquals(hostRefreshes[0], 1, "No-op ready write should not re-refresh the host screen");

        // Between-round standby clears ready; that too must refresh the host screen.
        lobby.setPlayerReady(0, false);
        Assert.assertFalse(host.isReady(), "setPlayerReady(false) should clear ready");
        Assert.assertEquals(hostRefreshes[0], 2, "Host screen should be refreshed again on the way back down");
    }
}
