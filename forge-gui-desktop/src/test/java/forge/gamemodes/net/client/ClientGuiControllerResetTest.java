package forge.gamemodes.net.client;

import forge.game.player.PlayerView;
import forge.net.HeadlessNetworkGuiGame;
import forge.trackable.Tracker;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;

/**
 * Regression test for the "opponent's hand tab appears" bug: the client shares one
 * {@code IGuiGame} for the whole session and PlayerView ids are the in-game seat index
 * (0/1), so a round-robin seat rotation (1 → 0) would previously leave the prior match's
 * seat registered alongside the current one. {@link AbstractGuiGame#getLocalPlayerCount()}
 * then equaled the player count and {@code CMatchUI} built a hand tab for every player.
 *
 * <p>The fix clears the shared gui's per-match controller state
 * ({@link AbstractGuiGame#resetForNewMatch()}) before re-registering, so only the current
 * match's seats count as local.
 */
public class ClientGuiControllerResetTest {

    @Test
    public void testSeatRotationDoesNotAccumulateLocalPlayers() {
        final HeadlessNetworkGuiGame gui = new HeadlessNetworkGuiGame();
        final FGameClient client = new FGameClient("player", gui, "127.0.0.1", 1);

        final Tracker tracker = new Tracker();
        final PlayerView seat1 = new PlayerView(1, tracker);
        final PlayerView seat0 = new PlayerView(0, tracker);

        // Match 1: the client sits in seat 1.
        client.setGameControllers(List.of(seat1));
        Assert.assertEquals(gui.getLocalPlayerCount(), 1);
        Assert.assertTrue(gui.isLocalPlayer(seat1));

        // Match 2: round-robin rotation flipped the client to seat 0.
        client.setGameControllers(List.of(seat0));
        Assert.assertEquals(gui.getLocalPlayerCount(), 1,
                "Stale controller from the previous seat must be cleared on re-registration");
        Assert.assertTrue(gui.isLocalPlayer(seat0));
        Assert.assertFalse(gui.isLocalPlayer(seat1));
    }
}
