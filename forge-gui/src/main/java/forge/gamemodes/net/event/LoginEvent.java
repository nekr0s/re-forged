package forge.gamemodes.net.event;

public class LoginEvent implements NetEvent {
    private static final long serialVersionUID = -8865183377417377938L;

    private final String username;
    private final int avatarIndex, sleeveIndex;
    private final String matKey;
    private final String version;
    private final boolean libgdx;
    public LoginEvent(final String username, final int avatarIndex, final int sleeveIndex, final String matKey, final String version, final boolean libgdx) {
        this.username = username;
        this.avatarIndex = avatarIndex;
        this.sleeveIndex = sleeveIndex;
        this.matKey = matKey;
        this.version = version;
        this.libgdx = libgdx;
    }

    public String getUsername() {
        return username;
    }

    public int getAvatarIndex() {
        return avatarIndex;
    }

    public int getSleeveIndex() {
        return sleeveIndex;
    }

    /** The joining player's chosen play mat, so the lobby shows it before they touch anything. */
    public String getMatKey() {
        return matKey;
    }

    public String getVersion() {
        return version;
    }

    public boolean isLibgdx() {
        return libgdx;
    }
}
