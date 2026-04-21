package mobarmy.lb.mobarmy.ui;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import mobarmy.lb.mobarmy.MobarmyMod;
import mobarmy.lb.mobarmy.team.Team;
import mobarmy.lb.mobarmy.ui.lobby.MenuUtils;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.*;

/**
 * Wave-Editor UI — one page per wave.
 *
 * Layout (9x6 chest, 54 slots):
 *  Row 0 (0-8):   [W1][W2][W3]...     [STATUS]            [DONE]
 *                  one slot per wave   slot 4              slot 8
 *  Row 1 (9-17):  Coloured separator (matches active wave colour)
 *  Rows 2-4 (18-44): Mob pool — 27 slots, hover shows per-wave breakdown
 *  Row 5 (45-53): Active wave preview — one egg per mob type, count = stack size,
 *                 right-click removes one of that type from the active wave.
 *
 * Players in this menu take no damage (handled in MobarmyMod ALLOW_DAMAGE).
 */
public class WaveBuilderMenu implements NamedScreenHandlerFactory {

    private static final Map<String, Set<Handler>> OPEN_HANDLERS = new HashMap<>();
    private static final Map<UUID, Integer> ACTIVE_WAVE = new HashMap<>();
    private static final Map<UUID, Integer> ACTIVE_PAGE = new HashMap<>();
    /** Pool-Slots in den Reihen 2-4 (18..44) = 27 Mobs pro Seite. */
    private static final int POOL_SLOTS_PER_PAGE = 27;
    private static final int SLOT_PAGE_PREV = 9;   // erste Spalte Separator-Reihe
    private static final int SLOT_PAGE_INFO = 13;  // Mitte Separator-Reihe
    private static final int SLOT_PAGE_NEXT = 17;  // letzte Spalte Separator-Reihe
    private static final Formatting[] WAVE_COLORS = {
        Formatting.GREEN, Formatting.YELLOW, Formatting.GOLD,
        Formatting.RED,   Formatting.LIGHT_PURPLE, Formatting.AQUA
    };

    public static void open(ServerPlayerEntity p, MobarmyMod mod, Team team) {
        ACTIVE_WAVE.putIfAbsent(p.getUuid(), 0);
        p.openHandledScreen(new WaveBuilderMenu(mod, team));
    }

    public static void closeAll(MobarmyMod mod) {
        List<ServerPlayerEntity> players = new ArrayList<>();
        for (Set<Handler> set : OPEN_HANDLERS.values())
            for (Handler h : set) if (h.viewer != null) players.add(h.viewer);
        for (ServerPlayerEntity p : players) p.closeHandledScreen();
        OPEN_HANDLERS.clear();
        ACTIVE_WAVE.clear();
        ACTIVE_PAGE.clear();
    }

    public static boolean allTeamsSubmitted(MobarmyMod mod) {
        if (mod.teams.size() == 0) return false;
        for (Team t : mod.teams.all()) if (!t.wavesSubmitted) return false;
        return true;
    }

    private final MobarmyMod mod;
    private final Team team;

    public WaveBuilderMenu(MobarmyMod mod, Team team) {
        this.mod = mod;
        this.team = team;
    }

    @Override
    public Text getDisplayName() {
        return Text.literal("Wellen-Editor: " + team.name).formatted(team.color, Formatting.BOLD);
    }

    @Override
    public ScreenHandler createMenu(int syncId, PlayerInventory pi, PlayerEntity p) {
        SimpleInventory inv = new SimpleInventory(54);
        Handler h = new Handler(syncId, pi, inv, mod, team, (ServerPlayerEntity) p);
        OPEN_HANDLERS.computeIfAbsent(team.name, n -> new HashSet<>()).add(h);
        renderAllForTeam(team, mod);
        return h;
    }

    public static class Handler extends GenericContainerScreenHandler {
        final SimpleInventory inv;
        final MobarmyMod mod;
        final Team team;
        ServerPlayerEntity viewer;

