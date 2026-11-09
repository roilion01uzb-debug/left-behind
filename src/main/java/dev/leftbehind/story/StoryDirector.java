package dev.leftbehind.story;

import dev.leftbehind.LeftBehind;
import dev.leftbehind.entity.Herobrine;
import dev.leftbehind.entity.OriginalSteve;
import dev.leftbehind.memory.AngerStage;
import dev.leftbehind.memory.Relationship;
import dev.leftbehind.memory.SteveMemory;
import dev.leftbehind.registry.ModEntities;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

/**
 * The authored half of the story. Steve's behaviour decides when things happen;
 * the director decides what is allowed to happen.
 */
public final class StoryDirector {
    /** Ticks into a world before Steve first shows up (about 2 minutes). */
    private static final long FIRST_APPEARANCE = 2400;
    /** How often Herobrine considers appearing (about 5 minutes). */
    private static final int HEROBRINE_INTERVAL = 6000;
    /** A daytime episode lasts about 4 minutes. A night episode lasts until sunrise. */
    private static final int DAY_EPISODE_LENGTH = 4800;

    private static @Nullable Herobrine activeHerobrine;

    private StoryDirector() {}

    // ---- main loop ------------------------------------------------------------

    public static void tick(MinecraftServer server) {
        ServerLevel level = server.overworld();
        if (level.players().isEmpty() || level.getGameTime() % 20 != 0) return;
        SteveMemory memory = SteveMemory.get(server);
        long time = level.getGameTime();
        RandomSource random = level.getRandom();

        OriginalSteve steve = findSteve(level);
        if (steve == null && !memory.steveDead && time > FIRST_APPEARANCE) {
            steve = spawnSteveNear(level, memory, randomPlayer(level));
        }
        if (steve == null) return;

        // He is always around. If everyone wanders off, he finds them again.
        if (!memory.lostNight && nearestPlayerDistanceSqr(level, steve) > 96 * 96) {
            ServerPlayer player = randomPlayer(level);
            if (player != null) moveNear(level, steve, player.blockPosition(), 16, 28);
        }

        if (memory.lostNight) {
            boolean over = memory.episodeEnd > 0 ? time >= memory.episodeEnd : level.isBrightOutside();
            if (over) endLostNight(level, steve, memory);
            return;
        }

        // Slow recovery when nothing bad happens.
        if (time % 1200 == 0) {
            if (memory.stability < 100f) memory.addStability(1f);
            for (Relationship r : memory.relationships.values()) {
                if (r.anger > 0) r.addAnger(-1f);
            }
            memory.setDirty();
        }

        // Low stability: he starts seeing things.
        if (memory.stability < 35f && random.nextInt(900) == 0) {
            SteveVoice.steve(steve, random, "Did you see that?", "He's here. He's always here.",
                    "Not you. Not again.", "Don't look at the trees.");
        }

        if (time >= memory.nextHerobrineCheck && (activeHerobrine == null || activeHerobrine.isRemoved())) {
            memory.nextHerobrineCheck = time + HEROBRINE_INTERVAL + random.nextInt(HEROBRINE_INTERVAL);
            memory.setDirty();
            float chance = level.isDarkOutside() ? 0.35f : 0.06f;
            chance += Math.min(memory.breaches, 5) * 0.03f;
            if (random.nextFloat() < chance) spawnHerobrine(level, steve);
        }
    }

    // ---- Steve presence ---------------------------------------------------------

    public static @Nullable OriginalSteve findSteve(ServerLevel level) {
        SteveMemory memory = SteveMemory.get(level.getServer());
        if (memory.steveId == null) return null;
        Entity entity = level.getEntity(memory.steveId);
        return entity instanceof OriginalSteve steve && steve.isAlive() ? steve : null;
    }

