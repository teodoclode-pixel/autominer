package pl.areaminer;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.block.FluidBlock;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public class AreaMinerMod implements ModInitializer {
    public static final String MOD_ID = "areaminer";

    /** Ile blokow kopiemy na tick. */
    private static final int BLOCKS_PER_TICK = 4;
    /** Domyslnie: zatrzymaj, gdy narzedziu zostanie <= tyle wytrzymalosci. */
    private static final int DEFAULT_RESERVE = 100;

    public static Item WAND;

    private static final Map<UUID, Selection> SELECTIONS = new HashMap<>();
    private static final Map<UUID, MiningJob> JOBS = new HashMap<>();
    private static final Map<UUID, Integer> RESERVES = new HashMap<>();

    @Override
    public void onInitialize() {
        // ---- rozdzka (opcjonalna): LPM = pos1, PPM = pos2
        Identifier wandId = Identifier.of(MOD_ID, "wand");
        WAND = Registry.register(Registries.ITEM, wandId,
                new Item(new Item.Settings().maxCount(1)
                        .registryKey(RegistryKey.of(RegistryKeys.ITEM, wandId))));

        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (hand != Hand.MAIN_HAND || !player.getStackInHand(hand).isOf(WAND)) return ActionResult.PASS;
            if (!world.isClient) select(player, world, pos, true);
            return ActionResult.FAIL;
        });
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (hand != Hand.MAIN_HAND || !player.getStackInHand(hand).isOf(WAND)) return ActionResult.PASS;
            if (!world.isClient) select(player, world, hit.getBlockPos(), false);
            return ActionResult.SUCCESS;
        });

        // ---- pakiet od klienta: otwarto czat / ESC / inne okno -> stop
        PayloadTypeRegistry.playC2S().register(StopPayload.ID, StopPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(StopPayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            MiningJob job = JOBS.remove(player.getUuid());
            if (job != null) {
                player.sendMessage(Text.literal("§e[Area] Stop (otwarto menu/czat). Wykopano " + job.mined + " blokow."), false);
            }
        });

        // ---- komendy
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(CommandManager.literal("area")
                    .requires(src -> src.getEntity() instanceof ServerPlayerEntity)
                    .then(CommandManager.literal("set")
                            .then(CommandManager.argument("n", IntegerArgumentType.integer(1, 2))
                                    .executes(ctx -> setPos(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "n")))))
                    .then(CommandManager.literal("start").executes(ctx -> start(ctx.getSource())))
                    .then(CommandManager.literal("stop").executes(ctx -> stop(ctx.getSource())))
                    .then(CommandManager.literal("repairall").executes(ctx -> repairAll(ctx.getSource())))
                    .then(CommandManager.literal("reserve")
                            .then(CommandManager.argument("durability", IntegerArgumentType.integer(0))
                                    .executes(ctx -> setReserve(ctx.getSource(),
                                            IntegerArgumentType.getInteger(ctx, "durability"))))));

            dispatcher.register(CommandManager.literal("repairall")
                    .requires(src -> src.getEntity() instanceof ServerPlayerEntity)
                    .executes(ctx -> repairAll(ctx.getSource())));
        });

        // ---- glowna petla kopania
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            Iterator<Map.Entry<UUID, MiningJob>> it = JOBS.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, MiningJob> entry = it.next();
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());
                if (player == null) { // gracz wyszedl
                    it.remove();
                    continue;
                }
                String stopReason = tickJob(player, entry.getValue());
                if (stopReason != null) {
                    it.remove();
                    player.sendMessage(Text.literal("§e[Area] Stop: " + stopReason
                            + " (wykopano " + entry.getValue().mined + " blokow)"), false);
                }
            }
        });
    }

    // ---------------------------------------------------------------- zaznaczanie

    private static int setPos(ServerCommandSource source, int n) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        select(player, player.getServerWorld(), player.getBlockPos(), n == 1);
        return 1;
    }

    private static void select(PlayerEntity player, World world, BlockPos pos, boolean first) {
        Selection s = SELECTIONS.computeIfAbsent(player.getUuid(), k -> new Selection());
        if (s.dim != null && !s.dim.equals(world.getRegistryKey())) { // inny wymiar -> zacznij od nowa
            s.pos1 = null;
            s.pos2 = null;
        }
        s.dim = world.getRegistryKey();
        if (first) s.pos1 = pos.toImmutable(); else s.pos2 = pos.toImmutable();

        String msg = "§b[Area] Pozycja " + (first ? "1" : "2") + ": "
                + pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
        if (s.pos1 != null && s.pos2 != null) msg += " §7(" + s.volume() + " blokow)";
        player.sendMessage(Text.literal(msg), false);
    }

    // ---------------------------------------------------------------- komendy

    private static int start(ServerCommandSource source) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        Selection s = SELECTIONS.get(player.getUuid());
        if (s == null || s.pos1 == null || s.pos2 == null) {
            source.sendError(Text.literal("Najpierw ustaw: /area set 1 oraz /area set 2"));
            return 0;
        }
        if (!player.getServerWorld().getRegistryKey().equals(s.dim)) {
            source.sendError(Text.literal("Zaznaczenie jest w innym wymiarze."));
            return 0;
        }
        ItemStack tool = player.getMainHandStack();
        if (!tool.isDamageable()) {
            source.sendError(Text.literal("Trzymaj w rece narzedzie (kilof/lopata/siekiera)."));
            return 0;
        }
        if (JOBS.containsKey(player.getUuid())) {
            source.sendError(Text.literal("Kopanie juz trwa. Uzyj /area stop."));
            return 0;
        }
        int reserve = RESERVES.getOrDefault(player.getUuid(), DEFAULT_RESERVE);
        JOBS.put(player.getUuid(), new MiningJob(s.dim, s.pos1, s.pos2));
        source.sendFeedback(() -> Text.literal("§a[Area] Start! Kopie " + s.volume()
                + " blokow od gory do dolu. Stop: /area stop, czat, ESC lub gdy narzedziu zostanie <= "
                + reserve + " wytrzymalosci."), false);
        return 1;
    }

    private static int stop(ServerCommandSource source) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        MiningJob job = JOBS.remove(player.getUuid());
        if (job == null) {
            source.sendError(Text.literal("Nic teraz nie kopie."));
            return 0;
        }
        source.sendFeedback(() -> Text.literal("§e[Area] Zatrzymano (wykopano " + job.mined + " blokow)."), false);
        return 1;
    }

    /** Naprawia wszystkie uszkodzone przedmioty w ekwipunku (inwentarz, zbroja, druga reka). */
    private static int repairAll(ServerCommandSource source) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        PlayerInventory inv = player.getInventory();
        int repaired = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty() && stack.isDamageable() && stack.getDamage() > 0) {
                stack.setDamage(0);
                repaired++;
            }
        }
        final int count = repaired;
        source.sendFeedback(() -> Text.literal("§a[Area] Naprawiono przedmiotow: " + count), false);
        return count;
    }

    private static int setReserve(ServerCommandSource source, int value) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        RESERVES.put(player.getUuid(), value);
        source.sendFeedback(() -> Text.literal("§a[Area] Zatrzymam, gdy narzedziu zostanie <= " + value + " wytrzymalosci."), false);
        return 1;
    }

    // ---------------------------------------------------------------- kopanie

    /** @return null = kopiemy dalej, w przeciwnym razie powod zatrzymania */
    private static String tickJob(ServerPlayerEntity player, MiningJob job) {
        if (!player.getServerWorld().getRegistryKey().equals(job.dim)) return "zmieniles wymiar";
        int reserve = RESERVES.getOrDefault(player.getUuid(), DEFAULT_RESERVE);

        for (int i = 0; i < BLOCKS_PER_TICK; i++) {
            ItemStack tool = player.getMainHandStack();
            if (!tool.isDamageable()) return "nie trzymasz narzedzia";
            int remaining = tool.getMaxDamage() - tool.getDamage();
            if (remaining <= reserve) return "narzedzie ma " + remaining + " wytrzymalosci";

            BlockPos pos = job.next();
            if (pos == null) return "gotowe, caly obszar wykopany";

            ServerWorld world = player.getServerWorld();
            if (!world.getChunkManager().isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) continue;

            BlockState state = world.getBlockState(pos);
            if (state.isAir() || state.getBlock() instanceof FluidBlock) continue;
            if (state.getHardness(world, pos) < 0) continue; // bedrock itp.

            if (player.interactionManager.tryBreakBlock(pos)) job.mined++;
        }
        return null;
    }

    // ---------------------------------------------------------------- klasy pomocnicze

    private static class Selection {
        RegistryKey<World> dim;
        BlockPos pos1, pos2;

        long volume() {
            return (long) (Math.abs(pos1.getX() - pos2.getX()) + 1)
                    * (Math.abs(pos1.getY() - pos2.getY()) + 1)
                    * (Math.abs(pos1.getZ() - pos2.getZ()) + 1);
        }
    }

    private static class MiningJob {
        final RegistryKey<World> dim;
        final int minX, minY, minZ, maxX, maxZ;
        int x, y, z;
        boolean done = false;
        int mined = 0;

        MiningJob(RegistryKey<World> dim, BlockPos a, BlockPos b) {
            this.dim = dim;
            minX = Math.min(a.getX(), b.getX());
            maxX = Math.max(a.getX(), b.getX());
            minY = Math.min(a.getY(), b.getY());
            minZ = Math.min(a.getZ(), b.getZ());
            maxZ = Math.max(a.getZ(), b.getZ());
            x = minX;
            y = Math.max(a.getY(), b.getY()); // od gory w dol
            z = minZ;
        }

        BlockPos next() {
            if (done) return null;
            BlockPos p = new BlockPos(x, y, z);
            z++;
            if (z > maxZ) {
                z = minZ;
                x++;
                if (x > maxX) {
                    x = minX;
                    y--;
                    if (y < minY) done = true;
                }
            }
            return p;
        }
    }
}
