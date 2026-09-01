package forge.gamemodes.net.server;

import forge.gamemodes.net.CompatibleObjectDecoder;
import forge.gamemodes.net.CompatibleObjectEncoder;
import forge.gamemodes.net.ReplyPool;
import forge.trackable.Tracker;
import forge.gamemodes.net.event.IdentifiableNetEvent;
import forge.gamemodes.net.event.NetEvent;
import forge.util.IHasForgeLog;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;

import java.net.SocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class RemoteClient implements IToClient, IHasForgeLog {

    /** Special value indicating the client hasn't been assigned a slot yet. */
    public static final int UNASSIGNED_SLOT = -1;

    private volatile Channel channel;
    private String username;
    private int index = UNASSIGNED_SLOT;
    private boolean libgdx;
    private volatile ReplyPool defaultReplies = new ReplyPool();
    private final Map<String, ReplyPool> matchReplies = new ConcurrentHashMap<>();
    private volatile Tracker defaultCodecTracker;
    private volatile int defaultCodecConsumerId = -1;
    private final AtomicInteger sendErrors = new AtomicInteger(0);
    private final Map<String, RemoteClientGuiGame> matchGuis = new ConcurrentHashMap<>();
    private volatile String activeMatchId;

    // Package-private: SaturationLoggingHandler reads/resets these on writability transitions
    volatile long saturationStartMs = 0L;
    final AtomicInteger sendsDuringSaturation = new AtomicInteger(0);

    private void recordSendIfSaturated(final Channel ch) {
        if (!ch.isWritable()) {
            sendsDuringSaturation.incrementAndGet();
        }
    }

    public RemoteClient(final Channel channel) {
        this.channel = channel;
    }

    /**
     * Swap the underlying channel for a reconnecting client.
     * Updates the channel and re-applies the codec tracker to the new channel's
     * pipeline so IdRef resolution keeps working. Per-match ReplyPools and codec
     * trackers are preserved — reconnect should not lose match state.
     */
    public void swapChannel(final Channel newChannel) {
        this.channel = newChannel;
        // Keep existing ReplyPools and codec trackers — reconnect preserves match state
        applyCodecTracker(newChannel);
    }

    /**
     * Check if this client has been assigned a valid lobby slot.
     * @return true if the client has a valid slot (index >= 0)
     */
    public boolean hasValidSlot() {
        return index >= 0;
    }

    /** Remote peer address, for admission limits and logging. */
    public SocketAddress getRemoteAddress() {
        final Channel ch = channel;
        return ch == null ? null : ch.remoteAddress();
    }

    /** Encodes synchronously on the caller's thread. Returns null on failure (logged). */
    private ByteBuf encodeOnCallingThread(final NetEvent event) {
        final Channel ch = channel;
        final CompatibleObjectEncoder encoder = ch.pipeline().get(CompatibleObjectEncoder.class);
        if (encoder == null) {
            netLog.error("No encoder in pipeline for {} (event: {})", username, event);
            sendErrors.incrementAndGet();
            return null;
        }
        try {
            return encoder.encodeToBuf(event, ch.alloc());
        } catch (Exception e) {
            sendErrors.incrementAndGet();
            netLog.error(e, "Network encode error for {} (event: {})", username, event);
            return null;
        }
    }

    @Override
    public void send(final NetEvent event) {
        final Channel ch = channel;
        recordSendIfSaturated(ch);
        final ByteBuf encoded = encodeOnCallingThread(event);
        if (encoded == null) return;
        ch.writeAndFlush(encoded).addListener(f -> {
            if (!f.isSuccess()) {
                sendErrors.incrementAndGet();
                Throwable c = f.cause();
                if (c != null) {
                    netLog.error(c, "Network send error for {} (event: {})", username, event);
                } else {
                    netLog.error("Network send error for {} (event: {}, cause: {})",
                            username, event, f.isCancelled() ? "cancelled" : "no cause");
                }
            }
        });
    }

    @Override
    public void write(final NetEvent event) {
        final Channel ch = channel;
        recordSendIfSaturated(ch);
        final ByteBuf encoded = encodeOnCallingThread(event);
        if (encoded == null) return;
        ch.write(encoded).addListener(f -> {
            if (!f.isSuccess()) {
                sendErrors.incrementAndGet();
                Throwable c = f.cause();
                if (c != null) {
                    netLog.error(c, "Network write error for {} (event: {})", username, event);
                } else {
                    netLog.error("Network write error for {} (event: {}, cause: {})",
                            username, event, f.isCancelled() ? "cancelled" : "no cause");
                }
            }
        });
    }

    @Override
    public Object sendAndWait(final IdentifiableNetEvent event) {
        ReplyPool replies = getReplyPool();
        replies.initialize(event.getId());
        final Channel ch = channel;
        recordSendIfSaturated(ch);
        final ByteBuf encoded = encodeOnCallingThread(event);
        if (encoded == null) {
            replies.complete(event.getId(), null);
            return replies.get(event.getId());
        }
        ch.writeAndFlush(encoded).addListener(f -> {
            if (!f.isSuccess()) {
                sendErrors.incrementAndGet();
                Throwable c = f.cause();
                if (c != null) {
                    netLog.error(c, "sendAndWait write failed for {} (event: {})", username, event);
                } else {
                    netLog.error("sendAndWait write failed for {} (event: {}, cause: {})",
                            username, event, f.isCancelled() ? "cancelled" : "no cause");
                }
                replies.complete(event.getId(), null);
            }
        });
        return replies.get(event.getId());
    }

    public String getUsername() {
        return username;
    }
    public void setUsername(final String username) {
        this.username = username;
    }

    public int getIndex() {
        return index;
    }
    public void setIndex(final int index) {
        this.index = index;
    }

    public boolean isLibgdx() {
        return libgdx;
    }
    public void setLibgdx(final boolean libgdx) {
        this.libgdx = libgdx;
    }

    // Backward compat: returns the active match's GUI (or null)
    public RemoteClientGuiGame getGui() {
        return activeMatchId != null ? matchGuis.get(activeMatchId) : null;
    }

    // Backward compat for code that calls setGui(null) to clear
    void setGui(final RemoteClientGuiGame gui) {
        if (gui == null) {
            if (activeMatchId != null) {
                matchGuis.remove(activeMatchId);
            }
        } else {
            // Legacy path — assign to active match or a default key
            if (activeMatchId == null) {
                activeMatchId = "default";
            }
            matchGuis.put(activeMatchId, gui);
        }
    }

    // New multi-match API
    public RemoteClientGuiGame getMatchGui(final String matchId) {
        return matchGuis.get(matchId);
    }

    /**
     * Snapshot of the current match-GUI keys. Returns a copy so callers may
     * iterate while removing entries (e.g. the spectate auto-leave loop).
     */
    public java.util.Set<String> getMatchGuiKeys() {
        return new java.util.HashSet<>(matchGuis.keySet());
    }

    public void setMatchGui(final String matchId, final RemoteClientGuiGame gui) {
        matchGuis.put(matchId, gui);
    }

    /**
     * Move the client's active match GUI to a real matchId key. Tournament matches
     * create the player GUI via the legacy {@code setGui} path (keyed "default"),
     * but the scoped {@code clearPlayerGuis(matchId)} can only clean up a matchId-
     * keyed entry — so the tournament controller rekeys it once the match exists.
     */
    public void rekeyActiveMatchGui(final String matchId) {
        final String current = activeMatchId;
        final RemoteClientGuiGame gui = current != null ? matchGuis.get(current) : null;
        if (gui == null) {
            return;
        }
        matchGuis.remove(current);
        matchGuis.put(matchId, gui);
        activeMatchId = matchId;
    }

    public void removeMatchGui(final String matchId) {
        matchGuis.remove(matchId);
        matchReplies.remove(matchId);
        if (activeMatchId != null && activeMatchId.equals(matchId)) {
            activeMatchId = matchGuis.isEmpty() ? null : matchGuis.keySet().iterator().next();
        }
    }

    public void clearAllMatchGuis() {
        matchGuis.clear();
        matchReplies.clear();
        activeMatchId = null;
    }

    public RemoteClientGuiGame getActiveMatchGui() {
        return activeMatchId != null ? matchGuis.get(activeMatchId) : null;
    }

    public void setActiveMatchId(final String matchId) {
        activeMatchId = matchId;
    }

    public String getActiveMatchId() {
        return activeMatchId;
    }

    /**
     * Set the tracker and per-client consumerId on the channel's encoder and
     * decoder. Called when the game starts (before any client protocol
     * messages arrive). Cached so that {@link #swapChannel} can re-apply
     * after a reconnect. The consumerId is the {@code DeltaSyncManager} id
     * for this client; the encoder uses it to gate IdRef substitution to
     * objects this client has actually been told about.
     */
    public void setCodecTracker(Tracker tracker, int consumerId) {
        // Skip no-op rebinds: setGameView fires on every view push, the tracker changes per game.
        if (tracker == defaultCodecTracker && consumerId == defaultCodecConsumerId) {
            return;
        }
        this.defaultCodecTracker = tracker;
        this.defaultCodecConsumerId = consumerId;
        applyCodecTracker(channel);
    }

    private void applyCodecTracker(Channel ch) {
        if (defaultCodecTracker == null || ch == null) {
            return;
        }
        // Swap on the event loop so it lands between decoded frames: a message the
        // client sent against the previous game must not resolve against the new one.
        if (!ch.eventLoop().inEventLoop()) {
            ch.eventLoop().execute(() -> applyCodecTracker(ch));
            return;
        }
        CompatibleObjectEncoder encoder = ch.pipeline().get(CompatibleObjectEncoder.class);
        if (encoder != null) {
            encoder.setTracker(defaultCodecTracker);
            encoder.setConsumerId(defaultCodecConsumerId);
        }
        CompatibleObjectDecoder decoder = ch.pipeline().get(CompatibleObjectDecoder.class);
        if (decoder != null) {
            decoder.setTracker(defaultCodecTracker);
        }
    }

    public Tracker getCodecTracker() {
        return defaultCodecTracker;
    }

    public int getCodecConsumerId() {
        return defaultCodecConsumerId;
    }

    public int getSendErrorCount() {
        return sendErrors.get();
    }

    ReplyPool getReplyPool() {
        return activeMatchId != null
            ? matchReplies.computeIfAbsent(activeMatchId, k -> new ReplyPool())
            : defaultReplies;
    }

    ReplyPool getReplyPool(final String matchId) {
        if (matchId == null) {
            return defaultReplies;
        }
        return matchReplies.computeIfAbsent(matchId, k -> new ReplyPool());
    }

    void removeReplyPool(final String matchId) {
        if (matchId != null) {
            matchReplies.remove(matchId);
        }
    }
}
