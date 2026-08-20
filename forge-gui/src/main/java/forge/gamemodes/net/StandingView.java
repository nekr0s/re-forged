package forge.gamemodes.net;

import java.io.Serializable;

/**
 * Wire-safe snapshot of a tournament standing for broadcast to clients.
 */
public record StandingView(
        String playerName,
        int wins,
        int losses,
        int byes,
        int score,
        String omwPercent) implements Serializable {
}
