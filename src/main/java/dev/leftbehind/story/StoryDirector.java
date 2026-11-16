package dev.leftbehind.story;

import dev.leftbehind.LeftBehind;
import dev.leftbehind.entity.Herobrine;
import dev.leftbehind.entity.OriginalSteve;
import dev.leftbehind.entity.Remnant;
import dev.leftbehind.fx.Fx;
import dev.leftbehind.memory.AngerStage;
import dev.leftbehind.memory.Mood;
import dev.leftbehind.memory.Relationship;
import dev.leftbehind.memory.SteveMemory;
import dev.leftbehind.registry.ModEntities;
import dev.leftbehind.registry.ModSounds;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The authored half of the story. Steve's behaviour decides when things happen;
 * the director decides what is allowed to happen, and in which act.
 */
public final class StoryDirector {
    /** Ticks into a world before Steve first shows up (about 2 minutes). */
    private static final long FIRST_APPEARANCE = 2400;
    /** A daytime episode lasts about 3 minutes. A night episode lasts until sunrise. */
    private static final int DAY_EPISODE_LENGTH = 3600;
    /** If he is killed, this is how long the world stays quiet before he is back (two days). */
    private static final long RETURN_AFTER_DEATH = 48000;

    private static @Nullable Herobrine activeHerobrine;
    private static @Nullable Remnant activeGhost;
    private static final Map<UUID, Long> NEXT_AMBIENT = new HashMap<>();
    private static boolean wasDark;
    private static boolean wasRaining;

    private StoryDirector() {}

    /** Forget everything that isn't saved with the world. Called when a server starts. */
    public static void resetTransient() {
        activeHerobrine = null;
        activeGhost = null;
        NEXT_AMBIENT.clear();
        Sense.clear();
        wasDark = false;
        wasRaining = false;
    }

    // ---- main loop ------------------------------------------------------------

    public static void tick(MinecraftServer server) {
        ServerLevel level = server.overworld();
        if (level.players().isEmpty()) return;
        long time = level.getGameTime();
        if (time % 5 == 0) Sense.tick(server, time);
        if (time % 20 != 0) return;
        SteveMemory memory = SteveMemory.get(server);
        RandomSource random = level.getRandom();

        OriginalSteve steve = findSteve(level);
        if (steve == null && memory.steveDead && time - memory.counter("steve_died_at") > RETURN_AFTER_DEATH) {
            memory.steveDead = false;
            memory.stability = Math.max(memory.stability, 40f);
            memory.setFlag("came_back");
            memory.setDirty();
        }
        if (steve == null && !memory.steveDead && time > FIRST_APPEARANCE) {
            // If the chunk he was in has gone to sleep mid-episode, the episode is over.
            if (memory.mood().inEpisode()) memory.setMood(Mood.NORMAL);
            boolean returning = memory.flag("came_back") && memory.setFlag("came_back_said");
            steve = spawnSteveNear(level, memory, randomPlayer(level));
            if (steve != null && returning) {
                SteveVoice.steve(steve, "...I came back. I always come back. That's the worst part.");
                memory.clearFlag("came_back");
                memory.clearFlag("came_back_said");
            }
        }
        if (steve == null) return;
        if (memory.counter("act") == 0) Progress.setAct(server, memory, 1, true);

        Mood mood = memory.mood();
        // He is always around. If everyone wanders off, he finds them again.
        if (mood == Mood.NORMAL && nearestPlayerDistanceSqr(level, steve) > 96 * 96) {
            ServerPlayer player = randomPlayer(level);
            if (player != null) moveNear(level, steve, player.blockPosition(), 16, 28);
        }

        if (mood == Mood.FLEEING || mood == Mood.TRAUMA || mood == Mood.RAMPAGE) {
            long end = memory.counter("episode_end");
            long since = time - memory.counter("episode_start");
            boolean over = end > 0 ? time >= end : (level.isBrightOutside() && since > 600);
            if (over) steve.beginReturn(level, memory);
        }

        if (time % 100 == 0) {
            Progress.checkActs(server, memory);
            Relics.tick(level, memory, time);
        }
        if (time % 1200 == 0) everyMinute(level, memory, steve);

        dayAndWeather(level, memory, steve);
        ambience(level, memory, time);

        if (mood.inEpisode()) return;
        Reactions.watchPlayers(level, steve, memory);

        // Low stability: he starts seeing things.
        if (memory.stability < 35f && random.nextInt(600) == 0) {
            Reactions.remark(steve, "unstable", 30, "Did you see that?", "He's here. He's always here.",
                    "Not you. Not again.", "Don't look at the trees.", "Count with me. One. Two. ...what comes after two?");
        }

        int act = memory.act();
        boolean herobrineGone = activeHerobrine == null || activeHerobrine.isRemoved();
        if (act >= 2 && herobrineGone && time >= memory.counter("next_herobrine")) {
            int interval = act == 2 ? 6000 : act == 3 ? 4200 : 3000;
            memory.setCounter("next_herobrine", time + interval + random.nextInt(interval));
            float chance = level.isDarkOutside() ? 0.55f : 0.14f;
            chance += Math.min(memory.breaches, 5) * 0.03f;
            if (random.nextFloat() < chance) {
                boolean atSteve = act == 2
                        ? memory.breaches == 0 && time - memory.counter("act_start") > 12000
                        : random.nextInt(100) < 55;
                ServerPlayer player = randomPlayer(level);
                if (atSteve) spawnHerobrine(level, steve);
                else if (player != null) spawnHerobrineWatching(level, player);
            }
        }

        boolean ghostGone = activeGhost == null || activeGhost.isRemoved();
        if (act >= 2 && ghostGone && time >= memory.counter("next_ghost")) {
            int interval = act >= 4 ? 2400 : 3600;
            memory.setCounter("next_ghost", time + interval + random.nextInt(interval));
            boolean rightTime = level.isDarkOutside() || level.isRaining() || random.nextInt(4) == 0;
            ServerPlayer player = randomPlayer(level);
            if (rightTime && player != null && (herobrineGone || random.nextBoolean())) {
                spawnGhost(level, player, (int) memory.counter("ghost_count") % 3);
            }
        }
    }

