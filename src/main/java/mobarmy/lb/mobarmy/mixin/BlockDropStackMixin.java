package mobarmy.lb.mobarmy.mixin;

import mobarmy.lb.mobarmy.MobarmyMod;
import mobarmy.lb.mobarmy.game.GamePhase;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Zentraler Block-Drop-Randomizer — Zähler-Ansatz.
 * <p>
 * Strategie:
 * <ol>
 *   <li>Bei {@code dropStacks} HEAD: Zähler auf 0 setzen (= "wir tracken").</li>
 *   <li>Vanilla läuft normal → ruft intern {@code dropStack} pro Loot-Table-Item.</li>
 *   <li>Bei jedem {@code dropStack}: Zähler++, und BlockItem-Drops randomisieren.</li>
 *   <li>Bei {@code dropStacks} TAIL: Wenn Zähler immer noch 0 (= Vanilla hat NICHTS
 *       gedropt, z. B. Gras ohne Schere, Glas ohne Silk Touch), spawnen wir
 *       einen erzwungenen randomisierten Drop.</li>
 * </ol>
 * So bleiben Mehrfach-Drops (Clay → 4×, Leaves → Sticks+Saplings+Äpfel)
 * erhalten, und Blöcke mit leerer Loot-Table droppen trotzdem etwas.
 * <p>
 * Aktiv nur während {@link GamePhase#FARM}, sonst No-Op.
 */
@Mixin(Block.class)
public abstract class BlockDropStackMixin {

    /**
     * Zähler für Drops innerhalb eines {@code dropStacks}-Aufrufs.
     * {@code [0] = -1} → kein Tracking aktiv.
     * {@code [0] >= 0} → Anzahl der {@code dropStack}-Aufrufe seit HEAD.
     * {@code [1..2]} → BlockPos (x, z) für den Force-Drop am TAIL.
     * {@code [3]} → BlockPos y.
     */
    @Unique
    private static final ThreadLocal<int[]> mobarmy$tracker = ThreadLocal.withInitial(() -> new int[]{-1, 0, 0, 0});

    /** Block, dessen dropStacks gerade läuft (für Force-Drop am TAIL). */
    @Unique
    private static final ThreadLocal<Block> mobarmy$trackedBlock = new ThreadLocal<>();

    // ====================== dropStacks: HEAD = start tracking ======================

    @Inject(
        method = "dropStacks(Lnet/minecraft/block/BlockState;Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/entity/BlockEntity;Lnet/minecraft/entity/Entity;Lnet/minecraft/item/ItemStack;)V",
        at = @At("HEAD")
    )
    private static void mobarmy$trackStart6(BlockState state, World world, BlockPos pos,
                                             BlockEntity be, Entity entity, ItemStack tool, CallbackInfo ci) {
        mobarmy$beginTracking(state, world, pos);
    }

    @Inject(
        method = "dropStacks(Lnet/minecraft/block/BlockState;Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;)V",
        at = @At("HEAD")
    )
    private static void mobarmy$trackStart3(BlockState state, World world, BlockPos pos, CallbackInfo ci) {
        mobarmy$beginTracking(state, world, pos);
    }

    // ====================== dropStacks: TAIL = check & force-drop ======================

    @Inject(
        method = "dropStacks(Lnet/minecraft/block/BlockState;Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/entity/BlockEntity;Lnet/minecraft/entity/Entity;Lnet/minecraft/item/ItemStack;)V",
        at = @At("TAIL")
    )
    private static void mobarmy$trackEnd6(BlockState state, World world, BlockPos pos,
                                           BlockEntity be, Entity entity, ItemStack tool, CallbackInfo ci) {
        mobarmy$endTracking(world);
    }

    @Inject(
        method = "dropStacks(Lnet/minecraft/block/BlockState;Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;)V",
        at = @At("TAIL")
    )
    private static void mobarmy$trackEnd3(BlockState state, World world, BlockPos pos, CallbackInfo ci) {
        mobarmy$endTracking(world);
    }

    @Unique
    private static void mobarmy$beginTracking(BlockState state, World world, BlockPos pos) {
        if (world.isClient()) return;
        MobarmyMod mod = MobarmyMod.INSTANCE;
        if (mod == null || mod.gameManager == null) return;
        if (!mod.gameManager.isPhase(GamePhase.FARM)) return;
        if (!mod.randomizer.has(state.getBlock())) return;
        int[] t = mobarmy$tracker.get();
        t[0] = 0; // start counting
        t[1] = pos.getX();
        t[2] = pos.getY();
        t[3] = pos.getZ();
        mobarmy$trackedBlock.set(state.getBlock());
    }

    @Unique
    private static void mobarmy$endTracking(World world) {
        int[] t = mobarmy$tracker.get();
        if (t[0] < 0) return; // nicht aktiv
        int count = t[0];
        t[0] = -1; // reset
        Block block = mobarmy$trackedBlock.get();
        mobarmy$trackedBlock.remove();
        if (count > 0) return; // Vanilla hat Drops erzeugt → bereits in dropStack randomisiert
        // Vanilla hat 0 Drops erzeugt → Force-Drop eines randomisierten Items
        if (block == null) return;
        MobarmyMod mod = MobarmyMod.INSTANCE;
        if (mod == null || mod.gameManager == null) return;
        if (!mod.randomizer.has(block)) return;
        ItemStack mapped = mod.randomizer.getDrop(block);
        if (mapped.isEmpty()) return;
        ItemEntity ie = new ItemEntity(world,
            t[1] + 0.5, t[2] + 0.5, t[3] + 0.5, mapped.copy());
        ie.setToDefaultPickupDelay();
        world.spawnEntity(ie);
    }

    // ====================== dropStack: randomize + count ======================

    @Inject(
        method = "dropStack(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/item/ItemStack;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void mobarmy$randomizeDrop3(World world, BlockPos pos, ItemStack stack, CallbackInfo ci) {
        if (world.isClient()) return;
        // Zähler inkrementieren falls wir innerhalb eines dropStacks-Aufrufs sind
        int[] t = mobarmy$tracker.get();
        if (t[0] >= 0) t[0]++;
        ItemStack swap = mobarmy$swap(stack);
        if (swap == null) return;
        ItemEntity ie = new ItemEntity(world,
            pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, swap);
        ie.setToDefaultPickupDelay();
        world.spawnEntity(ie);
        ci.cancel();
    }

    @Inject(
        method = "dropStack(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/math/Direction;Lnet/minecraft/item/ItemStack;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void mobarmy$randomizeDrop4(World world, BlockPos pos, Direction dir, ItemStack stack, CallbackInfo ci) {
        if (world.isClient()) return;
        int[] t = mobarmy$tracker.get();
        if (t[0] >= 0) t[0]++;
        ItemStack swap = mobarmy$swap(stack);
        if (swap == null) return;
        double x = pos.getX() + 0.5 + dir.getOffsetX() * 0.5;
        double y = pos.getY() + 0.5 + dir.getOffsetY() * 0.5;
        double z = pos.getZ() + 0.5 + dir.getOffsetZ() * 0.5;
        ItemEntity ie = new ItemEntity(world, x, y, z, swap);
        ie.setToDefaultPickupDelay();
        ie.setVelocity(dir.getOffsetX() * 0.1, dir.getOffsetY() * 0.1, dir.getOffsetZ() * 0.1);
        world.spawnEntity(ie);
        ci.cancel();
    }

    /** Liefert den randomisierten Drop oder {@code null} wenn nichts zu tun ist. */
    @Unique
    private static ItemStack mobarmy$swap(ItemStack stack) {
        MobarmyMod mod = MobarmyMod.INSTANCE;
        if (mod == null || mod.gameManager == null) return null;
        if (!mod.gameManager.isPhase(GamePhase.FARM)) return null;
        if (stack == null || stack.isEmpty()) return null;
        if (!(stack.getItem() instanceof BlockItem bi)) return null;
        if (!mod.randomizer.has(bi.getBlock())) return null;

        ItemStack mapped = mod.randomizer.getDrop(bi.getBlock());
        if (mapped.isEmpty()) return null;
        ItemStack swap = mapped.copy();
        swap.setCount(stack.getCount());
        return swap;
    }
}

