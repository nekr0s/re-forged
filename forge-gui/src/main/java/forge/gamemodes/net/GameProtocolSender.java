package forge.gamemodes.net;

import forge.gamemodes.net.event.GuiGameEvent;

public final class GameProtocolSender {

    private final IRemote remote;
    private final String matchId;

    public GameProtocolSender(final IRemote remote) {
        this(remote, null);
    }

    public GameProtocolSender(final IRemote remote, final String matchId) {
        this.remote = remote;
        this.matchId = matchId;
    }

    public void send(final ProtocolMethod method, final Object... args) {
        method.checkArgs(args);
        remote.send(new GuiGameEvent(method, matchId, args));
    }

    public void write(final ProtocolMethod method, final Object... args) {
        method.checkArgs(args);
        remote.write(new GuiGameEvent(method, matchId, args));
    }

    @SuppressWarnings("unchecked")
    public <T> T sendAndWait(final ProtocolMethod method, final Object... args) {
        method.checkArgs(args);
        final Object returned = remote.sendAndWait(new GuiGameEvent(method, matchId, args));
        method.checkReturnValue(returned);
        return (T) returned;
    }
}