        Handler(int syncId, PlayerInventory pi, SimpleInventory inv, MobarmyMod mod, Team team, ServerPlayerEntity viewer) {
            super(ScreenHandlerType.GENERIC_9X6, syncId, pi, inv, 6);
            this.inv = inv;
            this.mod = mod;
            this.team = team;
            this.viewer = viewer;
        }

        @Override
        public void onSlotClick(int slot, int button, SlotActionType action, PlayerEntity p) {
            if (slot < 0 || slot >= 54) { super.onSlotClick(slot, button, action, p); return; }
            if (!(p instanceof ServerPlayerEntity sp)) return;
            int waveCount = mod.config.waveCount;

            if (slot < waveCount) {
                ACTIVE_WAVE.put(sp.getUuid(), slot);
                renderAllForTeam(team, mod);
                return;
            }
            // Page-Navigation für den Mob-Pool
            if (slot == SLOT_PAGE_PREV || slot == SLOT_PAGE_NEXT) {
                int totalPages = Math.max(1,
                    (sortedTypes(team).size() + POOL_SLOTS_PER_PAGE - 1) / POOL_SLOTS_PER_PAGE);
                int page = ACTIVE_PAGE.getOrDefault(sp.getUuid(), 0);
                if (slot == SLOT_PAGE_PREV && page > 0) page--;
                else if (slot == SLOT_PAGE_NEXT && page < totalPages - 1) page++;
                else return;
                ACTIVE_PAGE.put(sp.getUuid(), page);
                render(((Handler) sp.currentScreenHandler).inv, team, sp, mod);
                ((Handler) sp.currentScreenHandler).sendContentUpdates();
                return;
            }
            if (slot == 8) {
                team.wavesSubmitted = !team.wavesSubmitted;
                Text msg = team.wavesSubmitted
                    ? Text.literal("Team " + team.name + " hat seine Wellen abgegeben!").formatted(Formatting.GREEN, Formatting.BOLD)
                    : Text.literal("Team " + team.name + " bearbeitet die Wellen wieder.").formatted(Formatting.YELLOW);
                mod.server.getPlayerManager().broadcast(msg, false);
                for (Set<Handler> set : OPEN_HANDLERS.values())
                    for (Handler h : set) { render(h.inv, h.team, h.viewer, mod); h.sendContentUpdates(); }
                return;
            }
            if (slot >= 18 && slot <= 44) {
                if (team.wavesSubmitted) {
                    sp.sendMessage(Text.literal("Team hat abgegeben - klicke FERTIG um zu bearbeiten").formatted(Formatting.RED), true);
                    return;
                }
                int poolIndex = slot - 18;
                List<EntityType<?>> available = sortedTypes(team);
                int page = ACTIVE_PAGE.getOrDefault(sp.getUuid(), 0);
                int globalIdx = page * POOL_SLOTS_PER_PAGE + poolIndex;
                if (globalIdx < 0 || globalIdx >= available.size()) return;
                EntityType<?> type = available.get(globalIdx);
                int waveIdx = ACTIVE_WAVE.getOrDefault(sp.getUuid(), 0);
                ensureWaves(team, waveCount);

                if (button == 0) {
                    if (remaining(team, type) > 0) team.waves.get(waveIdx).add(type);
                } else if (button == 1) {
                    List<EntityType<?>> w = team.waves.get(waveIdx);
                    for (int i = w.size() - 1; i >= 0; i--) {
                        if (w.get(i) == type) { w.remove(i); break; }
                    }
                }
                renderAllForTeam(team, mod);
                return;
            }
            // AUTO-ASSIGN button (slot 7): verteilt alle gefarmten Mobs gleich-
            // mäßig per round-robin über alle Wellen.
            if (slot == 7) {
                if (team.wavesSubmitted) {
                    sp.sendMessage(Text.literal("Team hat abgegeben - klicke FERTIG um zu bearbeiten").formatted(Formatting.RED), true);
                    return;
                }
                autoAssign(team, waveCount);
                sp.sendMessage(Text.literal("Auto-Assign: alle Mobs gleichmäßig verteilt.").formatted(Formatting.GREEN, Formatting.BOLD), true);
                renderAllForTeam(team, mod);
                return;
            }
            if (slot >= 45 && slot <= 53 && !team.wavesSubmitted) {
                int waveIdx = ACTIVE_WAVE.getOrDefault(sp.getUuid(), 0);
                ensureWaves(team, waveCount);
                List<EntityType<?>> activeWave = team.waves.get(waveIdx);
                List<EntityType<?>> uniques = uniqueOrdered(activeWave);
                int idx = slot - 45;
                if (idx >= 0 && idx < uniques.size()) {
                    EntityType<?> type = uniques.get(idx);
                    for (int i = activeWave.size() - 1; i >= 0; i--) {
                        if (activeWave.get(i) == type) { activeWave.remove(i); break; }
                    }
                    renderAllForTeam(team, mod);
                }
            }
        }

