package forge.gamemodes.net.server;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import forge.LobbyPlayer;
import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.game.GameType;
import forge.game.player.RegisteredPlayer;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.match.LobbySlot;
import forge.gamemodes.net.EventFormat;
import forge.gamemodes.net.EventParticipant;
import forge.gamemodes.net.EventPhase;
import forge.gamemodes.net.NetworkEvent;
import forge.gamemodes.net.event.MessageEvent;
import forge.gamemodes.tournament.system.TournamentPairing;
import forge.gamemodes.tournament.system.TournamentPlayer;
import forge.gamemodes.tournament.system.TournamentRoundRobin;

public class ServerTournamentController {
    private static final long POLL_INTERVAL_MS = 500L;

    private final ServerGameLobby lobby;
    private final NetworkEvent event;
    private final TournamentRoundRobin tournament;
    private final FServerManager server;

    private final Map<String, HostedMatch> trackedMatches = new ConcurrentHashMap<>();
    private final Map<String, TournamentPairing> matchToPairing = new ConcurrentHashMap<>();
    private ScheduledFuture<?> pollTask;

    public ServerTournamentController(ServerGameLobby lobby, NetworkEvent event) {
        this.lobby = lobby;
        this.event = event;
        this.server = FServerManager.getInstance();

        List<TournamentPlayer> players = new ArrayList<>();
        for (EventParticipant ep : event.getParticipants()) {
            TournamentPlayer tp = new TournamentPlayer(
                    new LobbyPlayerAi(ep.getName(), null),
                    ep.getSeatIndex()
            );
            ep.setTournamentPlayer(tp);
            players.add(tp);
        }

        int totalRounds = event.getNumRounds();
        this.tournament = new TournamentRoundRobin(totalRounds, players);

        event.setTournament(tournament);
    }

    public TournamentRoundRobin getTournament() {
        return tournament;
    }

    public synchronized void startTournament() {
        event.setPhase(EventPhase.TOURNAMENT_IN_PROGRESS);
        startRoundMatches();
    }

    private synchronized void startRoundMatches() {
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
                startRoundMatches();
            }
        } else {
            startPolling();
        }
    }

    private void handleBye(TournamentPairing pairing) {
        TournamentPlayer byePlayer = pairing.getPairedPlayers().get(0);
        pairing.setWinner(byePlayer);
        byePlayer.addBye();
        tournament.reportMatchCompletion(pairing);
    }

    private void startMatchForPairing(TournamentPairing pairing) {
        List<TournamentPlayer> pairedPlayers = pairing.getPairedPlayers();
        List<Integer> slotIndices = new ArrayList<>();

        for (TournamentPlayer tp : pairedPlayers) {
            EventParticipant ep = findParticipant(tp);
            if (ep == null || ep.getLobbySlotIndex() < 0) continue;

            LobbySlot slot = lobby.getSlot(ep.getLobbySlotIndex());
            if (slot == null) continue;

            Deck deck = ep.getDeck();
            if (deck != null) {
                slot.setDeck(deck);
            }
            slot.setIsReady(true);
            slotIndices.add(ep.getLobbySlotIndex());
        }

        if (slotIndices.size() < 2) {
            return;
        }

        GameType gameType = event.getFormat() == EventFormat.SEALED
                ? GameType.Sealed
                : GameType.Constructed;

        Runnable starter = lobby.startMatch(slotIndices, gameType, EnumSet.noneOf(GameType.class));
        if (starter == null) {
            return;
        }

        starter.run();

        HostedMatch match = findNewMatch();
        if (match != null) {
            String matchId = match.getMatchId();
            trackedMatches.put(matchId, match);
            matchToPairing.put(matchId, pairing);

            int gamesPerMatch = event.getGamesPerMatch();
            if (match.getMatch() != null && match.getMatch().getRules() != null) {
                match.getMatch().getRules().setGamesPerMatch(gamesPerMatch);
            }

            server.broadcast(new MessageEvent(
                    "Tournament round " + tournament.getActiveRound()
                            + ": " + formatPairing(pairing)));
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

        for (String matchId : new ArrayList<>(trackedMatches.keySet())) {
            HostedMatch match = trackedMatches.get(matchId);
            if (match == null || match.isMatchOver()) {
                TournamentPairing pairing = matchToPairing.get(matchId);
                if (pairing != null) {
                    determineWinner(match, pairing);
                    tournament.reportMatchCompletion(pairing);
                }
                trackedMatches.remove(matchId);
                matchToPairing.remove(matchId);
                anyCompleted = true;
            }
        }

        if (anyCompleted && trackedMatches.isEmpty()) {
            stopPolling();
            if (tournament.isTournamentOver()) {
                onTournamentComplete();
            } else {
                startRoundMatches();
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
                LobbyPlayer winnerLobby = winner.getPlayer();
                for (TournamentPlayer tp : pairedPlayers) {
                    if (tp.getPlayer().equals(winnerLobby)) {
                        pairing.setWinner(tp);
                        return;
                    }
                }
            }
        }

        pairing.setWinner(pairedPlayers.get(0));
    }

    private void onTournamentComplete() {
        event.setPhase(EventPhase.TOURNAMENT_COMPLETE);

        List<TournamentPlayer> ranked = new ArrayList<>(tournament.getAllPlayers());
        ranked.sort((a, b) -> Integer.compare(b.getScore(), a.getScore()));

        StringBuilder results = new StringBuilder("Tournament complete! Final standings:\n");
        int rank = 1;
        for (TournamentPlayer tp : ranked) {
            results.append(rank++).append(". ")
                    .append(tp.getPlayer().getName())
                    .append(" — ")
                    .append(tp.getScore())
                    .append(" pts\n");
        }

        server.broadcast(new MessageEvent(results.toString()));
        server.updateLobbyState();
    }

    public void shutdown() {
        stopPolling();
        for (HostedMatch match : trackedMatches.values()) {
            if (!match.isMatchOver()) {
                match.endCurrentGame();
            }
        }
        trackedMatches.clear();
        matchToPairing.clear();
    }
}
