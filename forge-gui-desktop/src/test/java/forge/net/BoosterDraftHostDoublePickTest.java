package forge.net;

import forge.card.DraftOptions;
import forge.gamemodes.net.draft.BoosterDraftHost;
import org.testng.Assert;
import org.testng.annotations.Test;

public class BoosterDraftHostDoublePickTest {

    @Test
    public void pickOneNeverKeepsPack() {
        for (int taken = 0; taken < 5; taken++) {
            Assert.assertFalse(BoosterDraftHost.keepPackForDoublePick(
                    DraftOptions.DoublePick.NEVER, taken));
        }
    }

    @Test
    public void pickTwoKeepsPackOnEvenPicksTaken() {
        // picksTaken is the number of cards already removed from the pack before
        // this pick (0-based pick index). Even index -> 1st of a pair -> keep.
        Assert.assertTrue(BoosterDraftHost.keepPackForDoublePick(DraftOptions.DoublePick.ALWAYS, 0));
        Assert.assertFalse(BoosterDraftHost.keepPackForDoublePick(DraftOptions.DoublePick.ALWAYS, 1));
        Assert.assertTrue(BoosterDraftHost.keepPackForDoublePick(DraftOptions.DoublePick.ALWAYS, 2));
        Assert.assertFalse(BoosterDraftHost.keepPackForDoublePick(DraftOptions.DoublePick.ALWAYS, 3));
    }

    @Test
    public void pickTwoFirstPickOptionIsNotKeptByHelper() {
        // The network host only uses NEVER (8P1) and ALWAYS (4P2); FIRST_PICK is
        // not a supported online format, so the helper returns false.
        Assert.assertFalse(BoosterDraftHost.keepPackForDoublePick(DraftOptions.DoublePick.FIRST_PICK, 0));
    }

    @Test
    public void passDirectionAlternatesByPackNumber() {
        Assert.assertEquals(BoosterDraftHost.nextSeat(0, 4, 1), 1); // odd pack  -> right
        Assert.assertEquals(BoosterDraftHost.nextSeat(0, 4, 2), 3); // even pack -> left
        Assert.assertEquals(BoosterDraftHost.nextSeat(3, 4, 1), 0); // wraps around
        Assert.assertEquals(BoosterDraftHost.nextSeat(1, 8, 2), 0);
    }
}
