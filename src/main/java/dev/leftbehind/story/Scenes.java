package dev.leftbehind.story;

import dev.leftbehind.LeftBehind;
import dev.leftbehind.entity.Herobrine;
import dev.leftbehind.entity.OriginalSteve;
import dev.leftbehind.entity.Remnant;
import dev.leftbehind.fx.Fx;
import dev.leftbehind.memory.Mood;
import dev.leftbehind.memory.SteveMemory;
import dev.leftbehind.registry.ModEntities;
import java.util.List;
import java.util.UUID;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Scenes you can witness: Herobrine with Steve, before he has any interest in you.
 *
 * <p><b>The hill.</b> At dusk Steve walks up somewhere high, a long way from you, and talks to someone standing next to him.
 * You only ever hear Steve. Get close, or look too long, and there is only Steve: "Who? There's nobody."
 *
 * <p><b>The sleepwalk.</b> From Act II, some nights Steve gets up and walks off in a straight line, eyes half shut.
 * Follow him and you find Herobrine waiting where the path ends, looking at the ground. Steve digs there,
 * and finds something somebody buried. Meet Herobrine's eyes and he says "not yet", and is gone.
 *
 * <p>There is only one Steve and one scene at a time, so the state is kept here. Herobrine is rare: at most one
 * Herobrine moment per game day before Act III, shared with the director's other appearances.
 */
public final class Scenes {
    public enum Kind { NONE, HILL, SLEEPWALK }

    private static final int WALK = 0;
    private static final int TOGETHER = 1;
    private static final int AFTER = 2;

    private static Kind kind = Kind.NONE;
    private static int phase;
    private static int ticks;
    private static int phaseTicks;
    private static @Nullable BlockPos spot;
    private static @Nullable Herobrine partner;
    private static @Nullable UUID witness;
    private static int lineIndex;
    private static int nextLine;
    private static int lookTicks;
    private static int dug;
    private static boolean seen;
    private static boolean answered;
    private static boolean appeared;

    private Scenes() {}

    public static void resetTransient() {
        end(null);
    }

    public static Kind kind() {
        return kind;
    }

    /** True while Steve belongs to a scene: he doesn't follow, chat or start conversations. */
    public static boolean busy() {
        return kind != Kind.NONE;
    }

    /** For the game test: Steve is at the spot and Herobrine (if nobody scared him off) is with him. */
    public static boolean together() {
        return busy() && phase == TOGETHER;
    }

    public static boolean sleepwalking() {
        return kind == Kind.SLEEPWALK && phase != AFTER;
    }

    // ---- starting ------------------------------------------------------------------------

    /** Once a second, from the story director: is it time for a scene? */
    public static void consider(ServerLevel level, SteveMemory memory, OriginalSteve steve) {
        if (busy() || memory.mood() != Mood.NORMAL || StoryDirector.activeHerobrine() != null) return;
        int act = memory.act();
        long clock = level.getOverworldClockTime();
        long day = clock / 24000L;
        long hour = clock % 24000L;
        if (act < 3 && memory.counter("herobrine_day") == day + 1) return; // he has already been here today
        if (day < 1 || StoryDirector.mostTrusted(level, memory) == null) return; // not before he has met someone
        RandomSource random = level.getRandom();

        // Dusk: the hill. Once a dusk at most, the first one soon, then every few days.
        if (hour > 11600 && hour < 12500 && memory.counter("hill_checked") != day + 1 && act <= 3) {
            memory.setCounter("hill_checked", day + 1);
            long last = memory.counter("hill_day");
            int chance = last == 0 ? 70 : (day + 1 - last >= 3 ? 35 : 0);
            if (random.nextInt(100) < chance) startHill(level, memory, steve);
            return;
        }
        // Deep night, from Act II: the sleepwalk.
        if (act >= 2 && hour > 17500 && hour < 21000 && memory.counter("walk_checked") != day + 1) {
            memory.setCounter("walk_checked", day + 1);
            long last = memory.counter("walk_day");
            int chance = last == 0 ? 60 : (day + 1 - last >= 3 ? 30 : 0);
            if (random.nextInt(100) < chance) startSleepwalk(level, memory, steve);
        }
    }

