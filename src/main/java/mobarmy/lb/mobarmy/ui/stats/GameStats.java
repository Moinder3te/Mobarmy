package mobarmy.lb.mobarmy.ui.stats;

import mobarmy.lb.mobarmy.battle.MatchInstance;
import mobarmy.lb.mobarmy.battle.MatchResult;
import mobarmy.lb.mobarmy.team.Team;
import net.minecraft.entity.EntityType;
import net.minecraft.registry.Registries;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.*;

/**
 * Immutable snapshot of all game stats, captured at end of game.
 * Ranking is by total clear time (fastest wins). Teams that wiped get MAX time.
 */
public class GameStats {

    public final List<TeamStats> ranking;   // sorted by total time asc (fastest first)
    public final TeamStats winner;          // fastest team
    public final TeamStats farmMvp;         // most total farm kills
    public final TeamStats mostDiverse;     // most unique mob types
    public final long seed;

    public GameStats(List<Team> teams, List<MatchResult> allResults, long seed) {
        this.seed = seed;
        List<TeamStats> list = new ArrayList<>();
        for (Team t : teams) {
            // Collect all match results where this team was the attacker.
            List<MatchResult> teamResults = new ArrayList<>();
            for (MatchResult r : allResults) {
                if (r.attackerName().equals(t.name)) teamResults.add(r);
            }
            list.add(new TeamStats(t, teamResults));
        }
        // Sort by total time ascending (fastest first). Wiped teams (MAX_VALUE) go last.
        // Tiebreaker: more waves cleared → fewer deaths.
        list.sort(Comparator.comparingLong((TeamStats s) -> s.totalTimeTicks)
            .thenComparingInt(s -> -s.wavesCleared)
            .thenComparingInt(s -> s.totalDeaths));
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
        public final int totalFarmKills;
        public final int uniqueMobTypes;
        public final int totalBabyKills;
        public final int memberCount;
        public final List<MobEntry> topMobs;

        // ===== TIME-BASED BATTLE STATS =====
        /** Sum of all match times across rounds (ticks). MAX_VALUE if any round was a wipe. */
        public final long totalTimeTicks;
        /** Total waves cleared across all rounds. */
        public final int wavesCleared;
        /** Total deaths across all rounds. */
        public final int totalDeaths;
        /** True if team cleared ALL rounds (no wipes). */
        public final boolean allCleared;
        /** Per-round results for this team. */
        public final List<RoundResult> rounds;

        public TeamStats(Team t, List<MatchResult> results) {
            this.name = t.name;
            this.color = t.color;
            this.memberCount = t.members.size();

            int kills = 0;
            int babies = 0;
            for (int c : t.killedMobs.values()) kills += c;
            for (int c : t.killedBabies.values()) babies += c;
            this.totalFarmKills = kills;
            this.totalBabyKills = babies;
            this.uniqueMobTypes = t.killedMobs.size();

            // Build mob list
            List<MobEntry> mobs = new ArrayList<>();
            for (var entry : t.killedMobs.object2IntEntrySet()) {
                EntityType<?> type = entry.getKey();
                int count = entry.getIntValue();
                int babyCount = t.killedBabies.getOrDefault(type, 0);
                Identifier id = Registries.ENTITY_TYPE.getId(type);
                String mobName = id != null ? id.getPath().replace('_', ' ') : "???";
                if (!mobName.isEmpty()) mobName = mobName.substring(0, 1).toUpperCase() + mobName.substring(1);
                mobs.add(new MobEntry(mobName, type, count, babyCount));
            }
            mobs.sort(Comparator.comparingInt((MobEntry e) -> e.count).reversed());
            this.topMobs = Collections.unmodifiableList(mobs);

            // Battle stats from match results
            long sumTime = 0;
            int waves = 0;
            int deaths = 0;
            boolean cleared = results.isEmpty() ? false : true;
            List<RoundResult> roundList = new ArrayList<>();
            if (results.isEmpty()) sumTime = Long.MAX_VALUE;
            for (MatchResult r : results) {
                roundList.add(new RoundResult(r));
                if (r.cleared() && sumTime < Long.MAX_VALUE) {
                    sumTime += r.totalTimeTicks();
                } else {
                    cleared = false;
                    sumTime = Long.MAX_VALUE; // wipe = DNF
                }
                waves += r.waveTimes().size();
                deaths += r.deaths();
            }
            this.totalTimeTicks = sumTime;
            this.wavesCleared = waves;
            this.totalDeaths = deaths;
            this.allCleared = cleared;
            this.rounds = Collections.unmodifiableList(roundList);
        }
    }

    /** Per-round result for display in the UI. */
    public static class RoundResult {
        public final int round;
        public final String opponentName;
        public final boolean cleared;
        public final long timeTicks;
        public final List<Long> waveTimes;
        public final int deaths;

        public RoundResult(MatchResult r) {
            this.round = r.round();
            this.opponentName = r.defenderName();
            this.cleared = r.cleared();
            this.timeTicks = r.totalTimeTicks();
            this.waveTimes = r.waveTimes();
            this.deaths = r.deaths();
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