        @Override public ItemStack quickMove(PlayerEntity p, int s) { return ItemStack.EMPTY; }

        @Override
        public void onClosed(PlayerEntity p) {
            super.onClosed(p);
            Set<Handler> set = OPEN_HANDLERS.get(team.name);
            if (set != null) set.remove(this);
        }
    }

    public static void renderAllForTeam(Team team, MobarmyMod mod) {
        Set<Handler> set = OPEN_HANDLERS.get(team.name);
        if (set == null) return;
        for (Handler h : set) {
            render(h.inv, team, h.viewer, mod);
            h.sendContentUpdates();
        }
    }

    private static void render(SimpleInventory inv, Team team, ServerPlayerEntity viewer, MobarmyMod mod) {
        int waveCount = mod.config.waveCount;
        ensureWaves(team, waveCount);
        int active = viewer == null ? 0 : ACTIVE_WAVE.getOrDefault(viewer.getUuid(), 0);
        if (active >= waveCount) active = 0;
        Formatting activeColor = WAVE_COLORS[active % WAVE_COLORS.length];

        ItemStack bg = MenuUtils.named(new ItemStack(Items.GRAY_STAINED_GLASS_PANE), Text.literal(" "));
        ItemStack accent = MenuUtils.named(new ItemStack(coloredPaneFor(activeColor)), Text.literal(" "));
        for (int i = 0; i < 54; i++) inv.setStack(i, bg.copy());
        for (int i = 9; i <= 17; i++) inv.setStack(i, accent.copy());

        for (int w = 0; w < waveCount && w < 8; w++) {
            inv.setStack(w, waveTabItem(team, w, w == active));
        }
        inv.setStack(4, statusItem(team, mod));
        inv.setStack(7, autoAssignButton(team));
        inv.setStack(8, doneButton(team));

        List<EntityType<?>> types = sortedTypes(team);
        int totalPages = Math.max(1, (types.size() + POOL_SLOTS_PER_PAGE - 1) / POOL_SLOTS_PER_PAGE);
        int page = viewer == null ? 0 : ACTIVE_PAGE.getOrDefault(viewer.getUuid(), 0);
        if (page >= totalPages) { page = totalPages - 1; if (viewer != null) ACTIVE_PAGE.put(viewer.getUuid(), page); }
        if (page < 0) page = 0;

        int start = page * POOL_SLOTS_PER_PAGE;
        int end = Math.min(types.size(), start + POOL_SLOTS_PER_PAGE);
        for (int i = start; i < end; i++) {
            inv.setStack(18 + (i - start), poolItem(team, types.get(i), waveCount));
        }
        if (types.isEmpty()) {
            inv.setStack(31, MenuUtils.named(new ItemStack(Items.BARRIER),
                Text.literal("Keine Mobs gefarmt!").formatted(Formatting.RED, Formatting.BOLD),
                Text.literal("Dein Team hat in der Farm-Phase").formatted(Formatting.GRAY),
                Text.literal("keine Mobs getoetet.").formatted(Formatting.GRAY)
            ));
        }

        // Page-Pfeile + Info auf der Separator-Reihe
        inv.setStack(SLOT_PAGE_PREV, pageArrow(false, page > 0, page, totalPages));
        inv.setStack(SLOT_PAGE_NEXT, pageArrow(true, page < totalPages - 1, page, totalPages));
        if (totalPages > 1) inv.setStack(SLOT_PAGE_INFO, pageInfo(page, totalPages, types.size()));

        List<EntityType<?>> activeWave = team.waves.get(active);
        List<EntityType<?>> uniques = uniqueOrdered(activeWave);
        Object2IntOpenHashMap<EntityType<?>> activeCounts = new Object2IntOpenHashMap<>();
        for (EntityType<?> t : activeWave) activeCounts.addTo(t, 1);

        int previewMax = Math.min(uniques.size(), 9);
        for (int i = 0; i < previewMax; i++) {
            EntityType<?> type = uniques.get(i);
            int cnt = activeCounts.getInt(type);
            int total = team.killedMobs.getInt(type);
            int babies = team.killedBabies.getInt(type);
            float babyChance = total > 0 ? (float) babies / total : 0f;
            int expectedBabies = Math.round(cnt * babyChance);
            inv.setStack(45 + i, previewItem(type, cnt, expectedBabies, activeColor));
        }
        if (uniques.isEmpty()) {
            inv.setStack(49, MenuUtils.named(new ItemStack(Items.STRUCTURE_VOID),
                Text.literal("Welle " + (active + 1) + " ist leer").formatted(activeColor, Formatting.BOLD),
                Text.literal("Klicke unten auf einen Mob,").formatted(Formatting.GRAY),
                Text.literal("um ihn zu dieser Welle hinzuzufuegen.").formatted(Formatting.GRAY)
            ));
        }
    }