    /** For testing: start a scene now. Returns false if there was nowhere to stage it. */
    public static boolean start(ServerLevel level, OriginalSteve steve, Kind which) {
        SteveMemory memory = SteveMemory.get(level.getServer());
        if (busy()) end(steve);
        return which == Kind.HILL ? startHill(level, memory, steve) : which == Kind.SLEEPWALK && startSleepwalk(level, memory, steve);
    }

    private static boolean startHill(ServerLevel level, SteveMemory memory, OriginalSteve steve) {
        ServerPlayer player = nearestPlayer(level, steve);
        if (player == null) return false;
        // Somewhere high, far enough from you that you can just about make him out.
        BlockPos best = null;
        int bestY = Integer.MIN_VALUE;
        for (int attempt = 0; attempt < 24; attempt++) {
            BlockPos pos = StoryDirector.surfaceNear(level, player.blockPosition(), 30, 44);
            if (pos == null || pos.distSqr(steve.blockPosition()) > 46 * 46) continue;
            if (pos.getY() <= bestY) continue;
            Path path = steve.getNavigation().createPath(pos, 1);
            if (path == null || !path.canReach()) continue;
            best = pos;
            bestY = pos.getY();
        }
        if (best == null) return false;
        begin(Kind.HILL, best, player);
        memory.setCounter("hill_day", level.getOverworldClockTime() / 24000L + 1);
        memory.setDirty();
        steve.orderNothing();
        steve.stopTalking();
        LeftBehind.LOGGER.info("Scene: the hill at {}", best);
        return true;
    }

    private static boolean startSleepwalk(ServerLevel level, SteveMemory memory, OriginalSteve steve) {
        ServerPlayer player = nearestPlayer(level, steve);
        // Someone has to be close enough to see him go.
        if (player == null || player.distanceToSqr(steve) > 28 * 28) return false;
        BlockPos target = null;
        for (int attempt = 0; attempt < 16 && target == null; attempt++) {
            BlockPos pos = StoryDirector.surfaceNear(level, steve.blockPosition(), 30, 42);
            if (pos == null || pos.distSqr(player.blockPosition()) < 24 * 24) continue;
            Path path = steve.getNavigation().createPath(pos, 1);
            if (path != null && path.canReach()) target = pos;
        }
        if (target == null) return false;
        begin(Kind.SLEEPWALK, target, player);
        memory.setCounter("walk_day", level.getOverworldClockTime() / 24000L + 1);
        memory.setDirty();
        steve.orderNothing();
        steve.stopTalking();
        SteveVoice.steve(steve, level.getRandom(), "...it's this way. It's always this way.", "...he's showing me.", "...coming. I'm coming.");
        LeftBehind.LOGGER.info("Scene: the sleepwalk to {}", target);
        return true;
    }

    private static void begin(Kind which, BlockPos at, ServerPlayer watcher) {
        kind = which;
        phase = WALK;
        ticks = 0;
        phaseTicks = 0;
        spot = at;
        witness = watcher.getUUID();
        partner = null;
        lineIndex = 0;
        nextLine = 0;
        lookTicks = 0;
        dug = 0;
        seen = false;
        answered = false;
        appeared = false;
    }

    // ---- running -------------------------------------------------------------------------

    /** Every tick while a scene has him, from Steve's scene goal. */
    public static void steveTick(ServerLevel level, OriginalSteve steve) {
        if (!busy() || spot == null) return;
        SteveMemory memory = SteveMemory.get(level.getServer());
        if (memory.mood().inEpisode()) {
            end(steve);
            return;
        }
        ticks++;
        phaseTicks++;
        if (partner != null && partner.isRemoved()) partner = null;
        if (kind == Kind.HILL) hillTick(level, memory, steve);
        else sleepwalkTick(level, memory, steve);
    }

