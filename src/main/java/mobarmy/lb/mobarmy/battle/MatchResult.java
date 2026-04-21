package mobarmy.lb.mobarmy.battle;

import java.util.List;

/**
 * Immutable snapshot of a single match result, captured when it finishes.
 * Stored per-round in BattleController so GameStats can display per-round detail.
 */
public record MatchResult(
    String attackerName,
    String defenderName,
    int round,
    boolean cleared,
    long totalTimeTicks,
    List<Long> waveTimes,
    int deaths
) {
    public static MatchResult from(MatchInstance m, int round) {
        return new MatchResult(
            m.attacker.name,
            m.defender.name,
            round,
            m.cleared,
            m.totalTimeTicks,
            List.copyOf(m.waveTimes),
            m.deaths
        );
    }
}
