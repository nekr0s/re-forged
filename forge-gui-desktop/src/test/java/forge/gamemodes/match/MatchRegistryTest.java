package forge.gamemodes.match;

import org.testng.Assert;
import org.testng.annotations.Test;

public class MatchRegistryTest {

    @Test
    public void testRegisterAndGet() {
        MatchRegistry registry = new MatchRegistry();
        HostedMatch match = new HostedMatch();
        registry.register(match);
        Assert.assertSame(registry.get(match.getMatchId()), match, "Registered match should be retrievable by matchId");
    }

    @Test
    public void testUnregister() {
        MatchRegistry registry = new MatchRegistry();
        HostedMatch match = new HostedMatch();
        registry.register(match);
        registry.unregister(match.getMatchId());
        Assert.assertNull(registry.get(match.getMatchId()), "Match should be removed after unregister");
    }

    @Test
    public void testIsEmpty() {
        MatchRegistry registry = new MatchRegistry();
        Assert.assertTrue(registry.isEmpty(), "New registry should be empty");
        HostedMatch match = new HostedMatch();
        registry.register(match);
        Assert.assertFalse(registry.isEmpty(), "Registry with a match should not be empty");
        registry.unregister(match.getMatchId());
        Assert.assertTrue(registry.isEmpty(), "Registry should be empty after unregister");
    }

    @Test
    public void testGetAll() {
        MatchRegistry registry = new MatchRegistry();
        HostedMatch m1 = new HostedMatch();
        HostedMatch m2 = new HostedMatch();
        registry.register(m1);
        registry.register(m2);
        Assert.assertEquals(registry.getAll().size(), 2, "getAll should return 2 matches");
    }

    @Test
    public void testHasActiveMatches() {
        MatchRegistry registry = new MatchRegistry();
        Assert.assertFalse(registry.hasActiveMatches());
        registry.register(new HostedMatch());
        Assert.assertTrue(registry.hasActiveMatches());
    }
}