    private static void hillTick(ServerLevel level, SteveMemory memory, OriginalSteve steve) {
        BlockPos at = spot;
        if (at == null) return;
        int act = memory.act();
        switch (phase) {
            case WALK -> {
                if (walkTo(steve, at, 0.9)) {
                    ServerPlayer near = nearestPlayer(level, steve);
                    if (near != null && near.distanceToSqr(steve) < 18 * 18) {
                        // You came with him. He waits for you to go, and you don't, so nothing happens.
                        if (phaseTicks > 400) {
                            SteveVoice.steve(steve, "...it's nothing. I just like it up here. Let's go back.");
                            end(steve);
                        }
                        return;
                    }
                    if (appear(level, steve, memory, at, 1.6)) next(TOGETHER);
                    else end(steve);
                } else if (phaseTicks > 900) {
                    end(steve);
                }
            }
            case TOGETHER -> {
                Herobrine h = partner;
                steve.getNavigation().stop();
                if (h != null) {
                    // They stand side by side and look out the same way. Steve turns to him now and then.
                    if ((ticks / 60) % 3 == 0) steve.getLookControl().setLookAt(h, 20f, 20f);
                    else steve.getLookControl().setLookAt(Vec3.atCenterOf(at).add(outward(at, steve).scale(20)).add(0, 2, 0));
                    ServerPlayer watcher = watcher(level);
                    if (watcher != null && act >= 2 && phaseTicks > 200) {
                        // In Act II he has noticed you noticing.
                        h.lookAt(EntityAnchorArgument.Anchor.EYES, watcher.getEyePosition());
                    } else {
                        h.lookAt(EntityAnchorArgument.Anchor.EYES, steve.getEyePosition());
                    }
                    if (caught(level, h, act)) {
                        next(AFTER);
                        return;
                    }
                }
                if (phaseTicks >= nextLine && !SteveVoice.isSpeaking(steve)) {
                    List<String> lines = act <= 1 ? HILL_LINES_1 : HILL_LINES_2;
                    if (lineIndex >= lines.size()) {
                        if (h != null) h.discard();
                        next(AFTER);
                        return;
                    }
                    SteveVoice.steve(steve, lines.get(lineIndex++));
                    steve.swingArm();
                    nextLine = phaseTicks + 70 + level.getRandom().nextInt(60);
                }
            }
            case AFTER -> {
                steve.getNavigation().stop();
                ServerPlayer near = nearestPlayer(level, steve);
                if (near != null && near.distanceToSqr(steve) < 9 * 9 && !answered) {
                    answered = true;
                    steve.getLookControl().setLookAt(near, 30f, 30f);
                    if (act <= 1) SteveVoice.steve(steve, level.getRandom(), "Who? There's nobody.", "Talking? No. I was thinking out loud.",
                            "Nobody comes up here. Just me.");
                    else SteveVoice.steve(steve, level.getRandom(), "You saw him too? ...no. No, there's nobody. Forget it.",
                            "He said you'd come up here. ...who said? I don't know why I said that.");
                }
                if (phaseTicks > (answered ? 160 : 1200)) end(steve);
            }
            default -> end(steve);
        }
    }

