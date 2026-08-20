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

    private final Map<String, HostedMatch> trackedMatches = new ConcurrentHashMap<>();
    private final Map<String, TournamentPairing> matchToPairing = new ConcurrentHashMap<>();
    private final Map<TournamentPairing, String> pairingToMatchId = new ConcurrentHashMap<>();
    private ScheduledFuture<?> pollTask;
    private boolean inStandby = false;

    public ServerTournamentController(ServerGameLobby lobby, NetworkEvent event) {
        this.lobby = lobby;
        this.event = event;
        this.server = FServerManager.getInstance();

        List<TournamentPlayer> players = new ArrayList<>();
        for (EventParticipant ep : event.getParticipants()) {
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
        List<String> playerNames = event.getParticipants().stream()
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

        GameType gameType = event.getFormat() == EventFormat.SEALED
                ? GameType.Sealed
                : GameType.Constructed;

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

        if (match != null && match.getMatch() != null) {
            RegisteredPlayer winner = match.getMatch().getWinner();
            if (winner != null) {
                String winnerName = winner.getPlayer().getName();
                for (TournamentPlayer tp : pairedPlayers) {
                    if (tp.getPlayer().getName().equals(winnerName)) {
                        pairing.setWinner(tp);
                        netLog.info("[Tournament] Winner determined by match outcome: {}", winnerName);
                        return;
                    }
                }
                netLog.warn("[Tournament] Match winner '{}' not found in pairing players, falling back to first player", winnerName);
            } else {
                netLog.warn("[Tournament] Match.getWinner() returned null, falling back to first player");
            }
        } else {
            netLog.warn("[Tournament] Match or match.getMatch() was null, falling back to first player");
        }

        pairing.setWinner(pairedPlayers.get(0));
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
        PairingView.PairingStatus status = pairing.isBye()
                ? PairingView.PairingStatus.BYE
                : (winner != null ? PairingView.PairingStatus.COMPLETE : PairingView.PairingStatus.ONGOING);
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
