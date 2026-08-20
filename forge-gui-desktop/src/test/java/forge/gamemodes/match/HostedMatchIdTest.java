package forge.gamemodes.match;

import org.testng.Assert;
import org.testng.annotations.Test;

public class HostedMatchIdTest {
    @Test
    public void testMatchIdIsUnique() {
        HostedMatch m1 = new HostedMatch();
        HostedMatch m2 = new HostedMatch();
        Assert.assertNotNull(m1.getMatchId(), "matchId should not be null");
        Assert.assertNotNull(m2.getMatchId(), "matchId should not be null");
        Assert.assertNotEquals(m1.getMatchId(), m2.getMatchId(), "matchIds should be unique");
    }

    @Test
    public void testMatchIdIsUuidFormat() {
        HostedMatch m = new HostedMatch();
        Assert.assertTrue(m.getMatchId().matches(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),
            "matchId should be UUID format");
    }
}