    private static net.minecraft.item.Item coloredPaneFor(Formatting f) {
        return switch (f) {
            case GREEN -> Items.LIME_STAINED_GLASS_PANE;
            case YELLOW -> Items.YELLOW_STAINED_GLASS_PANE;
            case GOLD -> Items.ORANGE_STAINED_GLASS_PANE;
            case RED -> Items.RED_STAINED_GLASS_PANE;
            case LIGHT_PURPLE -> Items.MAGENTA_STAINED_GLASS_PANE;
            case AQUA -> Items.LIGHT_BLUE_STAINED_GLASS_PANE;
            default -> Items.WHITE_STAINED_GLASS_PANE;
        };
    }

    private static net.minecraft.item.Item concreteFor(Formatting f, boolean active) {
        if (!active) return Items.LIGHT_GRAY_CONCRETE;
        return switch (f) {
            case GREEN -> Items.LIME_CONCRETE;
            case YELLOW -> Items.YELLOW_CONCRETE;
            case GOLD -> Items.ORANGE_CONCRETE;
            case RED -> Items.RED_CONCRETE;
            case LIGHT_PURPLE -> Items.MAGENTA_CONCRETE;
            case AQUA -> Items.LIGHT_BLUE_CONCRETE;
            default -> Items.WHITE_CONCRETE;
        };
    }

