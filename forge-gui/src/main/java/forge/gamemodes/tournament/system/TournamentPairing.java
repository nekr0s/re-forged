package forge.gamemodes.tournament.system;

import java.util.ArrayList;
import java.util.List;

import com.thoughtworks.xstream.io.HierarchicalStreamWriter;

import forge.LobbyPlayer;
import forge.game.GameOutcome;

public class TournamentPairing {

    /**
     * The outcome of a completed pairing. WIN awards the match to
     * {@link #getWinner()}; DRAW awards a tie to every player; VOID awards no
     * points to anyone (e.g. both players AWOL, or a result that can't be
     * trusted). PENDING means the pairing hasn't been resolved yet.
     */
    public enum MatchResult { PENDING, WIN, DRAW, VOID }

    private int round;
    private boolean bye = false;
    private final List<TournamentPlayer> pairedPlayers = new ArrayList<>();
    private final List<GameOutcome> outcomes = new ArrayList<>();
    private TournamentPlayer winner;
    private MatchResult result = MatchResult.PENDING;

    public TournamentPairing(int rnd, List<TournamentPlayer> plyrs) {
        pairedPlayers.addAll(plyrs);
        round = rnd;
        winner = null;
    }

    public int getRound() { return round; }

    public void setRound(int round) { this.round = round; }

    public boolean isBye() { return bye; }

    public void setBye(boolean bye) { this.bye = bye; }

    public List<TournamentPlayer> getPairedPlayers() { return pairedPlayers; }

    public List<GameOutcome> getOutcomes() { return outcomes; }

    public TournamentPlayer getWinner() { return winner; }

    public MatchResult getResult() { return result; }

    public void setWinner(TournamentPlayer winner) {
        this.winner = winner;
        this.result = winner != null ? MatchResult.WIN : MatchResult.PENDING;
    }

    /** A match that ended with no winner (a draw): every player gets a tie. */
    public void markDraw() {
        this.result = MatchResult.DRAW;
        this.winner = null;
    }

    /** A pairing that produced no result (e.g. both players AWOL): no points. */
    public void markVoid() {
        this.result = MatchResult.VOID;
        this.winner = null;
    }

    public boolean isDraw() { return result == MatchResult.DRAW; }
    public boolean isVoid() { return result == MatchResult.VOID; }

    public void setWinnerByIndex(int index) {
        for(TournamentPlayer pl : pairedPlayers) {
            if (pl.getIndex() == index) {
                this.winner = pl;
                return;
            }
        }
    }

    public boolean hasPlayer(LobbyPlayer player) {
        for(TournamentPlayer pl : this.pairedPlayers) {
            if (pl.getPlayer().equals(player)) {
                return true;
            }
        }
        return false;
    }

    public String outputHeader() {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for(TournamentPlayer tp : getPairedPlayers()) {
            // Post Record
            if (!first) {
                sb.append("vs ");
            }
            first = false;
            sb.append(tp.getNameAndScore()).append(" ");
        }
        if (isBye()) {
            sb.append("BYE");
        }
        return sb.toString();
    }

    public void exportToXML(HierarchicalStreamWriter writer) {

    }
}
