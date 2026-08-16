package forge.gui.interfaces;

import forge.gamemodes.net.event.NetEvent;

public interface INetEventHandler {
    boolean dispatch(NetEvent event);
}