    private static ItemStack waveTabItem(Team team, int waveIdx, boolean active) {
        List<EntityType<?>> wave = team.waves.get(waveIdx);
        Object2IntOpenHashMap<EntityType<?>> count = new Object2IntOpenHashMap<>();
        for (EntityType<?> t : wave) count.addTo(t, 1);
        Formatting waveColor = WAVE_COLORS[waveIdx % WAVE_COLORS.length];

        ItemStack stack = new ItemStack(concreteFor(waveColor, active));
        if (active) MenuUtils.glow(stack);
        if (!wave.isEmpty()) stack.setCount(Math.min(64, wave.size()));

        String prefix = active ? "> " : "  ";
        Text name = Text.literal(prefix + "WELLE " + (waveIdx + 1) + "  (" + wave.size() + ")")
            .formatted(active ? waveColor : Formatting.GRAY, Formatting.BOLD);

        List<Text> lore = new ArrayList<>();
        lore.add(Text.literal(active ? "AKTIVE Seite" : "Klicke zum Wechseln")
            .formatted(active ? Formatting.GREEN : Formatting.DARK_GRAY));
        lore.add(Text.literal(""));
        if (count.isEmpty()) {
            lore.add(Text.literal("(noch leer)").formatted(Formatting.DARK_GRAY));
        } else {
            List<Map.Entry<EntityType<?>, Integer>> entries = new ArrayList<>();
            for (var e : count.object2IntEntrySet()) entries.add(Map.entry(e.getKey(), e.getIntValue()));
            entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
            for (var e : entries) {
                Text mobName = Text.translatable(e.getKey().getTranslationKey());
                lore.add(Text.literal("- ").formatted(Formatting.GRAY)
                    .append(Text.literal(e.getValue() + "x ").formatted(Formatting.WHITE))
                    .append(mobName.copy().formatted(Formatting.AQUA)));
            }
        }
        return MenuUtils.named(stack, name, lore.toArray(new Text[0]));
    }

    private static ItemStack statusItem(Team team, MobarmyMod mod) {
        int totalTeams = mod.teams.size();
        int submitted = 0;
        for (Team t : mod.teams.all()) if (t.wavesSubmitted) submitted++;
        int secondsLeft = mod.gameManager.phaseTicksRemaining() / 20;

        return MenuUtils.named(new ItemStack(Items.CLOCK),
            Text.literal(MenuUtils.formatTime(Math.max(0, secondsLeft)) + " verbleibend")
                .formatted(Formatting.AQUA, Formatting.BOLD),
            Text.literal(""),
            Text.literal("Teams fertig: ").formatted(Formatting.GRAY)
                .append(Text.literal(submitted + " / " + totalTeams)
                    .formatted(submitted == totalTeams ? Formatting.GREEN : Formatting.YELLOW)),
            Text.literal(""),
            Text.literal("Wenn alle FERTIG geklickt haben,").formatted(Formatting.DARK_GRAY),
            Text.literal("startet der Battle sofort.").formatted(Formatting.DARK_GRAY)
        );
    }

    private static ItemStack autoAssignButton(Team team) {
        ItemStack stack = new ItemStack(Items.HOPPER);
        if (!team.wavesSubmitted) MenuUtils.glow(stack);
        Text title = Text.literal("AUTO-ASSIGN").formatted(Formatting.AQUA, Formatting.BOLD);
        List<Text> lore = new ArrayList<>();
        if (team.wavesSubmitted) {
            lore.add(Text.literal("Team hat bereits abgegeben.").formatted(Formatting.RED));
        } else {
            lore.add(Text.literal("Verteilt ALLE gefarmten Mobs").formatted(Formatting.GRAY));
            lore.add(Text.literal("gleichmäßig über alle Wellen.").formatted(Formatting.GRAY));
            lore.add(Text.literal(""));
            lore.add(Text.literal("Achtung: überschreibt deine").formatted(Formatting.YELLOW));
            lore.add(Text.literal("aktuelle Wellen-Belegung!").formatted(Formatting.YELLOW));
        }
        return MenuUtils.named(stack, title, lore.toArray(new Text[0]));
    }

    private static ItemStack pageArrow(boolean next, boolean enabled, int page, int totalPages) {
        ItemStack s = new ItemStack(enabled
            ? (next ? Items.SPECTRAL_ARROW : Items.ARROW)
            : Items.GRAY_DYE);
        if (enabled) MenuUtils.glow(s);
        Text title = enabled
            ? Text.literal(next ? ">> Naechste Seite" : "<< Vorherige Seite")
                .formatted(Formatting.AQUA, Formatting.BOLD)
            : Text.literal(next ? "Letzte Seite" : "Erste Seite")
                .formatted(Formatting.DARK_GRAY);
        Text sub = Text.literal("Seite " + (page + 1) + " / " + totalPages).formatted(Formatting.GRAY);
        return MenuUtils.named(s, title, sub);
    }

