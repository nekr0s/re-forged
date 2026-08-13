package forge.gamemodes.net;

import java.io.Serializable;

/**
 * Wire-safe snapshot of a tournament pairing for broadcast to clients.
 */
public record PairingView(
        String playerAName,
        String playerBName,
        String matchId,
        PairingStatus status,
        String winnerName) implements Serializable {

    public enum PairingStatus {
        ONGOING,
        COMPLETE,
        BYE
    }
}
