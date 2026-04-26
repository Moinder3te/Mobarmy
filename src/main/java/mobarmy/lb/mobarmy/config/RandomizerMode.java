package mobarmy.lb.mobarmy.config;

public enum RandomizerMode {
    GLOBAL("Global", "Alle teilen den gleichen Randomizer"),
    PER_TEAM("Pro Team", "Jedes Team hat eigene Drops"),
    PER_PLAYER("Pro Spieler", "Jeder Spieler hat eigene Drops");

    public final String displayName;
    public final String description;

    RandomizerMode(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public RandomizerMode next() {
        RandomizerMode[] vals = values();
        return vals[(ordinal() + 1) % vals.length];
    }

    public RandomizerMode prev() {
        RandomizerMode[] vals = values();
        return vals[(ordinal() + vals.length - 1) % vals.length];
    }
}