    private static ItemStack pageInfo(int page, int totalPages, int totalTypes) {
        ItemStack s = new ItemStack(Items.PAPER);
        s.setCount(Math.max(1, Math.min(64, totalTypes)));
        return MenuUtils.named(s,
            Text.literal("Seite " + (page + 1) + " / " + totalPages).formatted(Formatting.AQUA, Formatting.BOLD),
            Text.literal(totalTypes + " verschiedene Mob-Typen").formatted(Formatting.GRAY),
            Text.literal(""),
            Text.literal("Pfeile in dieser Reihe").formatted(Formatting.DARK_GRAY),
            Text.literal("zum Bl\u00e4ttern benutzen.").formatted(Formatting.DARK_GRAY)
        );
    }

    /**
     * Verteilt alle gefarmten Mobs gleichmäßig per round-robin über alle
     * Wellen. Pro EntityType: count/waveCount in jede Welle, Rest gleichmäßig
     * auf die ersten Wellen verteilt.
     */
    private static void autoAssign(Team team, int waveCount) {
        ensureWaves(team, waveCount);
        // Vorhandene Belegungen löschen.
        for (List<EntityType<?>> w : team.waves) w.clear();
        for (EntityType<?> type : sortedTypes(team)) {
            int total = team.killedMobs.getInt(type);
            if (total <= 0) continue;
            int base = total / waveCount;
            int extra = total - base * waveCount; // remainder geht auf erste Wellen
            for (int w = 0; w < waveCount; w++) {
                int n = base + (w < extra ? 1 : 0);
                List<EntityType<?>> wave = team.waves.get(w);
                for (int i = 0; i < n; i++) wave.add(type);
            }
        }
    }

    private static ItemStack doneButton(Team team) {
        if (team.wavesSubmitted) {
            ItemStack done = new ItemStack(Items.GREEN_CONCRETE);
            MenuUtils.glow(done);
            return MenuUtils.named(done,
                Text.literal("ABGEGEBEN").formatted(Formatting.GREEN, Formatting.BOLD),
                Text.literal(""),
                Text.literal("Dein Team wartet auf die anderen.").formatted(Formatting.GRAY),
                Text.literal("Klick: bearbeiten fortsetzen").formatted(Formatting.YELLOW)
            );
        }
        ItemStack ready = new ItemStack(Items.LIME_CONCRETE);
        MenuUtils.glow(ready);
        return MenuUtils.named(ready,
            Text.literal("FERTIG").formatted(Formatting.GREEN, Formatting.BOLD),
            Text.literal(""),
            Text.literal("Wellen abgeben und auf").formatted(Formatting.GRAY),
            Text.literal("die anderen Teams warten.").formatted(Formatting.GRAY),
            Text.literal(""),
            Text.literal("Wenn alle abgeben -> Battle startet").formatted(Formatting.AQUA)
        );
    }

