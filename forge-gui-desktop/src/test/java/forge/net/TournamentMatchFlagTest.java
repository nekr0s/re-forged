package forge.net;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Gap 4: a GUI must carry a "this is a tournament match" flag so the WinLose
 * screen can distinguish tournament matches from ordinary network limited games.
 * The flag lives on the shared client GUI, is cleared when a new match's view
 * opens, and is set when a tournament match starts.
 */
public class TournamentMatchFlagTest {

    @Test
    public void testTournamentFlagDefaultsToFalse() {
        final HeadlessNetworkGuiGame gui = new HeadlessNetworkGuiGame();
        Assert.assertFalse(gui.isTournamentMatch(),
                "A fresh GUI must not be a tournament match");
    }

    @Test
    public void testTournamentFlagCanBeSetAndCleared() {
        final HeadlessNetworkGuiGame gui = new HeadlessNetworkGuiGame();
        gui.setTournamentMatch(true);
        Assert.assertTrue(gui.isTournamentMatch());
        gui.setTournamentMatch(false);
        Assert.assertFalse(gui.isTournamentMatch());
    }
}
