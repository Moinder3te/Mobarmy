package mobarmy.lb.mobarmy.ui.stats;

import mobarmy.lb.mobarmy.team.Team;
import net.minecraft.entity.EntityType;
import net.minecraft.registry.Registries;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.*;

/**
 * Immutable snapshot of all game stats, captured at end of game.
 * Shared by all players viewing the stats UI.
 */
public class GameStats {

    public final List<TeamStats> ranking;   // sorted by score desc
    public final TeamStats winner;          // ranking.get(0) or null
    public final TeamStats farmMvp;         // most total kills
    public final TeamStats mostDiverse;     // most unique mob types
    public final long seed;

    public GameStats(List<Team> teams, long seed) {
        this.seed = seed;
        List<TeamStats> list = new ArrayList<>();
        for (Team t : teams) list.add(new TeamStats(t));
        list.sort(Comparator.comparingInt((TeamStats s) -> s.score).reversed());
        this.ranking = Collections.unmodifiableList(list);
        this.winner = ranking.isEmpty() ? null : ranking.get(0);

        TeamStats bestKills = null;
        TeamStats bestDiversity = null;
        for (TeamStats ts : ranking) {
            if (bestKills == null || ts.totalFarmKills > bestKills.totalFarmKills) bestKills = ts;
            if (bestDiversity == null || ts.uniqueMobTypes > bestDiversity.uniqueMobTypes) bestDiversity = ts;
        }
        this.farmMvp = bestKills;
        this.mostDiverse = bestDiversity;
    }

    public static class TeamStats {
        public final String name;
        public final Formatting color;
        public final int score;
        public final int totalFarmKills;
        public final int uniqueMobTypes;
        public final int totalBabyKills;
        public final int battlePoints;
        public final int wavesCleared;
        public final int memberCount;
        public final List<MobEntry> topMobs;    // sorted by count desc, all of them

        public TeamStats(Team t) {
            this.name = t.name;
            this.color = t.color;
            this.score = t.score;
            this.memberCount = t.members.size();

            int kills = 0;
            int babies = 0;
            for (int c : t.killedMobs.values()) kills += c;
            for (int c : t.killedBabies.values()) babies += c;
            this.totalFarmKills = kills;
            this.totalBabyKills = babies;
            this.uniqueMobTypes = t.killedMobs.size();
            this.battlePoints = score - kills;  // farm = +1/kill, battle = +10/wave
            this.wavesCleared = battlePoints > 0 ? battlePoints / 10 : 0;

            List<MobEntry> mobs = new ArrayList<>();
            for (var entry : t.killedMobs.object2IntEntrySet()) {
                EntityType<?> type = entry.getKey();
                int count = entry.getIntValue();
                int babyCount = t.killedBabies.getOrDefault(type, 0);
                Identifier id = Registries.ENTITY_TYPE.getId(type);
                String mobName = id != null ? id.getPath().replace('_', ' ') : "???";
                // Capitalize first letter
                if (!mobName.isEmpty()) mobName = mobName.substring(0, 1).toUpperCase() + mobName.substring(1);
                mobs.add(new MobEntry(mobName, type, count, babyCount));
            }
            mobs.sort(Comparator.comparingInt((MobEntry e) -> e.count).reversed());
            this.topMobs = Collections.unmodifiableList(mobs);
        }
    }

    public static class MobEntry {
        public final String name;
        public final EntityType<?> type;
        public final int count;
        public final int babyCount;

        public MobEntry(String name, EntityType<?> type, int count, int babyCount) {
            this.name = name;
            this.type = type;
            this.count = count;
            this.babyCount = babyCount;
        }
    }
}
