package forge.net;

import forge.game.GameType;
import forge.gamemodes.net.EventFormat;
import forge.gamemodes.net.server.ServerTournamentController;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Gaps 2/3: tournament match setup. Draft events must run as {@code GameType.Draft}
 * (not Constructed), sealed events as {@code GameType.Sealed}, so the limited
 * format's deck semantics and WinLose handling apply.
 */
public class ServerTournamentControllerTest {

    @Test
    public void testSealedEventUsesSealedGameType() {
        Assert.assertEquals(ServerTournamentController.gameTypeFor(EventFormat.SEALED), GameType.Sealed);
    }

    @Test
    public void testDraftEventUsesDraftGameType() {
        Assert.assertEquals(ServerTournamentController.gameTypeFor(EventFormat.BOOSTER_DRAFT), GameType.Draft);
    }

    @Test
    public void testOtherFormatFallsBackToConstructed() {
        Assert.assertEquals(ServerTournamentController.gameTypeFor(null), GameType.Constructed);
    }
}