    public static @Nullable OriginalSteve spawnSteveNear(ServerLevel level, SteveMemory memory, @Nullable ServerPlayer player) {
        if (player == null) return null;
        OriginalSteve steve = ModEntities.ORIGINAL_STEVE.create(level, EntitySpawnReason.EVENT);
        if (steve == null) return null;
        if (!moveNear(level, steve, player.blockPosition(), 16, 28)) return null;
        level.addFreshEntity(steve);
        boolean firstTime = memory.steveId == null;
        memory.steveId = steve.getUUID();
        memory.setDirty();
        if (firstTime) LeftBehind.LOGGER.info("Original Steve has arrived.");
        return steve;
    }

    /** Places an entity on the surface somewhere in a ring around a point. */
    public static boolean moveNear(ServerLevel level, Entity entity, BlockPos center, int minDist, int maxDist) {
        RandomSource random = level.getRandom();
        for (int attempt = 0; attempt < 16; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            int dist = minDist + random.nextInt(Math.max(1, maxDist - minDist));
            int x = center.getX() + (int) (Math.cos(angle) * dist);
            int z = center.getZ() + (int) (Math.sin(angle) * dist);
            if (!level.hasChunkAt(new BlockPos(x, 0, z))) continue;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockState below = level.getBlockState(new BlockPos(x, y - 1, z));
            if (below.getFluidState().isEmpty() && !below.isAir()) {
                entity.snapTo(x + 0.5, y, z + 0.5, random.nextFloat() * 360f, 0f);
                return true;
            }
        }
        return false;
    }

    private static double nearestPlayerDistanceSqr(ServerLevel level, Entity entity) {
        double best = Double.MAX_VALUE;
        for (ServerPlayer p : level.players()) best = Math.min(best, p.distanceToSqr(entity));
        return best;
    }

    private static @Nullable ServerPlayer randomPlayer(ServerLevel level) {
        List<ServerPlayer> players = new ArrayList<>(level.players());
        players.removeIf(ServerPlayer::isSpectator);
        return players.isEmpty() ? null : players.get(level.getRandom().nextInt(players.size()));
    }

    /** A player's home: their bed or respawn anchor in the overworld, if they have one. */
    public static @Nullable BlockPos homeOf(ServerPlayer player, ServerLevel level) {
        ServerPlayer.RespawnConfig config = player.getRespawnConfig();
        if (config == null || !config.respawnData().globalPos().dimension().equals(Level.OVERWORLD)) return null;
        return config.respawnData().pos();
    }

    // ---- Herobrine ---------------------------------------------------------------

