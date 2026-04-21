package mobarmy.lb.mobarmy.team;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Formatting;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TeamManager {
    private final Map<String, Team> teams = new LinkedHashMap<>();
    private final Map<UUID, Team> playerTeam = new ConcurrentHashMap<>();

    public Team create(String name, Formatting color) {
        if (teams.containsKey(name.toLowerCase(Locale.ROOT))) return null;
        Team t = new Team(name, color);
        teams.put(name.toLowerCase(Locale.ROOT), t);
        return t;
    }

    public boolean remove(String name) {
        Team t = teams.remove(name.toLowerCase(Locale.ROOT));
        if (t == null) return false;
        for (UUID u : t.members) playerTeam.remove(u);
        return true;
    }

    public Team get(String name) { return teams.get(name.toLowerCase(Locale.ROOT)); }
    public Team get(UUID player) { return playerTeam.get(player); }
    public Team get(ServerPlayerEntity p) { return playerTeam.get(p.getUuid()); }

    public boolean join(ServerPlayerEntity p, String name) { return joinUuid(p.getUuid(), name); }

    public boolean joinUuid(UUID uuid, String name) {
        Team t = get(name);
        if (t == null) return false;
        leaveUuid(uuid);
        t.members.add(uuid);
        playerTeam.put(uuid, t);
        return true;
    }

    public void leave(ServerPlayerEntity p) { leaveUuid(p.getUuid()); }

    public void leaveUuid(UUID uuid) {
        Team old = playerTeam.remove(uuid);
        if (old != null) old.members.remove(uuid);
    }

    public Collection<Team> all() { return teams.values(); }
    public List<Team> ordered() { return new ArrayList<>(teams.values()); }
    public int size() { return teams.size(); }

    public void clear() {
        teams.clear();
        playerTeam.clear();
    }
}