    private static void everyMinute(ServerLevel level, SteveMemory memory, OriginalSteve steve) {
        if (memory.mood().inEpisode()) return;
        if (memory.stability < 100f) memory.addStability(1f);
        for (Relationship r : memory.relationships.values()) {
            if (r.anger > 0) r.addAnger(-1f);
        }
        // Just being around him counts for something, up to a point.
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator() || player.distanceToSqr(steve) > 20 * 20) continue;
            Relationship r = memory.relationship(player.getUUID());
            if (!r.stage().atLeast(AngerStage.RESENTMENT) && r.trust < 50f) r.addTrust(0.6f);
            if (r.trust >= 60f) Progress.grant(player, "trusted");
        }
        memory.setDirty();
    }

    private static void dayAndWeather(ServerLevel level, SteveMemory memory, OriginalSteve steve) {
        boolean dark = level.isDarkOutside();
        boolean raining = level.isRaining();
        if (dark != wasDark) {
            wasDark = dark;
            if (!memory.mood().inEpisode() && nearestPlayerDistanceSqr(level, steve) < 24 * 24) {
                if (dark) {
                    Reactions.remark(steve, "nightfall", 120, "It's getting dark. You should be inside.", "Night. I don't sleep. I just wait.",
                            "The first night is always the worst. You never forget your first night.");
                    if (memory.highestTrust() >= 25f) Journal.unlock(level.getServer(), "mem_first_night");
                } else {
                    Reactions.remark(steve, "sunrise", 120, "Morning. We made it.", "The sun came back. It doesn't always feel like it will.",
                            "Good morning.");
                }
            }
        }
        if (raining != wasRaining) {
            wasRaining = raining;
            if (raining && !memory.mood().inEpisode() && nearestPlayerDistanceSqr(level, steve) < 24 * 24) {
                Reactions.remark(steve, "rain", 300, "Rain. There didn't use to be rain.", "I like the sound. It covers the other sounds.");
            }
        }
    }

    // ---- ambience -------------------------------------------------------------------

    private static void ambience(ServerLevel level, SteveMemory memory, long time) {
        int act = memory.act();
        RandomSource random = level.getRandom();
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) continue;
            Long next = NEXT_AMBIENT.get(player.getUUID());
            if (next == null) {
                NEXT_AMBIENT.put(player.getUUID(), time + 600 + random.nextInt(600));
                continue;
            }
            if (time < next) continue;
            int gap = switch (act) {
                case 1 -> 2400;
                case 2 -> 1500;
                case 3 -> 1000;
                default -> 700;
            };
            NEXT_AMBIENT.put(player.getUUID(), time + gap + random.nextInt(gap));
            if (isHunted(player)) continue;
            boolean dark = level.isDarkOutside();
            boolean indoors = !level.canSeeSky(player.blockPosition().above());
            boolean underground = indoors && player.getY() < 55;
            int roll = random.nextInt(100);
            if (act == 1) {
                if (dark && roll < 50) Fx.soundTo(player, ModSounds.WIND_HOWL, SoundSource.AMBIENT, 0.5f, 1.0f);
                else if (underground && roll < 60) Fx.soundTo(player, ModSounds.DREAD, SoundSource.AMBIENT, 0.35f, 1.0f);
                continue;
            }
            if (!dark && !indoors && roll < 55) continue; // Daylight in the open is mostly safe. Mostly.
            if (roll < 22) {
                Fx.soundTo(player, ModSounds.DREAD, SoundSource.AMBIENT, underground ? 0.7f : 0.5f, 1.0f);
            } else if (roll < 36 && !indoors) {
                Fx.soundTo(player, ModSounds.WIND_HOWL, SoundSource.AMBIENT, 0.6f, 0.9f + random.nextFloat() * 0.2f);
            } else if (roll < 50 && indoors) {
                // Something knocks, behind you, on something that might be your door.
                Fx.soundTo(player, Fx.behind(player, 5.0), ModSounds.KNOCK, SoundSource.AMBIENT, 1.0f, 0.9f + random.nextFloat() * 0.2f);
            } else if (roll < 60) {
                Fx.soundTo(player, Fx.behind(player, 7.0), SoundEvents.AMBIENT_CAVE, SoundSource.AMBIENT, 0.7f, 0.8f);
            } else if (roll < 72 && act >= 3) {
                Fx.soundTo(player, Fx.behind(player, 2.5), ModSounds.WHISPER, SoundSource.AMBIENT, 0.8f, 1.0f);
            } else if (roll < 82 && act >= 3) {
                Fx.soundTo(player, Fx.behind(player, 1.5), ModSounds.BREATH, SoundSource.AMBIENT, 0.8f, 1.0f);
            } else if (roll < 90 && act >= 3 && memory.stability < 70f) {
                Fx.soundTo(player, ModSounds.STATIC, SoundSource.AMBIENT, 0.5f, 1.0f);
                Fx.staticFlash(player, 6);
            } else if (roll < 94 && act >= 3 && dark) {
                // For three frames, in the dark, something looks back.
                Fx.eyes(player, 3);
            } else if (roll < 100 && act >= 2) {
                Vec3 behind = Fx.behind(player, 4.0);
                level.playSound(null, behind.x, behind.y, behind.z, SoundEvents.GRASS_STEP, SoundSource.HOSTILE, 0.6f, 0.8f);
            }
        }
    }

    // ---- settings -------------------------------------------------------------------

    public static boolean griefingAllowed(ServerLevel level) {
        return level.getGameRules().get(GameRules.MOB_GRIEFING) && !SteveMemory.get(level.getServer()).flag("set_no_grief");
    }

    public static boolean huntAllowed(ServerLevel level) {
        return !SteveMemory.get(level.getServer()).flag("set_no_hunt");
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
        return spawnSteveAt(level, memory, player.blockPosition(), 16, 28);
    }

    public static @Nullable OriginalSteve spawnSteveAt(ServerLevel level, SteveMemory memory, BlockPos center, int min, int max) {
        OriginalSteve steve = ModEntities.ORIGINAL_STEVE.create(level, EntitySpawnReason.EVENT);
        if (steve == null) return null;
        if (!moveNear(level, steve, center, min, max)) return null;
        level.addFreshEntity(steve);
        boolean firstTime = memory.steveId == null;
        memory.steveId = steve.getUUID();
        memory.setDirty();
        if (firstTime) LeftBehind.LOGGER.info("Original Steve has arrived.");
        return steve;
    }

    /** Places an entity on the surface somewhere in a ring around a point. */
    public static boolean moveNear(ServerLevel level, Entity entity, BlockPos center, int minDist, int maxDist) {
        BlockPos pos = surfaceNear(level, center, minDist, maxDist);
        if (pos == null) return false;
        entity.snapTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, level.getRandom().nextFloat() * 360f, 0f);
        return true;
    }

    /** A dry place to stand on the surface, somewhere in a ring around a point. */
    public static @Nullable BlockPos surfaceNear(ServerLevel level, BlockPos center, int minDist, int maxDist) {
        RandomSource random = level.getRandom();
        for (int attempt = 0; attempt < 16; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            int dist = minDist + random.nextInt(Math.max(1, maxDist - minDist));
            int x = center.getX() + (int) (Math.cos(angle) * dist);
            int z = center.getZ() + (int) (Math.sin(angle) * dist);
            if (!level.hasChunkAt(new BlockPos(x, 0, z))) continue;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockState below = level.getBlockState(new BlockPos(x, y - 1, z));
            if (below.getFluidState().isEmpty() && !below.isAir()) return new BlockPos(x, y, z);
        }
        return null;
    }

    private static double nearestPlayerDistanceSqr(ServerLevel level, Entity entity) {
        double best = Double.MAX_VALUE;
        for (ServerPlayer p : level.players()) best = Math.min(best, p.distanceToSqr(entity));
        return best;
    }

    public static @Nullable ServerPlayer randomPlayer(ServerLevel level) {
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

    public static @Nullable ServerPlayer mostTrusted(ServerLevel level, SteveMemory memory) {
        ServerPlayer best = null;
        float bestTrust = -1;
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) continue;
            float trust = memory.trust(player.getUUID());
            if (trust > bestTrust) {
                bestTrust = trust;
                best = player;
            }
        }
        return best;
    }

    // ---- Herobrine ---------------------------------------------------------------

    public static @Nullable Herobrine activeHerobrine() {
        return activeHerobrine == null || activeHerobrine.isRemoved() ? null : activeHerobrine;
    }

    public static boolean isHunted(ServerPlayer player) {
        Herobrine h = activeHerobrine();
        return h != null && h.mode() == Herobrine.Mode.HUNT && player.getUUID().equals(h.targetId());
    }

    private static @Nullable Herobrine placeHerobrine(ServerLevel level, Entity watched, int min, int max) {
        if (activeHerobrine != null && !activeHerobrine.isRemoved()) activeHerobrine.discard();
        Herobrine herobrine = ModEntities.HEROBRINE.create(level, EntitySpawnReason.EVENT);
        if (herobrine == null) return null;
        boolean placed = false;
        for (int attempt = 0; attempt < 12; attempt++) {
            if (!moveNear(level, herobrine, watched.blockPosition(), min, max)) continue;
            placed = true;
            if (watched instanceof net.minecraft.world.entity.LivingEntity living && living.hasLineOfSight(herobrine)) break;
        }
        if (!placed) return null;
        herobrine.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, watched.getEyePosition());
        level.addFreshEntity(herobrine);
        activeHerobrine = herobrine;
        return herobrine;
    }

    /** He stands where Steve will see him. */
    public static @Nullable Herobrine spawnHerobrine(ServerLevel level, OriginalSteve steve) {
        Herobrine herobrine = placeHerobrine(level, steve, 14, 30);
        if (herobrine != null) herobrine.watchSteve(900);
        return herobrine;
    }

    /** He stands a long way off and watches a player. */
    public static @Nullable Herobrine spawnHerobrineWatching(ServerLevel level, ServerPlayer player) {
        Herobrine herobrine = placeHerobrine(level, player, 28, 44);
        if (herobrine != null) herobrine.watchPlayer(player, 700 + level.getRandom().nextInt(500));
        return herobrine;
    }

    /** Testing: he appears close by and comes straight for the player. */
    public static @Nullable Herobrine spawnHunt(ServerLevel level, ServerPlayer player) {
        Herobrine herobrine = placeHerobrine(level, player, 14, 20);
        if (herobrine != null) herobrine.startHunt(level, player);
        return herobrine;
    }

    /**
     * Eye contact. Steve recognises someone he hoped never to see again.
     * He doesn't vanish. He runs, as far as he can get, and what happens next can be seen by anyone who follows.
     */
    public static void memoryBreach(ServerLevel level, OriginalSteve steve, @Nullable Herobrine herobrine) {
        SteveMemory memory = SteveMemory.get(level.getServer());
        long time = level.getGameTime();
        memory.breaches++;
        memory.addStability(-25f);
        memory.nightLog.clear();
        memory.setCounter("episode_start", time);
        memory.setCounter("episode_end", level.isDarkOutside() ? 0L : time + DAY_EPISODE_LENGTH);
        memory.setDirty();

        SteveVoice.steve(steve, level.getRandom(), "...no.", "You.", "I know you. Why do I know you?", "Not again. Please, not again.");
        Fx.sound(steve, ModSounds.BREACH, SoundSource.NEUTRAL, 1.6f, 1.0f);
        Fx.memoryDust(steve, 30);
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(steve) < 64 * 64) {
                player.sendSystemMessage(Component.literal("Steve remembers.").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC), true);
                Fx.staticFlash(player, 10);
                Fx.shake(player, 30);
                Progress.grant(player, "breach");
            }
        }
        if (memory.setFlag("breach_seen")) Journal.unlock(level.getServer(), "note_breach");
        steve.orderNothing();
        steve.beginFlee(level, memory, 700);
        LeftBehind.LOGGER.info("Memory breach #{} (stability now {})", memory.breaches, memory.stability);
    }

    // ---- ghosts ------------------------------------------------------------------

    public static @Nullable Remnant spawnGhost(ServerLevel level, ServerPlayer player, int variant) {
        SteveMemory memory = SteveMemory.get(level.getServer());
        Remnant ghost = ModEntities.REMNANT.create(level, EntitySpawnReason.EVENT);
        if (ghost == null) return null;
        boolean placed = false;
        for (int attempt = 0; attempt < 10; attempt++) {
            if (!moveNear(level, ghost, player.blockPosition(), 20, 34)) continue;
            placed = true;
            if (player.hasLineOfSight(ghost)) break;
        }
        if (!placed) return null;
        ghost.setVariant(variant);
        ghost.setLifetime(700 + level.getRandom().nextInt(500));
        // Some of them are carrying something that somebody wrote down.
        for (String page : new String[]{"page_1", "page_2", "page_3", "page_6"}) {
            if (!memory.journal.contains(page)) {
                if (level.getRandom().nextInt(100) < 60) ghost.setPage(page);
                break;
            }
        }
        level.addFreshEntity(ghost);
        activeGhost = ghost;
        memory.addCounter("ghost_count", 1);
        return ghost;
    }

    public static void onGhostSeen(ServerLevel level, Remnant ghost, ServerPlayer player) {
        SteveMemory memory = SteveMemory.get(level.getServer());
        if (memory.setFlag("ghost_seen")) Journal.unlock(level.getServer(), "note_ghosts");
        memory.setFlag("seen_" + ghost.getVariant());
        Progress.grant(player, "ghost");
        OriginalSteve steve = findSteve(level);
        if (steve == null || steve.inEpisode() || steve.distanceToSqr(ghost) > 40 * 40) return;
        steve.getLookControl().setLookAt(ghost, 30f, 30f);
        memory.addStability(-3f);
        String name = Remnant.NAMES[ghost.getVariant()];
        if (memory.setFlag("steve_saw_ghost")) {
            SteveVoice.steve(steve, "...I know them. I know them. They used to be here.");
            Journal.unlock(level.getServer(), "mem_others");
        } else {
            Reactions.remark(steve, "ghost", 30, name + ". That's " + name + ". Don't go near, they don't like that.",
                    "They still don't walk. Nobody ever taught them how.", "Do they see us? I never know if they see us.");
        }
    }

    // ---- smashing ----------------------------------------------------------------

    public static @Nullable String describeSmash(BlockState state) {
        if (state.is(Blocks.TORCH) || state.is(Blocks.WALL_TORCH) || state.is(BlockTags.LANTERNS)) return "put out a light";
        if (state.is(BlockTags.DOORS)) return "broke a door";
        if (state.is(Blocks.GLASS) || state.is(Blocks.GLASS_PANE)) return "broke a window";
        if (state.is(BlockTags.CROPS)) return "tore up crops";
        if (state.is(BlockTags.FLOWER_POTS)) return "smashed a flower pot";
        return null;
    }

    public static @Nullable BlockPos findSmashable(ServerLevel level, BlockPos center, RandomSource random) {
        for (int attempt = 0; attempt < 60; attempt++) {
            BlockPos pos = center.offset(random.nextInt(21) - 10, random.nextInt(7) - 3, random.nextInt(21) - 10);
            if (describeSmash(level.getBlockState(pos)) != null) return pos;
        }
        return null;
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

    public static void onSteveDeath(ServerLevel level, OriginalSteve steve, DamageSource source) {
        SteveMemory memory = SteveMemory.get(level.getServer());
        memory.setCounter("steve_died_at", level.getGameTime());
        if (source.getEntity() instanceof ServerPlayer killer) {
            Relationship r = memory.relationship(killer.getUUID());
            r.addTrust(-40f);
            r.addAnger(30f);
            LeftBehind.LOGGER.info("Original Steve was killed by {}.", killer.getName().getString());
        }
        for (ServerPlayer player : level.players()) {
            player.sendSystemMessage(Component.literal("It gets very quiet.").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC), true);
        }
        if (activeHerobrine != null && !activeHerobrine.isRemoved() && activeHerobrine.mode() != Herobrine.Mode.HUNT) {
            activeHerobrine.vanish(level, false);
        }
    }
}