    private static ItemStack poolItem(Team team, EntityType<?> type, int waveCount) {
        int total = team.killedMobs.getInt(type);
        int babies = team.killedBabies.getInt(type);
        int used = countAcrossWaves(team, type);
        int remaining = Math.max(0, total - used);

        ItemStack stack;
        if (remaining > 0) {
            SpawnEggItem egg = SpawnEggItem.forEntity(type);
            stack = egg != null ? new ItemStack(egg) : new ItemStack(Items.GHAST_TEAR);
            stack.setCount(Math.min(64, remaining));
        } else {
            stack = new ItemStack(Items.GRAY_DYE);
        }

        Text mobName = Text.translatable(type.getTranslationKey());
        Text title = Text.literal("").append(mobName.copy().formatted(
            remaining > 0 ? Formatting.WHITE : Formatting.DARK_GRAY, Formatting.BOLD));

        List<Text> lore = new ArrayList<>();
        lore.add(Text.literal("Verbleibend: ").formatted(Formatting.GRAY)
            .append(Text.literal(String.valueOf(remaining)).formatted(remaining > 0 ? Formatting.GREEN : Formatting.RED))
            .append(Text.literal(" / " + total).formatted(Formatting.DARK_GRAY)));
        if (babies > 0 && total > 0) {
            int pct = Math.round(100f * babies / total);
            lore.add(Text.literal("Davon Babys: ").formatted(Formatting.GRAY)
                .append(Text.literal(babies + " (" + pct + "%)").formatted(Formatting.LIGHT_PURPLE)));
        }
        lore.add(Text.literal(""));
        for (int w = 0; w < waveCount; w++) {
            int c = countInWave(team, type, w);
            Formatting col = c > 0 ? WAVE_COLORS[w % WAVE_COLORS.length] : Formatting.DARK_GRAY;
            lore.add(Text.literal("Welle " + (w + 1) + ": ").formatted(Formatting.DARK_GRAY)
                .append(Text.literal(String.valueOf(c)).formatted(col)));
        }
        lore.add(Text.literal(""));
        if (team.wavesSubmitted) {
            lore.add(Text.literal("Bereits abgegeben").formatted(Formatting.RED));
        } else {
            lore.add(Text.literal("Linksklick: +1 zur aktiven Welle").formatted(Formatting.GREEN));
            lore.add(Text.literal("Rechtsklick: -1 von aktiver Welle").formatted(Formatting.RED));
        }
        return MenuUtils.named(stack, title, lore.toArray(new Text[0]));
    }

    private static ItemStack previewItem(EntityType<?> type, int count, int expectedBabies, Formatting waveColor) {
        SpawnEggItem egg = SpawnEggItem.forEntity(type);
        ItemStack stack = egg != null ? new ItemStack(egg) : new ItemStack(Items.GHAST_TEAR);
        stack.setCount(Math.min(64, count));

        Text mobName = Text.translatable(type.getTranslationKey());
        Text title = Text.literal(count + "x ").formatted(waveColor, Formatting.BOLD)
            .append(mobName.copy().formatted(Formatting.WHITE, Formatting.BOLD));

        List<Text> lore = new ArrayList<>();
        lore.add(Text.literal("In dieser Welle").formatted(Formatting.GRAY));
        if (expectedBabies > 0) {
            lore.add(Text.literal(""));
            lore.add(Text.literal("~" + expectedBabies + " davon als Baby")
                .formatted(Formatting.LIGHT_PURPLE));
            lore.add(Text.literal("(je nach Baby-Anteil deiner Kills)").formatted(Formatting.DARK_GRAY));
        }
        lore.add(Text.literal(""));
        lore.add(Text.literal("Rechtsklick: -1").formatted(Formatting.RED));
        return MenuUtils.named(stack, title, lore.toArray(new Text[0]));
    }

    private static void ensureWaves(Team team, int waveCount) {
        while (team.waves.size() < waveCount) team.waves.add(new ArrayList<>());
    }
    private static int countAcrossWaves(Team team, EntityType<?> type) {
        int n = 0;
        for (List<EntityType<?>> w : team.waves) for (EntityType<?> t : w) if (t == type) n++;
        return n;
    }
    private static int countInWave(Team team, EntityType<?> type, int waveIdx) {
        if (waveIdx >= team.waves.size()) return 0;
        int n = 0;
        for (EntityType<?> t : team.waves.get(waveIdx)) if (t == type) n++;
        return n;
    }
    private static int remaining(Team team, EntityType<?> type) {
        return team.killedMobs.getInt(type) - countAcrossWaves(team, type);
    }
    private static List<EntityType<?>> sortedTypes(Team team) {
        List<EntityType<?>> list = new ArrayList<>(team.killedMobs.keySet());
        list.sort(Comparator.comparing(t -> Registries.ENTITY_TYPE.getId(t).toString()));
        return list;
    }
    private static List<EntityType<?>> uniqueOrdered(List<EntityType<?>> in) {
        List<EntityType<?>> out = new ArrayList<>();
        Set<EntityType<?>> seen = new HashSet<>();
        for (EntityType<?> t : in) if (seen.add(t)) out.add(t);
        return out;
    }
}

