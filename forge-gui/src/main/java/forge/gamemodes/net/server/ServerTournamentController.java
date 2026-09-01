package forge.gamemodes.net.server;

import forge.LobbyPlayer;
import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.GameType;
import forge.game.player.RegisteredPlayer;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.match.LobbySlot;
import forge.gamemodes.net.*;
import forge.gamemodes.net.event.*;
import forge.gamemodes.tournament.system.TournamentPairing;
import forge.gamemodes.tournament.system.TournamentPlayer;
import forge.gamemodes.tournament.system.TournamentRoundRobin;
import forge.player.GamePlayerUtil;
import forge.util.IHasForgeLog;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class ServerTournamentController implements IHasForgeLog {
    private static final long POLL_INTERVAL_MS = 500L;

    private final ServerGameLobby lobby;
    private final NetworkEvent event;
    private final TournamentRoundRobin tournament;
    private final FServerManager server;
    private final int gamesPerMatch;

    private final Map<String, HostedMatch> trackedMatches = new ConcurrentHashMap<>();
    private final Map<String, TournamentPairing> matchToPairing = new ConcurrentHashMap<>();
    private final Map<TournamentPairing, String> pairingToMatchId = new ConcurrentHashMap<>();
    private ScheduledFuture<?> pollTask;
    private boolean inStandby = false;

    /**
     * Participants that take part in the tournament: real seated players and
     * host-added playable bots ({@code lobbySlotIndex >= 0}). Draft-pod padding
     * AI fillers ({@code lobbySlotIndex == -1}) only draft and pass packs; they
     * never play tournament matches.
     */
    public static List<EventParticipant> realParticipants(List<EventParticipant> participants) {
        List<EventParticipant> out = new ArrayList<>();
        for (EventParticipant ep : participants) {
            if (ep.getLobbySlotIndex() >= 0) {
                out.add(ep);
            }
        }
        return out;
    }

    public ServerTournamentController(ServerGameLobby lobby, NetworkEvent event, int gamesPerMatch) {
        this.lobby = lobby;
        this.event = event;
        this.server = FServerManager.getInstance();
        this.gamesPerMatch = gamesPerMatch;

        List<TournamentPlayer> players = new ArrayList<>();
        for (EventParticipant ep : realParticipants(event.getParticipants())) {
            LobbyPlayer lobbyPlayer;
            if (ep.isHuman()) {
                lobbyPlayer = GamePlayerUtil.getGuiPlayer(ep.getName(), -1, -1, false);
            } else {
                lobbyPlayer = new LobbyPlayerAi(ep.getName(), null);
            }
            TournamentPlayer tp = new TournamentPlayer(lobbyPlayer, ep.getSeatIndex());
            ep.setTournamentPlayer(tp);
            players.add(tp);
        }

        this.tournament = new TournamentRoundRobin(players);

        netLog.info("[Tournament] Controller created — players={}, rounds={}", players.size(), tournament.getTotalRounds());
    }

    public TournamentRoundRobin getTournament() {
        return tournament;
    }

    public synchronized void startTournament() {
        event.setPhase(EventPhase.TOURNAMENT_IN_PROGRESS);
        netLog.info("[Tournament] Tournament started — round 1 of {}", tournament.getTotalRounds());
        List<String> playerNames = realParticipants(event.getParticipants()).stream()
                .map(EventParticipant::getName).collect(Collectors.toList());
        lobby.broadcastTournamentEvent(new TournamentStartEvent(event.getEventId(), playerNames, tournament.getTotalRounds()));
        startRoundMatches();
        server.updateLobbyState();
    }

    private synchronized void startRoundMatches() {
        startRoundMatchesInternal();
        if (tournament.isTournamentOver()) {
            // onTournamentComplete already broadcast the final standings
        } else {
            broadcastUpdate(RoundState.ACTIVE);
        }
    }

    private void startRoundMatchesInternal() {
        netLog.info("[Tournament] Starting round {} matches", tournament.getActiveRound());
        for (TournamentPairing pairing : new ArrayList<>(tournament.getActivePairings())) {
            if (pairing.isBye()) {
                handleBye(pairing);
            } else {
                startMatchForPairing(pairing);
            }
        }

        if (trackedMatches.isEmpty()) {
            if (tournament.isTournamentOver()) {
                onTournamentComplete();
            } else {
                startRoundMatchesInternal();
            }
        } else {
            startPolling();
        }
    }

    private void handleBye(TournamentPairing pairing) {
        TournamentPlayer byePlayer = pairing.getPairedPlayers().get(0);
        pairing.setWinner(byePlayer);
        byePlayer.addBye();
        netLog.info("[Tournament] Bye awarded to {} in round {}", byePlayer.getPlayer().getName(), tournament.getActiveRound());
        tournament.reportMatchCompletion(pairing);
    }

    private void startMatchForPairing(TournamentPairing pairing) {
        List<TournamentPlayer> pairedPlayers = pairing.getPairedPlayers();
        List<Integer> slotIndices = new ArrayList<>();
        boolean hasHuman = false;

        for (TournamentPlayer tp : pairedPlayers) {
            EventParticipant ep = findParticipant(tp);
            if (ep == null || ep.getLobbySlotIndex() < 0) continue;

            if (ep.isHuman()) {
                hasHuman = true;
            }

            LobbySlot slot = lobby.getSlot(ep.getLobbySlotIndex());
            if (slot == null) continue;

            // The slot is the single source of truth for the deck. AI participants carry
            // their auto-built deck on the participant; humans uploaded theirs to the slot
            // during deck selection. Either way, make sure the slot has it before the match.
            Deck deck = ep.getDeck() != null ? ep.getDeck() : slot.getDeck();
            if (deck != null) {
                slot.setDeck(deck);
            }
            netLog.info("[Tournament] Pairing deck for slot {} ({}) — deck='{}' main={} side={}",
                    ep.getLobbySlotIndex(), ep.getName(),
                    deck == null ? "null" : deck.getName(),
                    deck == null || deck.getMain() == null ? -1 : deck.getMain().countAll(),
                    deck == null || deck.get(DeckSection.Sideboard) == null ? -1 : deck.get(DeckSection.Sideboard).countAll());
            slotIndices.add(ep.getLobbySlotIndex());
        }

        if (slotIndices.size() < 2) {
            netLog.warn("[Tournament] Could not start match for pairing {} — not enough slots", formatPairing(pairing));
            return;
        }

        GameType gameType = gameTypeFor(event.getFormat());

        netLog.info("[Tournament] Starting match: {})", formatPairing(pairing));

        Runnable starter = lobby.startMatch(slotIndices, gameType, EnumSet.noneOf(GameType.class), hasHuman);
        if (starter == null) {
            netLog.warn("[Tournament] lobby.startMatch returned null for pairing {}", formatPairing(pairing));
            return;
        }

        starter.run();

        HostedMatch match = findNewMatch();
        if (match != null) {
            String matchId = match.getMatchId();
            trackedMatches.put(matchId, match);
            matchToPairing.put(matchId, pairing);
            pairingToMatchId.put(pairing, matchId);

            // Gap 2: best-of-N is tournament-scoped, not the global UI_MATCHES_PER_GAME default.
            if (match.getMatch() != null) {
                match.getMatch().getRules().setGamesPerMatch(gamesPerMatch);
                netLog.info("[Tournament] Match {} set to best-of-{}", matchId, gamesPerMatch);
            }
            // Gap 4: mark the match so the host's WinLose screen picks the tournament controller.
            match.setTournamentMatch(true);

            // Key each remote participant's own-match GUI under this match's real id so
            // clearPlayerGuis(matchId) removes it at match end and the spectate guard sees
            // "in own match" only while the match is actually running (not a lingering
            // legacy "default" entry).
            for (final TournamentPlayer tp : pairing.getPairedPlayers()) {
                final EventParticipant ep = findParticipant(tp);
                if (ep == null) {
                    continue;
                }
                final RemoteClient client = server.findClientByIndex(ep.getLobbySlotIndex());
                if (client != null) {
                    client.rekeyActiveMatchGui(matchId);
                }
            }

            netLog.info("[Tournament] Match started — matchId={}, round={}", matchId, tournament.getActiveRound());

            lobby.broadcastTournamentEvent(
                    new MatchStartedEvent(
                            matchId,
                            pairedPlayers.get(0).getPlayer().getName(),
                            pairedPlayers.get(1).getPlayer().getName(),
                            tournament.getActiveRound()));
            server.broadcast(new MessageEvent("Tournament round " + tournament.getActiveRound() + ": " + formatPairing(pairing)));
        } else {
            netLog.warn("[Tournament] Could not find started HostedMatch for pairing {}", formatPairing(pairing));
        }
    }

    private HostedMatch findNewMatch() {
        for (HostedMatch m : lobby.getActiveMatches().getAll()) {
            if (!trackedMatches.containsKey(m.getMatchId())) {
                return m;
            }
        }
        return null;
    }

    /**
     * The {@link GameType} a tournament match of the given event format runs as.
     * Both limited formats (draft and sealed) keep their limited {@code GameType}
     * so deck semantics and WinLose handling match; anything else falls back to
     * Constructed.
     */
    public static GameType gameTypeFor(EventFormat format) {
        if (format == null) {
            return GameType.Constructed;
        }
        return switch (format) {
            case SEALED -> GameType.Sealed;
            case BOOSTER_DRAFT -> GameType.Draft;
            default -> GameType.Constructed;
        };
    }

    private EventParticipant findParticipant(TournamentPlayer tp) {
        for (EventParticipant ep : event.getParticipants()) {
            if (ep.getTournamentPlayer() == tp) {
                return ep;
            }
        }
        return null;
    }

    private String formatPairing(TournamentPairing pairing) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (TournamentPlayer tp : pairing.getPairedPlayers()) {
            if (!first) sb.append(" vs ");
            first = false;
            sb.append(tp.getPlayer().getName());
        }
        return sb.toString();
    }

    private void startPolling() {
        if (pollTask != null) {
            pollTask.cancel(false);
        }
        pollTask = server.getAfkExecutor().scheduleAtFixedRate(
                this::checkCompletedMatches,
                POLL_INTERVAL_MS,
                POLL_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );
    }

    private void stopPolling() {
        if (pollTask != null) {
            pollTask.cancel(false);
            pollTask = null;
        }
    }

    private synchronized void checkCompletedMatches() {
        boolean anyCompleted = false;
        int completedRound = tournament.getActiveRound();

        for (String matchId : new ArrayList<>(trackedMatches.keySet())) {
            HostedMatch match = trackedMatches.get(matchId);
            if (match == null || match.isMatchOver()) {
                TournamentPairing pairing = matchToPairing.get(matchId);
                if (pairing != null) {
                    determineWinner(match, pairing);
                    tournament.reportMatchCompletion(pairing);

                    String winnerName = pairing.getWinner() != null
                            ? pairing.getWinner().getPlayer().getName() : null;
                    netLog.info("[Tournament] Match complete — matchId={}, winner={}", matchId, winnerName);
                    lobby.broadcastTournamentEvent(new MatchCompleteEvent(matchId, winnerName, ""));
                }
                trackedMatches.remove(matchId);
                matchToPairing.remove(matchId);
                pairingToMatchId.remove(pairing);
                anyCompleted = true;
            }
        }

        if (anyCompleted) {
            server.updateLobbyState();

            if (trackedMatches.isEmpty()) {
                stopPolling();

                netLog.info("[Tournament] Round {} complete", completedRound);
                lobby.broadcastTournamentEvent(new RoundCompleteEvent(completedRound));

                if (tournament.isTournamentOver()) {
                    onTournamentComplete();
                } else {
                    enterBetweenRoundStandby();
                }
            }
        }
    }

    private void determineWinner(HostedMatch match, TournamentPairing pairing) {
        List<TournamentPlayer> pairedPlayers = pairing.getPairedPlayers();
        if (pairedPlayers.size() < 2) {
            pairing.setWinner(pairedPlayers.get(0));
            return;
        }

        if (match == null || match.getMatch() == null) {
            pairing.markVoid();
            netLog.warn("[Tournament] Match or match.getMatch() was null — VOID (no points awarded)");
            return;
        }

        RegisteredPlayer winner = match.getMatch().getWinner();
        LobbyPlayer winnerPlayer = winner != null ? winner.getPlayer() : null;
        TournamentPairing.MatchResult result = resolveMatchOutcome(pairing, winnerPlayer);
        switch (result) {
            case WIN:
                netLog.info("[Tournament] Winner determined by match outcome: {}", winnerPlayer.getName());
                break;
            case DRAW:
                netLog.warn("[Tournament] Match ended with no winner (draw) — recording a tie");
                break;
            case VOID:
                netLog.error("[Tournament] Match winner '{}' not found among pairing players — VOID (no points awarded)",
                        winnerPlayer == null ? "null" : winnerPlayer.getName());
                break;
            default:
                break;
        }
    }

    /**
     * Maps a match outcome onto a pairing's result. Never awards a win silently:
     * a null winner (draw) becomes a tie for all players; a winner whose name
     * matches no paired player (a desync) voids the pairing rather than handing
     * the match to the first player.
     *
     * @param pairing the pairing to mutate
     * @param matchWinner the match's winning lobby player, or null for a draw
     * @return the resolved {@link TournamentPairing.MatchResult}
     */
    public static TournamentPairing.MatchResult resolveMatchOutcome(TournamentPairing pairing, LobbyPlayer matchWinner) {
        if (matchWinner == null) {
            pairing.markDraw();
            return TournamentPairing.MatchResult.DRAW;
        }
        for (TournamentPlayer tp : pairing.getPairedPlayers()) {
            if (tp.getPlayer().getName().equals(matchWinner.getName())) {
                pairing.setWinner(tp);
                return TournamentPairing.MatchResult.WIN;
            }
        }
        pairing.markVoid();
        return TournamentPairing.MatchResult.VOID;
    }

    private void onTournamentComplete() {
        event.setPhase(EventPhase.TOURNAMENT_COMPLETE);

        List<StandingView> finalStandings = buildStandings();

        StringBuilder results = new StringBuilder("Tournament complete! Final standings:\n");
        int rank = 1;
        for (StandingView s : finalStandings) {
            results.append(rank++).append(". ")
                    .append(s.playerName())
                    .append(" — ")
                    .append(s.score())
                    .append(" pts\n");
        }

        netLog.info("[Tournament] Tournament complete — {} players ranked", finalStandings.size());
        for (StandingView s : finalStandings) {
            netLog.info("[Tournament]   {} — W:{} L:{} B:{} Score:{} OMW:{}",
                    s.playerName(), s.wins(), s.losses(), s.byes(), s.score(), s.omwPercent());
        }

        server.broadcast(new MessageEvent(results.toString()));

        lobby.broadcastTournamentEvent(new TournamentCompleteEvent(finalStandings, false));
        broadcastUpdate(RoundState.NONE);

        server.updateLobbyState();
    }

    private List<StandingView> buildStandings() {
        List<TournamentPlayer> ranked = new ArrayList<>(tournament.getAllPlayers());
        ranked.sort((a, b) -> {
            int scoreCmp = Integer.compare(b.getScore(), a.getScore());
            if (scoreCmp != 0) return scoreCmp;
            return Double.compare(b.getOMW(tournament.getAllPlayers()), a.getOMW(tournament.getAllPlayers()));
        });
        List<StandingView> views = new ArrayList<>();
        for (TournamentPlayer tp : ranked) {
            views.add(new StandingView(
                    tp.getPlayer().getName(),
                    tp.getWins(),
                    tp.getLosses(),
                    tp.getByes(),
                    tp.getScore(),
                    tp.getOMWPercent(tournament.getAllPlayers())));
        }
        return views;
    }

    private List<PairingView> buildPairings(RoundState state) {
        List<PairingView> views = new ArrayList<>();
        if (state == RoundState.COMPLETE) {
            int finishedRound = tournament.getActiveRound() - 1;
            for (TournamentPairing pairing : tournament.getCompletedPairings()) {
                if (pairing.getRound() != finishedRound) continue;
                views.add(toPairingView(pairing));
            }
        } else {
            for (TournamentPairing pairing : tournament.getActivePairings()) {
                views.add(toPairingView(pairing));
            }
        }
        return views;
    }

    private PairingView toPairingView(TournamentPairing pairing) {
        List<TournamentPlayer> players = pairing.getPairedPlayers();
        String playerA = !players.isEmpty() ? players.get(0).getPlayer().getName() : "?";
        String playerB = players.size() > 1 ? players.get(1).getPlayer().getName() : "?";
        String winner = pairing.getWinner() != null ? pairing.getWinner().getPlayer().getName() : null;
        PairingView.PairingStatus status;
        if (pairing.isBye()) {
            status = PairingView.PairingStatus.BYE;
        } else {
            status = switch (pairing.getResult()) {
                case DRAW -> PairingView.PairingStatus.DRAW;
                case VOID -> PairingView.PairingStatus.VOID;
                case WIN -> PairingView.PairingStatus.COMPLETE;
                default -> PairingView.PairingStatus.ONGOING;
            };
        }
        String matchId = pairingToMatchId.get(pairing);
        return new PairingView(playerA, playerB, matchId, status, winner);
    }

    private void broadcastUpdate(RoundState state) {
        int round = state == RoundState.COMPLETE ? tournament.getActiveRound() - 1 : tournament.getActiveRound();
        if (round < 1) {
            round = tournament.getActiveRound();
        }
        lobby.broadcastTournamentEvent(new TournamentUpdateEvent(
                event.getEventId(), round, tournament.getTotalRounds(), state,
                buildPairings(state), buildStandings()));
    }

    public void shutdown() {
        netLog.info("[Tournament] Tournament shutdown — cancelling with {} active matches", trackedMatches.size());
        stopPolling();
        inStandby = false;
        for (HostedMatch match : trackedMatches.values()) {
            if (!match.isMatchOver()) {
                match.endCurrentGame();
            }
        }
        trackedMatches.clear();
        matchToPairing.clear();
        pairingToMatchId.clear();

        lobby.broadcastTournamentEvent(new TournamentCompleteEvent(buildStandings(), true));
    }

    private void enterBetweenRoundStandby() {
        inStandby = true;

        for (EventParticipant ep : event.getParticipants()) {
            if (ep.isHuman()) {
                LobbySlot slot = lobby.getSlot(ep.getLobbySlotIndex());
                if (slot != null) {
                    lobby.setPlayerReady(ep.getLobbySlotIndex(), false);
                }
            }
        }

        boolean hasHuman = false;
        for (EventParticipant ep : event.getParticipants()) {
            if (ep.isHuman()) {
                hasHuman = true;
                break;
            }
        }

        if (!hasHuman) {
            netLog.info("[Tournament] No human players — auto-starting next round");
            proceedToNextRound();
            return;
        }

        int completedRound = tournament.getActiveRound() - 1;
        netLog.info("[Tournament] Entering between-round standby — waiting for host to start the next round");
        broadcastUpdate(RoundState.COMPLETE);
        server.broadcast(new MessageEvent(
                "Round " + completedRound + " complete. All players, click Ready. The host will start the next round."));
    }

    public synchronized void onPlayerReady(int slotIndex) {
        // No-op: the host explicitly starts the next round when all players are ready.
    }

    /**
     * Whether every human participant has marked themselves ready in the lobby.
     * Used by the host UI to enable the "Start Next Round" button.
     */
    public synchronized boolean isAllHumanPlayersReady() {
        if (!inStandby) return false;
        for (EventParticipant ep : event.getParticipants()) {
            if (ep.isHuman()) {
                LobbySlot slot = lobby.getSlot(ep.getLobbySlotIndex());
                if (slot == null || !slot.isReady()) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Host action: start the next round of matches. Only valid while in between-round standby.
     */
    public synchronized void startNextRound() {
        if (!inStandby) {
            netLog.warn("[Tournament] startNextRound called but tournament is not in standby — ignoring");
            return;
        }
        netLog.info("[Tournament] Host starting next round (round {})", tournament.getActiveRound());
        inStandby = false;
        startRoundMatches();
        server.updateLobbyState();
    }

    private void proceedToNextRound() {
        inStandby = false;
        startRoundMatches();
        server.updateLobbyState();
    }

    public boolean isInStandby() {
        return inStandby;
    }
}
