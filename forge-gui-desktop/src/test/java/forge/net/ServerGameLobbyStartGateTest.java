package forge.net;

import forge.deck.Deck;
import forge.gamemodes.net.server.ServerGameLobby;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Gap 12: the tournament start gate. Ready/deck problems and deck-legality
 * problems must be collected so the host (and the lobby) get real feedback
 * instead of a silent no-op, and illegal decks are flagged before play.
 */
public class ServerGameLobbyStartGateTest {

    private static Deck deckOfLands(int count) {
        Deck d = new Deck("test");
        d.getMain().add("Forest", count);
        return d;
    }

    @Test
    public void testNotReadyIsAProblem() {
        Assert.assertEquals(ServerGameLobby.startProblemFor("Alice", false, deckOfLands(40)),
                "Alice is not ready");
    }

    @Test
    public void testMissingDeckIsAProblem() {
        Assert.assertEquals(ServerGameLobby.startProblemFor("Bob", true, null),
                "Bob has no deck");
    }

    @Test
    public void testEmptyMainIsAProblem() {
        Assert.assertEquals(ServerGameLobby.startProblemFor("Carol", true, new Deck("empty")),
                "Carol has not finished building their deck");
    }

    @Test
    public void testReadyWithDeckHasNoProblem() {
        Assert.assertNull(ServerGameLobby.startProblemFor("Diana", true, deckOfLands(40)));
    }

    @Test
    public void testIllegalDeckProducesLegalityProblem() {
        String problem = ServerGameLobby.legalityProblemFor("Eve", deckOfLands(10));
        Assert.assertNotNull(problem, "A 10-card deck is not legal in Limited");
        Assert.assertTrue(problem.startsWith("Eve:"), "Problem should be prefixed with the player name");
    }

    @Test
    public void testLegalDeckHasNoLegalityProblem() {
        Assert.assertNull(ServerGameLobby.legalityProblemFor("Frank", deckOfLands(40)));
    }
}