    private static void sleepwalkTick(ServerLevel level, SteveMemory memory, OriginalSteve steve) {
        BlockPos at = spot;
        if (at == null) return;
        switch (phase) {
            case WALK -> {
                steve.setState(OriginalSteve.STATE_STARE); // eyes half shut, arms down
                if (!appeared && steve.blockPosition().distSqr(at) < 14 * 14) {
                    // He is already there when Steve comes over the rise. He was always going to be there.
                    appear(level, steve, memory, at.relative(steve.getDirection(), 3), 0);
                }
                Herobrine h = partner;
                if (h != null) {
                    h.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(at.below()));
                    if (caught(level, h, 2)) partner = null;
                }
                if (walkTo(steve, at, 0.55)) next(TOGETHER);
                else if (phaseTicks > 1600) end(steve);
            }
            case TOGETHER -> {
                // He digs where he's told to.
                steve.setState(OriginalSteve.STATE_STARE);
                steve.getNavigation().stop();
                steve.getLookControl().setLookAt(Vec3.atCenterOf(at.below()));
                Herobrine h = partner;
                if (h != null) {
                    h.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(at.below()));
                    if (caught(level, h, 2)) partner = null;
                }
                if (phaseTicks % 6 == 0) steve.swingArm();
                if (phaseTicks % 30 == 0 && dug < 2) {
                    BlockPos dig = at.below(dug + 1);
                    BlockState state = level.getBlockState(dig);
                    if (StoryDirector.griefingAllowed(level) && soft(state)) level.destroyBlock(dig, true, steve);
                    dug++;
                }
                if (phaseTicks >= 100) {
                    bury(level, memory, at.below(Math.max(1, dug)));
                    if (partner != null) partner.discard();
                    partner = null;
                    next(AFTER);
                }
            }
            case AFTER -> {
                steve.setState(OriginalSteve.STATE_NORMAL);
                if (phaseTicks == 30) {
                    ServerPlayer near = nearestPlayer(level, steve);
                    boolean followed = near != null && near.distanceToSqr(steve) < 12 * 12;
                    if (followed) {
                        steve.getLookControl().setLookAt(near, 30f, 30f);
                        SteveVoice.steve(steve, level.getRandom(), "...you followed me? Good. Don't let me do that alone.",
                                "Where are we? ...was I walking again?");
                    } else {
                        SteveVoice.steve(steve, level.getRandom(), "...where am I? Why are my hands dirty?", "Not again. Not again.");
                    }
                    if (near != null && near.distanceToSqr(steve) < 48 * 48 && memory.setFlag("sleepwalk_seen")) {
                        Journal.unlock(level.getServer(), "note_sleepwalk");
                    }
                    memory.addSanity(followed ? -2f : -5f);
                    memory.setDirty();
                }
                if (phaseTicks > 140) end(steve);
            }
            default -> end(steve);
        }
    }

    /** What he was led to: a page someone tore out and buried, or, once those are found, one of Rana's roses. */
    private static void bury(ServerLevel level, SteveMemory memory, BlockPos at) {
        ItemStack found = Remnant.rose();
        for (String page : new String[]{"page_6", "page_3", "page_2", "page_1"}) {
            if (!memory.journal.contains(page)) {
                found = Journal.tornPage(page);
                break;
            }
        }
        ItemEntity item = new ItemEntity(level, at.getX() + 0.5, at.getY() + 0.6, at.getZ() + 0.5, found, 0, 0.18, 0);
        item.setPickUpDelay(30);
        item.setUnlimitedLifetime();
        level.addFreshEntity(item);
        Fx.memoryDust(item, 12);
    }

    // ---- him ---------------------------------------------------------------------------------

    /** Puts Herobrine next to a spot without anyone seeing him arrive. */
    private static boolean appear(ServerLevel level, OriginalSteve steve, SteveMemory memory, BlockPos near, double beside) {
        Herobrine existing = StoryDirector.activeHerobrine();
        if (existing != null) return false;
        Herobrine h = ModEntities.HEROBRINE.create(level, EntitySpawnReason.EVENT);
        if (h == null) return false;
        Vec3 at;
        if (beside > 0) {
            Vec3 side = outward(near, steve).cross(new Vec3(0, 1, 0)).normalize().scale(beside);
            at = steve.position().add(side);
        } else {
            at = Vec3.atBottomCenterOf(near);
        }
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(at.x), Mth.floor(at.z));
        if (Math.abs(y - at.y) > 3) y = (int) Math.round(at.y);
        h.snapTo(at.x, y, at.z, steve.getYRot(), 0f);
        h.guide(2400);
        level.addFreshEntity(h);
        StoryDirector.adopt(h);
        partner = h;
        appeared = true;
        memory.setCounter("herobrine_day", level.getOverworldClockTime() / 24000L + 1);
        memory.setDirty();
        return true;
    }

    /**
     * Did someone catch him at it? In Act I he is gone the moment you get close or hold your eyes on him, without a sound.
     * Later he lets you see him a little longer, then says "not yet".
     */
    private static boolean caught(ServerLevel level, Herobrine h, int act) {
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) continue;
            double dist = player.distanceTo(h);
            if (dist > 72) continue;
            boolean looking = h.isLookedAtBy(player);
            if (looking && !seen) {
                seen = true;
                SteveMemory memory = SteveMemory.get(level.getServer());
                if (kind == Kind.HILL && memory.setFlag("hill_seen")) Journal.unlock(level.getServer(), "note_hill");
            }
            if (looking) lookTicks++;
            boolean tooClose = dist < (act <= 1 ? 20 : 12);
            if (tooClose || lookTicks > (act <= 1 ? 60 : 100)) {
                if (act <= 1) {
                    h.discard();
                } else {
                    Herobrine.whisper(player, "not yet");
                    h.vanish(level, false);
                }
                return true;
            }
        }
        return false;
    }

    // ---- helpers -------------------------------------------------------------------------

    /** Steve heard someone type while he was in a scene. Mostly he doesn't answer. */
    public static void heard(ServerPlayer player, OriginalSteve steve) {
        if (sleepwalking() && !answered && player.distanceToSqr(steve) < 8 * 8) {
            answered = true;
            SteveVoice.steve(steve, "Shh. He's showing me where.");
        }
    }

    private static boolean walkTo(OriginalSteve steve, BlockPos at, double speed) {
        if (steve.blockPosition().distSqr(at) < 2 * 2) {
            steve.getNavigation().stop();
            return true;
        }
        if (steve.getNavigation().isDone() || phaseTicks % 40 == 0) {
            steve.getNavigation().moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, speed);
        }
        return false;
    }

    /** The way to face from a spot: away from whoever might be watching, so their backs are to you. */
    private static Vec3 outward(BlockPos at, OriginalSteve steve) {
        ServerPlayer watcher = steve.level() instanceof ServerLevel level ? watcher(level) : null;
        Vec3 from = watcher != null ? watcher.position() : steve.position().add(steve.getLookAngle().scale(-1));
        Vec3 dir = Vec3.atCenterOf(at).subtract(from);
        dir = new Vec3(dir.x, 0, dir.z);
        return dir.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : dir.normalize();
    }

    private static @Nullable ServerPlayer watcher(ServerLevel level) {
        return witness == null ? null : level.getServer().getPlayerList().getPlayer(witness);
    }

    private static @Nullable ServerPlayer nearestPlayer(ServerLevel level, OriginalSteve steve) {
        ServerPlayer best = null;
        double bestDist = Double.MAX_VALUE;
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) continue;
            double d = player.distanceToSqr(steve);
            if (d < bestDist) {
                bestDist = d;
                best = player;
            }
        }
        return best;
    }

    private static boolean soft(BlockState state) {
        return state.is(BlockTags.DIRT) || state.is(BlockTags.SAND) || state.is(net.minecraft.world.level.block.Blocks.GRAVEL)
                || state.is(net.minecraft.world.level.block.Blocks.SNOW_BLOCK) || state.is(net.minecraft.world.level.block.Blocks.CLAY);
    }

    private static void next(int newPhase) {
        phase = newPhase;
        phaseTicks = 0;
        nextLine = 20;
    }

    private static void end(@Nullable OriginalSteve steve) {
        if (partner != null && !partner.isRemoved()) partner.discard();
        partner = null;
        if (steve != null) {
            steve.setState(OriginalSteve.STATE_NORMAL);
            steve.getNavigation().stop();
        }
        kind = Kind.NONE;
        spot = null;
        witness = null;
    }

    // What Steve says on the hill. You never hear the other voice.
    private static final List<String> HILL_LINES_1 = List.of(
            "I know. I know they can't see you.",
            "No. Not this one. This one talks to me.",
            "You said that last time. And the time before.",
            "...I'm trying. I don't remember it the way you do.",
            "Why do you always stand where they can't see you?");
    private static final List<String> HILL_LINES_2 = List.of(
            "You keep showing me the same place. I don't know what's under it.",
            "They're not like the others. They stay.",
            "Don't. Don't tell me how it ended. I don't want to know yet.",
            "...alright. Tomorrow. I'll look tomorrow.");
}
