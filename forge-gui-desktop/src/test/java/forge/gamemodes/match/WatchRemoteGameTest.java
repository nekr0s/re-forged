package forge.gamemodes.match;

import forge.gamemodes.net.server.WatchRemoteGame;
import forge.player.PlayerControllerHuman;
import org.testng.Assert;
import org.testng.annotations.Test;

public class WatchRemoteGameTest {

    @Test
    public void testWatchRemoteGameExtendsPlayerControllerHuman() {
        Assert.assertTrue(PlayerControllerHuman.class.isAssignableFrom(WatchRemoteGame.class),
            "WatchRemoteGame should extend PlayerControllerHuman");
    }
}
