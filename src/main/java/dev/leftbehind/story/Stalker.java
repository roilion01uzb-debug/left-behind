package dev.leftbehind.story;

import dev.leftbehind.entity.Herobrine;
import dev.leftbehind.entity.OriginalSteve;
import dev.leftbehind.memory.Relationship;
import dev.leftbehind.memory.SteveMemory;
import dev.leftbehind.registry.ModEntities;
import dev.leftbehind.fx.Fx;
import dev.leftbehind.registry.ModSounds;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Herobrine learns you. He keeps a map of the places you go back to (saved with the world), notices which way you are
 * walking, and goes to stand where you are about to be. When you get there, he has just left.
 *
 * <p>He is also there on your very first evening: far off, at the edge of what you can see, gone when you look properly.
 */
public final class Stalker {
    private Stalker() {}

    /** Places are remembered in squares this many blocks wide. */
    private static final int CELL = 24;
    private static final String HAUNT = "haunt:";
    private static final Map<UUID, Track> TRACKS = new HashMap<>();

    /** What he is following about one player right now (not saved; the places are). */
    private static final class Track {
        @Nullable Vec3 last;
        Vec3 heading = Vec3.ZERO;
        long cell = Long.MIN_VALUE;
        long cellSince;
        boolean counted;
        @Nullable BlockPos waitedAt;
        long waitedUntil;
    }

    public static void resetTransient() {
        TRACKS.clear();
    }

    /** Once a second, from the story director. */
    static void second(ServerLevel level, SteveMemory memory, long time) {
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) continue;
            Track t = TRACKS.computeIfAbsent(player.getUUID(), id -> new Track());
            Vec3 pos = player.position();
            if (t.last != null && pos.distanceToSqr(t.last) < 40 * 40) {
                Vec3 step = new Vec3(pos.x - t.last.x, 0, pos.z - t.last.z);
                t.heading = t.heading.scale(0.85).add(step.scale(0.15));
            }
            t.last = pos;

            // A place counts once you have spent half a minute in it.
            long cell = cellKey(player.blockPosition());
            if (cell != t.cell) {
                t.cell = cell;
                t.cellSince = time;
                t.counted = false;
            } else if (!t.counted && time - t.cellSince >= 600) {
                t.counted = true;
                remember(memory, player, player.blockPosition());
            }