    public static @Nullable Herobrine spawnHerobrine(ServerLevel level, OriginalSteve steve) {
        Herobrine herobrine = ModEntities.HEROBRINE.create(level, EntitySpawnReason.EVENT);
        if (herobrine == null) return null;
        for (int attempt = 0; attempt < 12; attempt++) {
            if (!moveNear(level, herobrine, steve.blockPosition(), 14, 32)) continue;
            if (steve.hasLineOfSight(herobrine)) break;
        }
        herobrine.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, steve.getEyePosition());
        level.addFreshEntity(herobrine);
        activeHerobrine = herobrine;
        return herobrine;
    }

    /**
     * Eye contact. Steve recognises someone he hoped never to see again.
     * The rest of the night belongs to the memory.
     */
    public static void memoryBreach(ServerLevel level, OriginalSteve steve, @Nullable Herobrine herobrine) {
        SteveMemory memory = SteveMemory.get(level.getServer());
        memory.breaches++;
        memory.addStability(-25f);
        memory.lostNight = true;
        memory.nightLog.clear();
        memory.episodeEnd = level.isDarkOutside() ? 0L : level.getGameTime() + DAY_EPISODE_LENGTH;
        memory.setDirty();

        SteveVoice.steve(steve, level.getRandom(), "...no.", "You.", "I know you. Why do I know you?", "Not again. Please, not again.");
        level.playSound(null, steve.blockPosition(), SoundEvents.AMBIENT_CAVE.value(), SoundSource.AMBIENT, 2.0f, 0.5f);
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(steve) < 64 * 64) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal("Steve is gone.")
                        .withStyle(net.minecraft.ChatFormatting.DARK_GRAY, net.minecraft.ChatFormatting.ITALIC), true);
            }
        }
        steve.setInvisible(true);
        steve.setSilent(true);
        steve.getNavigation().stop();
        LeftBehind.LOGGER.info("Memory breach #{} (stability now {})", memory.breaches, memory.stability);
    }

    /** Runs once a second while Steve is lost in the memory. */
    public static void lostNightTick(ServerLevel level, OriginalSteve steve) {
        SteveMemory memory = SteveMemory.get(level.getServer());
        RandomSource random = level.getRandom();
        ServerPlayer target = mostTrusted(level, memory);
        if (target == null) return;
        BlockPos home = homeOf(target, level);
        BlockPos center = home != null ? home : target.blockPosition();

        // He goes to the place that matters most to the person who trusts him most.
        if (steve.blockPosition().distSqr(center) > 14 * 14) {
            moveNear(level, steve, center, 6, 12);
            return;
        }
        if (random.nextInt(8) != 0) {
            // Something moving around outside. Footsteps where nobody is.
            if (random.nextInt(3) == 0) {
                level.playSound(null, steve.blockPosition(), SoundEvents.WOOD_STEP, SoundSource.HOSTILE, 0.8f, 0.8f);
            }
            return;
        }
        if (!level.getGameRules().get(GameRules.MOB_GRIEFING)) return;
        String act = destroyNear(level, steve, center, random);
        if (act != null) {
            memory.nightLog.add(act);
            memory.setDirty();
            level.playSound(null, steve.blockPosition(), SoundEvents.WOOD_BREAK, SoundSource.HOSTILE, 1.0f, 0.7f);
        }
    }

    private static @Nullable String destroyNear(ServerLevel level, OriginalSteve steve, BlockPos center, RandomSource random) {
        for (int attempt = 0; attempt < 40; attempt++) {
            BlockPos pos = center.offset(random.nextInt(21) - 10, random.nextInt(7) - 3, random.nextInt(21) - 10);
            BlockState state = level.getBlockState(pos);
            String what = null;
            if (state.is(Blocks.TORCH) || state.is(Blocks.WALL_TORCH) || state.is(BlockTags.LANTERNS)) what = "put out a light";
            else if (state.is(BlockTags.DOORS)) what = "broke a door";
            else if (state.is(Blocks.GLASS) || state.is(Blocks.GLASS_PANE)) what = "broke a window";
            else if (state.is(BlockTags.CROPS)) what = "tore up crops";
            else if (state.is(BlockTags.FLOWER_POTS)) what = "smashed a flower pot";
            if (what != null) {
                level.destroyBlock(pos, false, steve);
                steve.snapTo(pos.getX() + 0.5, steve.getY(), pos.getZ() + 0.5, steve.getYRot(), 0f);
                return what + " at " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
            }
        }
        return null;
    }

    private static void endLostNight(ServerLevel level, OriginalSteve steve, SteveMemory memory) {
        memory.lostNight = false;
        memory.episodeEnd = 0;
        memory.setDirty();
        ServerPlayer target = mostTrusted(level, memory);

        // The next-morning consequence: he left his axe at the place he doesn't remember visiting.
        ItemStack axe = steve.getMainHandItem();
        if (!axe.isEmpty() && !memory.nightLog.isEmpty()) {
            steve.spawnAtLocation(level, axe.copy());
            steve.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        }
        if (target != null) moveNear(level, steve, target.blockPosition(), 4, 8);
        steve.setInvisible(false);
        steve.setSilent(false);

        if (memory.nightLog.isEmpty()) {
            SteveVoice.steve(steve, level.getRandom(), "Morning.", "I had a bad dream. I think.");
        } else {
            SteveVoice.steve(steve, level.getRandom(), "Good morning! ...why are you looking at me like that?",
                    "Morning. Did something happen last night?", "Hey. Sleep okay? I slept great.");
        }
    }

    private static @Nullable ServerPlayer mostTrusted(ServerLevel level, SteveMemory memory) {
        ServerPlayer best = null;
        float bestTrust = -1;
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) continue;
            Relationship r = memory.relationships.get(player.getUUID());
            float trust = r == null ? 20f : r.trust;
            if (trust > bestTrust) {
                bestTrust = trust;
                best = player;
            }
        }
        return best;
    }

    // ---- anger ---------------------------------------------------------------------

    /** One deliberate act against a player's home, by anger stage. Returns what he did, or null. */
    public static @Nullable String provoke(ServerLevel level, OriginalSteve steve, ServerPlayer victim, BlockPos home, AngerStage stage) {
        RandomSource random = level.getRandom();
        if (stage.atLeast(AngerStage.ESCALATION) && level.getBlockState(home).is(BlockTags.BEDS)) {
            level.destroyBlock(home, false, steve);
            SteveVoice.steve(steve, "You don't live here anymore.");
            return "took your bed";
        }
        if (stage.atLeast(AngerStage.TERRITORIAL)) {
            SteveVoice.steve(steve, random, "This is my house.", "I was here first. I'm always here first.");
            return "claimed your house";
        }
        for (int attempt = 0; attempt < 40; attempt++) {
            BlockPos pos = home.offset(random.nextInt(17) - 8, random.nextInt(5) - 2, random.nextInt(17) - 8);
            BlockState state = level.getBlockState(pos);
            if ((state.is(Blocks.TORCH) || state.is(Blocks.WALL_TORCH))) {
                level.destroyBlock(pos, false, steve);
                return "took a torch";
            }
            if (stage.atLeast(AngerStage.RETALIATION) && (state.is(BlockTags.DOORS) || state.is(Blocks.GLASS_PANE) || state.is(Blocks.GLASS))) {
                level.destroyBlock(pos, false, steve);
                return "broke your door";
            }
        }
        return null;
    }

    // ---- dialogue ------------------------------------------------------------------

    public static void talk(OriginalSteve steve, ServerPlayer player, SteveMemory memory, Relationship r) {
        RandomSource random = steve.getRandom();
        if (r.stage().atLeast(AngerStage.PROVOCATION)) {
            SteveVoice.steve(steve, random, "Leave me alone.", "I don't want to talk to you.", "You know what you did.");
        } else if (!memory.nightLog.isEmpty() && memory.breaches > 0 && random.nextInt(2) == 0) {
            SteveVoice.steve(steve, random, "Your house looks different. Did you change something?",
                    "I was here all night. Wasn't I?", "Why does everything smell like smoke?");
        } else if (memory.stability < 50f) {
            SteveVoice.steve(steve, random, "Do you ever feel like you've done all this before?",
                    "I had a beard once. I think.", "There were others who looked like me. Where did they go?",
                    "Don't let him look at me.");
        } else if (r.trust >= 60f) {
            SteveVoice.steve(steve, random, "I'm glad you're here.", "You're not like the others.",
                    "I built a house once. Somebody broke it. I don't remember who.");
        } else {
            SteveVoice.steve(steve, random, "Hi.", "Nice day.", "What are you building?", "I'm just watching.");
        }
    }

    public static void idleThought(OriginalSteve steve, SteveMemory memory, ServerLevel level) {
        if (nearestPlayerDistanceSqr(level, steve) > 24 * 24) return;
        RandomSource random = level.getRandom();
        if (memory.stability < 60f) {
            SteveVoice.steve(steve, random, "This place feels familiar.", "Has this world always been here?",
                    "I keep forgetting something important.");
        } else if (random.nextInt(3) == 0) {
            SteveVoice.steve(steve, random, "*hums something old*", "Huh.", "I like it here.");
        }
    }

    public static void onSteveDeath(ServerLevel level, OriginalSteve steve, DamageSource source) {
        Optional<ServerPlayer> killer = source.getEntity() instanceof ServerPlayer p ? Optional.of(p) : Optional.empty();
        LeftBehind.LOGGER.info("Original Steve died ({}).", killer.map(p -> "killed by " + p.getName().getString()).orElse("not by a player"));
        if (activeHerobrine != null && !activeHerobrine.isRemoved()) {
            activeHerobrine.vanish(level);
        }
    }
}
