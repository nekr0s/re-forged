package forge.net;

import forge.card.DraftOptions;
import forge.gamemodes.net.DraftStyle;
import org.testng.Assert;
import org.testng.annotations.Test;

public class DraftStyleTest {

    @Test
    public void eightPlayerPickOne() {
        Assert.assertEquals(DraftStyle.EIGHT_PLAYER_PICK_ONE.podSize(), 8);
        Assert.assertEquals(DraftStyle.EIGHT_PLAYER_PICK_ONE.doublePick(), DraftOptions.DoublePick.NEVER);
        Assert.assertFalse(DraftStyle.EIGHT_PLAYER_PICK_ONE.isDoublePick());
    }

    @Test
    public void fourPlayerPickTwo() {
        Assert.assertEquals(DraftStyle.FOUR_PLAYER_PICK_TWO.podSize(), 4);
        Assert.assertEquals(DraftStyle.FOUR_PLAYER_PICK_TWO.doublePick(), DraftOptions.DoublePick.ALWAYS);
        Assert.assertTrue(DraftStyle.FOUR_PLAYER_PICK_TWO.isDoublePick());
    }
}