            // He was waiting where you were going, and you came.
            if (t.waitedAt != null) {
                if (time > t.waitedUntil) {
                    t.waitedAt = null;
                } else if (horizontalDistSqr(player.blockPosition(), t.waitedAt) < 6 * 6 && StoryDirector.activeHerobrine() == null) {
                    arrived(level, memory, player, t.waitedAt);
                    t.waitedAt = null;
                }
            }
        }
        firstEvening(level, memory);
    }

    // ---- the map of you -------------------------------------------------------------------

    private static void remember(SteveMemory memory, ServerPlayer player, BlockPos pos) {
        Relationship r = memory.relationship(player.getUUID());
        String key = HAUNT + Math.floorDiv(pos.getX(), CELL) + "," + Math.floorDiv(pos.getZ(), CELL);
        r.marks.merge(key, 1L, Long::sum);
        // Keep the 40 places that matter most.
        int haunts = 0;
        String weakest = null;
        long weakestN = Long.MAX_VALUE;
        for (Map.Entry<String, Long> e : r.marks.entrySet()) {
            if (!e.getKey().startsWith(HAUNT)) continue;
            haunts++;
            if (e.getValue() < weakestN && !e.getKey().equals(key)) {
                weakestN = e.getValue();
                weakest = e.getKey();
            }
        }
        if (haunts > 40 && weakest != null) r.marks.remove(weakest);
        memory.setDirty();
    }

    /** How many of your places he knows. */
    public static int known(SteveMemory memory, UUID player) {
        Relationship r = memory.relationships.get(player);
        if (r == null) return 0;
        int n = 0;
        for (String key : r.marks.keySet()) if (key.startsWith(HAUNT)) n++;
        return n;
    }

    /**
     * Where you are about to be: a place you keep going back to, in the direction you are walking, far enough that you
     * won't be there for a while. Your home at night. Null if he hasn't learned enough yet.
     */
    public static @Nullable BlockPos ahead(ServerLevel level, ServerPlayer player) {
        SteveMemory memory = SteveMemory.get(level.getServer());
        Relationship r = memory.relationships.get(player.getUUID());
        Track t = TRACKS.get(player.getUUID());
        Vec3 heading = t == null ? Vec3.ZERO : t.heading;
        boolean moving = heading.lengthSqr() > 0.6 * 0.6;
        Vec3 dir = moving ? heading.normalize() : Vec3.ZERO;
        BlockPos at = player.blockPosition();
        BlockPos best = null;
        double bestScore = 0;
        if (r != null) {
            for (Map.Entry<String, Long> e : r.marks.entrySet()) {
                if (!e.getKey().startsWith(HAUNT) || e.getValue() < 2) continue;
                String[] xz = e.getKey().substring(HAUNT.length()).split(",");
                int cx = Integer.parseInt(xz[0]) * CELL + CELL / 2;
                int cz = Integer.parseInt(xz[1]) * CELL + CELL / 2;
                double dx = cx - at.getX();
                double dz = cz - at.getZ();
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist < 30 || dist > 80) continue;
                double score = e.getValue();
                if (moving) {
                    double cos = (dx * dir.x + dz * dir.z) / dist;
                    if (cos < 0.55) continue;
                    score *= 1 + cos;
                } else {
                    score *= 0.6;
                }
                if (score > bestScore) {
                    bestScore = score;
                    best = new BlockPos(cx, at.getY(), cz);
                }
            }
        }
        BlockPos home = StoryDirector.homeOf(player, level);
        if (home != null && level.isDarkOutside()) {
            double d = Math.sqrt(horizontalDistSqr(home, at));
            if (d > 30 && d < 90 && bestScore < 8) best = home;
        }
        return best == null ? null : groundNear(level, best);
    }

    /** Somewhere to stand near a spot: on the ground, under the sky or not, never inside a wall. */
    private static @Nullable BlockPos groundNear(ServerLevel level, BlockPos center) {
        for (int attempt = 0; attempt < 16; attempt++) {
            int dx = attempt == 0 ? 0 : level.getRandom().nextInt(13) - 6;
            int dz = attempt == 0 ? 0 : level.getRandom().nextInt(13) - 6;
            int x = center.getX() + dx;
            int z = center.getZ() + dz;
            if (!level.hasChunkAt(new BlockPos(x, center.getY(), z))) continue;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos feet = new BlockPos(x, y, z);
            if (level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP) && level.getBlockState(feet).isAir()
                    && level.getBlockState(feet.above()).isAir() && level.getFluidState(feet.below()).isEmpty()) {
                return feet;
            }
        }
        return null;
    }

    /** He goes and stands where you're going, and waits. */
    public static @Nullable Herobrine waitAhead(ServerLevel level, ServerPlayer player) {
        BlockPos spot = ahead(level, player);
        if (spot == null) return null;
        Herobrine herobrine = place(level, spot, player);
        if (herobrine == null) return null;
        herobrine.watchPlayer(player, 1400, true);
        Track t = TRACKS.computeIfAbsent(player.getUUID(), id -> new Track());
        t.waitedAt = spot;
        t.waitedUntil = level.getGameTime() + 3600;
        return herobrine;
    }

    static @Nullable Herobrine place(ServerLevel level, BlockPos spot, ServerPlayer facing) {
        Herobrine old = StoryDirector.activeHerobrine();
        if (old != null) old.discard();
        Herobrine herobrine = ModEntities.HEROBRINE.create(level, EntitySpawnReason.EVENT);
        if (herobrine == null) return null;
        herobrine.snapTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 0f, 0f);
        herobrine.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, facing.getEyePosition());
        level.addFreshEntity(herobrine);
        StoryDirector.adopt(herobrine);
        return herobrine;
    }

    /** You got to the place he was waiting. He has only just gone. */
    private static void arrived(ServerLevel level, SteveMemory memory, ServerPlayer player, BlockPos spot) {
        int times = (int) memory.addCounter("waited_for", 1);
        Fx.soundTo(player, ModSounds.PRESENCE, SoundSource.HOSTILE, 0.6f, 0.8f);
        Fx.staticFlash(player, 6);
        String line = times == 1 ? "you always come this way"
                : times == 2 ? "i knew you would come here"
                : pick(level, "i was here first", "you take the same path every time", "i learned you", "this is where you always go",
                "you come back here a lot", "i know all of your places");
        Herobrine.whisper(player, line);
        if (memory.setFlag("waited_seen")) Journal.unlock(level.getServer(), "note_waiting");
        StoryLog.beat(level.getServer(), player, "waiting");
        OriginalSteve steve = StoryDirector.findSteve(level);
        if (steve != null && !steve.inEpisode() && steve.distanceToSqr(player) < 20 * 20) {
            Reactions.remark(steve, "waited", 300, "He was standing right there. Where you always go.",
                    "He knows where you walk. He learns people. He learned me.", "Don't take the same way twice. Please.");
        }
    }

    // ---- the first evening --------------------------------------------------------------------

    /**
     * Day one, as the sun goes down. Someone is standing a long way off, at the edge of what you can see, facing you.
     * Look at him properly and he isn't there. That's all, the first time.
     */
    private static void firstEvening(ServerLevel level, SteveMemory memory) {
        if (memory.flag("first_glimpse") || StoryDirector.activeHerobrine() != null) return;
        long clock = level.getOverworldClockTime();
        long hour = clock % 24000L;
        if (clock / 24000L > 1 || hour < 11800 || hour > 13800) return;
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator() || !level.canSeeSky(player.blockPosition().above())) continue;
            if (glimpse(level, player)) {
                memory.setFlag("first_glimpse");
                return;
            }
        }
    }

    /** He stands far away, roughly where you're facing, at the edge of the light. Vanishes when looked at for long. */
    public static boolean glimpse(ServerLevel level, ServerPlayer player) {
        return glimpse(level, player, false);
    }

    /** The same, but he is wearing Steve's face: no white eyes. Steve is somewhere else at the time. */
    public static boolean otherSteve(ServerLevel level, ServerPlayer player) {
        if (StoryDirector.activeHerobrine() != null || !level.canSeeSky(player.blockPosition().above())) return false;
        return glimpse(level, player, true);
    }

    private static boolean glimpse(ServerLevel level, ServerPlayer player, boolean masked) {
        Vec3 view = player.getViewVector(1f);
        Vec3 flat = new Vec3(view.x, 0, view.z);
        if (flat.lengthSqr() < 1e-4) flat = new Vec3(1, 0, 0);
        flat = flat.normalize();
        for (int attempt = 0; attempt < 14; attempt++) {
            double angle = Math.toRadians(level.getRandom().nextInt(71) - 35);
            double dist = 40 + level.getRandom().nextInt(18);
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            Vec3 d = new Vec3(flat.x * cos - flat.z * sin, 0, flat.x * sin + flat.z * cos).scale(dist);
            BlockPos spot = groundNear(level, BlockPos.containing(player.position().add(d)));
            if (spot == null) continue;
            Herobrine herobrine = place(level, spot, player);
            if (herobrine == null) return false;
            if (!player.hasLineOfSight(herobrine)) {
                herobrine.discard();
                StoryDirector.clearActiveHerobrine();
                continue;
            }
            herobrine.glimpse(player, 500);
            if (masked) {
                herobrine.setMasked(true);
                return true;
            }
            SteveMemory.get(level.getServer()).setFlag("first_glimpse");
            return true;
        }
        return false;
    }

    /** After the first glimpse, Steve wonders out loud. */
    public static void afterGlimpse(ServerLevel level, ServerPlayer player) {
        OriginalSteve steve = StoryDirector.findSteve(level);
        SteveMemory memory = SteveMemory.get(level.getServer());
        if (memory.counter("glimpses") == 0) Journal.unlock(level.getServer(), "note_glimpse");
        StoryLog.beat(level.getServer(), player, "glimpse");
        memory.addCounter("glimpses", 1);
        if (steve == null || steve.inEpisode() || steve.distanceToSqr(player) > 32 * 32) return;
        Reactions.remark(steve, "glimpse", 120, "Did you see someone? Over there. ...No. Never mind. Trees.",
                "Was someone standing there? Just now?", "Don't look over there. Look at me. Okay. Okay.");
    }

    // ---- helpers ------------------------------------------------------------------------

    private static long cellKey(BlockPos pos) {
        return ((long) Math.floorDiv(pos.getX(), CELL) << 32) ^ (Math.floorDiv(pos.getZ(), CELL) & 0xffffffffL);
    }

    private static double horizontalDistSqr(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    private static String pick(ServerLevel level, String... lines) {
        return lines[level.getRandom().nextInt(lines.length)];
    }

    /** Forget a player who left (their places stay saved). */
    public static void forget(UUID player) {
        for (Iterator<UUID> it = TRACKS.keySet().iterator(); it.hasNext(); ) if (it.next().equals(player)) it.remove();
    }
}
