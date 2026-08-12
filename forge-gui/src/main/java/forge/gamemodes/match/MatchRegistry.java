package forge.gamemodes.match;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MatchRegistry {
    private final Map<String, HostedMatch> matches = new ConcurrentHashMap<>();

    public void register(HostedMatch match) {
        matches.put(match.getMatchId(), match);
    }

    public HostedMatch get(String matchId) {
        return matches.get(matchId);
    }

    public void unregister(String matchId) {
        matches.remove(matchId);
    }

    public boolean isEmpty() {
        return matches.isEmpty();
    }

    public boolean hasActiveMatches() {
        return !matches.isEmpty();
    }

    public Collection<HostedMatch> getAll() {
        return Collections.unmodifiableCollection(matches.values());
    }

    public int size() {
        return matches.size();
    }
}
