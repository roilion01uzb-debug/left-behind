package dev.leftbehind.story;

import dev.leftbehind.entity.OriginalSteve;
import dev.leftbehind.fx.Fx;
import dev.leftbehind.memory.SteveMemory;
import dev.leftbehind.registry.ModSounds;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Small wrong things that happen to you, one at a time, with long quiet gaps between them. None of them hurt. Each is
 * part of the story: he walks where you walk, the world forgets what you built, he keeps the things Steve said, he can
 * wear Steve's face, the animals know where he is, he was listening to you, and he can take a few seconds from you.
 * The first time each one happens, Steve tells you what it was (see {@link StoryLog}).
 */
public final class Haunt {
    private Haunt() {}

    public enum Kind { FOOTSTEPS, RESET, ECHO, OTHER_STEVE, ANIMALS, SIGN_WORDS, LOST_TIME, DOOR }

    /** The blocks each player placed most recently, for the flicker. */
    private static final Map<UUID, Deque<BlockPos>> PLACED = new HashMap<>();
    /** The last few things each player said, for the sign. */
    private static final Map<UUID, Deque<String>> SAID = new HashMap<>();
    /** Where each player was, once a second, for lost time. */
    private static final Map<UUID, Deque<Vec3>> TRAIL = new HashMap<>();
    private static final Map<UUID, Long> NEXT = new HashMap<>();
    /** Things Steve said out loud with a recording: line, then recording. */
    private static final Deque<String[]> STEVE_SAID = new ArrayDeque<>();
    private static final List<Running> RUNNING = new ArrayList<>();

    public static void resetTransient() {
        for (Running r : RUNNING) r.stop();
        RUNNING.clear();
        PLACED.clear();
        SAID.clear();
        TRAIL.clear();
        NEXT.clear();
        STEVE_SAID.clear();
    }

    // ---- what it remembers ------------------------------------------------------------------

    public static void placed(ServerPlayer player, BlockPos pos) {
        Deque<BlockPos> list = PLACED.computeIfAbsent(player.getUUID(), k -> new ArrayDeque<>());
        list.addLast(pos.immutable());
        while (list.size() > 48) list.removeFirst();
    }

    public static void said(ServerPlayer player, String text) {
        String clean = text.strip();
        if (clean.length() < 4 || clean.length() > 60 || clean.startsWith("/")) return;
        Deque<String> list = SAID.computeIfAbsent(player.getUUID(), k -> new ArrayDeque<>());
        list.addLast(clean);
        while (list.size() > 12) list.removeFirst();
    }

    public static void steveSaid(String line, String voice) {
        if (voice.isEmpty() || line.length() > 70) return;
        for (String[] old : STEVE_SAID) if (old[1].equals(voice)) return;
        STEVE_SAID.addLast(new String[]{line, voice});
        while (STEVE_SAID.size() > 24) STEVE_SAID.removeFirst();
    }

    // ---- when -------------------------------------------------------------------------------

    /** Once a second. Keeps the trail, and now and then picks one thing to happen to one player. */
    static void second(ServerLevel level, SteveMemory memory, @Nullable OriginalSteve steve) {
        long time = level.getGameTime();
        RandomSource random = level.getRandom();
        int act = memory.act();
        for (ServerPlayer player : level.players()) {
            Deque<Vec3> trail = TRAIL.computeIfAbsent(player.getUUID(), k -> new ArrayDeque<>());
            trail.addLast(player.position());
            while (trail.size() > 40) trail.removeFirst();
        }
        // Nothing before the second day, and nothing while something bigger is going on.
        if (level.getOverworldClockTime() < 24000L || memory.mood().inEpisode() || Scenes.busy()) return;
        if (StoryDirector.activeHerobrine() != null || !RUNNING.isEmpty()) return;
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator() || StoryDirector.isHunted(player)) continue;
            Long next = NEXT.get(player.getUUID());
            if (next == null) {
                NEXT.put(player.getUUID(), time + gap(act, random) / 2);
                continue;
            }
            if (time < next) continue;
            NEXT.put(player.getUUID(), time + gap(act, random));
            List<Kind> pool = pool(level, act, player);
            int last = (int) memory.counter("haunt_last") - 1;
            for (int attempt = 0; attempt < 3 && !pool.isEmpty(); attempt++) {
                Kind kind = pool.remove(random.nextInt(pool.size()));
                if (kind.ordinal() == last && !pool.isEmpty()) continue;
                if (start(level, memory, player, kind)) {
                    memory.setCounter("haunt_last", kind.ordinal() + 1);
                    return;
                }
            }
        }
    }

    private static int gap(int act, RandomSource random) {
        int base = switch (act) {
            case 1 -> 7200;
            case 2 -> 4200;
            case 3 -> 3000;
            default -> 2200;
        };
        return base + random.nextInt(base);
    }

    private static List<Kind> pool(ServerLevel level, int act, ServerPlayer player) {
        List<Kind> pool = new ArrayList<>();
        boolean dark = level.isDarkOutside();
        boolean outside = level.canSeeSky(player.blockPosition().above());
        if (dark && outside) pool.add(Kind.FOOTSTEPS);
        pool.add(Kind.ANIMALS);
        if (dark && !STEVE_SAID.isEmpty()) pool.add(Kind.ECHO);
        if (act >= 2) {
            pool.add(Kind.RESET);
            if (outside) pool.add(Kind.OTHER_STEVE);
            if (dark) pool.add(Kind.SIGN_WORDS);
            if (dark) pool.add(Kind.DOOR);
        }
        if (act >= 3) pool.add(Kind.LOST_TIME);
        return pool;
    }

    /** For the test command. */
    public static boolean force(ServerLevel level, ServerPlayer player, String name) {
        Kind kind;
        try {
            kind = Kind.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return false;
        }
        return start(level, SteveMemory.get(level.getServer()), player, kind);
    }

    private static boolean start(ServerLevel level, SteveMemory memory, ServerPlayer player, Kind kind) {
        boolean ok = switch (kind) {
            case FOOTSTEPS -> run(new Footsteps(player));
            case RESET -> reset(level, player);
            case ECHO -> echo(level, player);
            case OTHER_STEVE -> Stalker.otherSteve(level, player);
            case ANIMALS -> animals(level, player);
            case SIGN_WORDS -> signWords(level, player);
            case LOST_TIME -> lostTime(level, player);
            case DOOR -> door(level, player);
        };
        if (ok) dev.leftbehind.LeftBehind.LOGGER.info("Haunt: {} for {}", kind, player.getName().getString());
        return ok;
    }

    private static boolean run(Running r) {
        RUNNING.add(r);
        return true;
    }

    /** Every tick, for the things that take a while. */
    public static void tick(MinecraftServer server) {
        if (RUNNING.isEmpty()) return;
        Iterator<Running> it = RUNNING.iterator();
        while (it.hasNext()) {
            Running r = it.next();
            if (r.player.isRemoved() || !(r.player.level() instanceof ServerLevel level) || !r.tick(level, ++r.age)) {
                r.stop();
                it.remove();
            }
        }
    }

    private abstract static class Running {
        final ServerPlayer player;
        int age;

        Running(ServerPlayer player) {
            this.player = player;
        }

        /** Returns false when it is over. */
        abstract boolean tick(ServerLevel level, int age);

        /** Puts everything back, however it ended. */
        void stop() {}
    }

    // ---- he walks where you walk ------------------------------------------------------------

    /** Your footsteps, one step behind you, on whatever you just walked on. When you stop, there is one more. */
    private static final class Footsteps extends Running {
        private Vec3 last;
        private double moved;
        private int steps = 10 + 4;
        private int still;
        private final Deque<Integer> due = new ArrayDeque<>();
        private int endAt = -1;

        Footsteps(ServerPlayer player) {
            super(player);
            this.last = player.position();
        }

        @Override
        boolean tick(ServerLevel level, int age) {
            Vec3 now = player.position();
            double d = Math.sqrt((now.x - last.x) * (now.x - last.x) + (now.z - last.z) * (now.z - last.z));
            last = now;
            if (d > 0.02 && player.onGround() && !player.isShiftKeyDown()) {
                moved += d;
                still = 0;
                if (moved >= (player.isSprinting() ? 2.2 : 1.7) && steps > 0) {
                    moved = 0;
                    steps--;
                    due.addLast(age + 7);
                }
            } else {
                still++;
            }
            if (!due.isEmpty() && due.peekFirst() <= age) {
                due.removeFirst();
                step(level, endAt > 0 ? 2.4 : 1.6);
            }
            // You stop. It takes one more step.
            if (endAt < 0 && age > 60 && (still == 16 || steps <= 0)) {
                endAt = age + 50;
                due.clear();
                due.addLast(age + 12);
            }
            if (endAt > 0 && age >= endAt) {
                StoryLog.beat(level.getServer(), player, "footsteps");
                if (SteveMemory.get(level.getServer()).setFlag("haunt_footsteps")) Journal.unlock(level.getServer(), "note_footsteps");
                return false;
            }
            return age < 1800;
        }

        private void step(ServerLevel level, double behind) {
            Vec3 at = Fx.behind(player, behind).add(0, -0.9, 0);
            BlockPos ground = BlockPos.containing(at).below();
            BlockState state = level.getBlockState(ground);
            if (state.isAir()) state = level.getBlockState(player.getOnPos());
            SoundType type = state.getSoundType();
            Holder<SoundEvent> sound = BuiltInRegistries.SOUND_EVENT.wrapAsHolder(type.getStepSound());
            Fx.soundTo(player, at, sound, SoundSource.HOSTILE, type.getVolume() * 0.32f, type.getPitch() * 0.94f);
        }
    }

    // ---- the world forgets ------------------------------------------------------------------

    /** Everything you built goes, for a second, twice. Only on your screen. Then it comes back. */
    private static boolean reset(ServerLevel level, ServerPlayer player) {
        List<BlockPos> gone = new ArrayList<>();
        Deque<BlockPos> placed = PLACED.get(player.getUUID());
        if (placed != null) {
            for (BlockPos pos : placed) {
                if (pos.distSqr(player.blockPosition()) < 40 * 40 && !level.getBlockState(pos).isAir()) gone.add(pos);
            }
        }
        // And everything around you that somebody made: torches, planks, cobblestone, glass, doors, beds, chests.
        BlockPos c = player.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(c.offset(-16, -6, -16), c.offset(16, 6, 16))) {
            if (gone.size() >= 600) break;
            if (made(level.getBlockState(pos))) gone.add(pos.immutable());
        }
        // Never what you're standing on or right next to: only the world goes, not the floor under your feet.
        BlockPos feet = player.blockPosition();
        gone.removeIf(pos -> pos.closerThan(feet, 2.5) || pos.closerThan(feet.below(), 2.5));
        if (gone.size() < 3) return false;
        return run(new Flicker(player, gone));
    }

    private static boolean made(BlockState s) {
        return s.is(Blocks.TORCH) || s.is(Blocks.WALL_TORCH) || s.is(Blocks.LANTERN) || s.is(BlockTags.BEDS) || s.is(BlockTags.PLANKS)
                || s.is(BlockTags.DOORS) || s.is(BlockTags.WOODEN_STAIRS) || s.is(BlockTags.WOODEN_SLABS) || s.is(BlockTags.WOODEN_FENCES)
                || s.is(BlockTags.WOOL) || s.is(Blocks.COBBLESTONE) || s.is(Blocks.GLASS) || s.is(Blocks.GLASS_PANE) || s.is(Blocks.STONE_BRICKS)
                || s.is(Blocks.BRICKS) || s.is(Blocks.CRAFTING_TABLE) || s.is(Blocks.FURNACE) || s.is(Blocks.CHEST) || s.is(Blocks.BARREL);
    }

    private static final class Flicker extends Running {
        private final List<BlockPos> gone;

        Flicker(ServerPlayer player, List<BlockPos> gone) {
            super(player);
            this.gone = gone;
        }

        @Override
        boolean tick(ServerLevel level, int age) {
            if (age == 1) {
                Fx.soundTo(player, ModSounds.RESET, SoundSource.AMBIENT, 0.9f, 1.0f);
                Fx.staticFlash(player, 4);
                hide();
            } else if (age == 4) {
                show(level);
            } else if (age == 7) {
                hide();
                Fx.staticFlash(player, 3);
            } else if (age == 46) {
                show(level);
                StoryLog.beat(level.getServer(), player, "reset");
                if (SteveMemory.get(level.getServer()).setFlag("haunt_reset")) Journal.unlock(level.getServer(), "note_reset");
                return false;
            }
            return true;
        }

        private void hide() {
            for (BlockPos pos : gone) player.connection.send(new ClientboundBlockUpdatePacket(pos, Blocks.AIR.defaultBlockState()));
        }

        private void show(ServerLevel level) {
            for (BlockPos pos : gone) player.connection.send(new ClientboundBlockUpdatePacket(level, pos));
        }

        @Override
        void stop() {
            if (player.level() instanceof ServerLevel level && !player.isRemoved()) show(level);
        }
    }

    // ---- he keeps the things Steve said -----------------------------------------------------

    /** Steve's own voice, out in the dark, slower, saying something he said days ago. */
    private static boolean echo(ServerLevel level, ServerPlayer player) {
        if (STEVE_SAID.isEmpty()) return false;
        List<String[]> lines = new ArrayList<>(STEVE_SAID);
        String[] pick = lines.get(level.getRandom().nextInt(Math.max(1, lines.size() - 3)));
        Vec3 at = Fx.behind(player, 20 + level.getRandom().nextInt(8));
        Holder<SoundEvent> voice = Holder.direct(SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath("leftbehind_voice", pick[1])));
        Fx.soundTo(player, at, voice, SoundSource.VOICE, 2.0f, 0.78f);
        SteveVoice.whisper(player, null, "Steve?", 0x8A8A8A, pick[0], false);
        StoryLog.beat(level.getServer(), player, "echo");
        return true;
    }

    // ---- he wears Steve's face --------------------------------------------------------------

    /** Called when the other Steve is gone again. */
    public static void afterOtherSteve(ServerLevel level, ServerPlayer player) {
        StoryLog.beat(level.getServer(), player, "other_steve");
        if (SteveMemory.get(level.getServer()).setFlag("haunt_other")) Journal.unlock(level.getServer(), "note_other");
    }

    // ---- the animals know where he is -------------------------------------------------------

    /** Every animal around you stops and looks at the same empty place. */
    private static boolean animals(ServerLevel level, ServerPlayer player) {
        List<Animal> animals = level.getEntitiesOfClass(Animal.class, player.getBoundingBox().inflate(28), a -> a.isAlive() && !a.isLeashed());
        if (animals.size() < 3) return false;
        Vec3 view = player.getViewVector(1f);
        Vec3 flat = new Vec3(view.x, 0, view.z);
        if (flat.lengthSqr() < 1e-4) flat = new Vec3(1, 0, 0);
        flat = flat.normalize();
        // Off to one side, where you'd have to turn to look.
        double side = level.getRandom().nextBoolean() ? 1 : -1;
        Vec3 dir = new Vec3(-flat.z * side, 0, flat.x * side).add(flat.scale(-0.4)).normalize();
        Vec3 spot = player.position().add(dir.scale(14 + level.getRandom().nextInt(6)));
        int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(spot.x), Mth.floor(spot.z));
        if (Math.abs(y - player.getY()) > 12) y = Mth.floor(player.getY());
        return run(new Stare(player, animals, new Vec3(spot.x, y + 1.6, spot.z)));
    }

    private static final class Stare extends Running {
        private final List<Animal> animals;
        private final Vec3 spot;

        Stare(ServerPlayer player, List<Animal> animals, Vec3 spot) {
            super(player);
            this.animals = animals;
            this.spot = spot;
            for (Animal a : animals) {
                a.getGoalSelector().disableControlFlag(Goal.Flag.MOVE);
                a.getGoalSelector().disableControlFlag(Goal.Flag.LOOK);
            }
        }

        @Override
        boolean tick(ServerLevel level, int age) {
            for (Animal a : animals) {
                if (!a.isAlive()) continue;
                a.getNavigation().stop();
                a.getLookControl().setLookAt(spot.x, spot.y, spot.z, 40f, 40f);
                double dx = spot.x - a.getX();
                double dz = spot.z - a.getZ();
                float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90f;
                a.setYBodyRot(Mth.approachDegrees(a.yBodyRot, yaw, 12f));
            }
            if (age == 150 && SteveMemory.get(level.getServer()).act() >= 3) {
                Fx.soundTo(player, spot, ModSounds.COUNT, SoundSource.HOSTILE, 0.8f, 1.0f);
            }
            if (age >= 170) {
                StoryLog.beat(level.getServer(), player, "animals");
                return false;
            }
            return true;
        }

        @Override
        void stop() {
            for (Animal a : animals) {
                a.getGoalSelector().enableControlFlag(Goal.Flag.MOVE);
                a.getGoalSelector().enableControlFlag(Goal.Flag.LOOK);
            }
        }
    }

    // ---- he was listening -------------------------------------------------------------------

    /** A sign on your way, in the dark, with something you said on it. */
    private static boolean signWords(ServerLevel level, ServerPlayer player) {
        Deque<String> said = SAID.get(player.getUUID());
        if (said == null || said.isEmpty() || !StoryDirector.griefingAllowed(level)) return false;
        BlockPos home = StoryDirector.homeOf(player, level);
        List<String> options = new ArrayList<>(said);
        String words = options.get(level.getRandom().nextInt(options.size())).toLowerCase(Locale.ROOT);
        Vec3 view = player.getViewVector(1f);
        Vec3 flat = new Vec3(view.x, 0, view.z);
        if (flat.lengthSqr() < 1e-4) return false;
        flat = flat.normalize();
        for (int d = 12; d <= 20; d += 2) {
            BlockPos around = BlockPos.containing(player.position().add(flat.scale(d)));
            for (BlockPos pos : BlockPos.betweenClosed(around.offset(-2, -3, -2), around.offset(2, 3, 2))) {
                if (!level.getBlockState(pos).isAir() || !level.getBlockState(pos.above()).isAir()) continue;
                if (!level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), net.minecraft.core.Direction.UP)) continue;
                if (!level.getFluidState(pos.below()).isEmpty() || level.getMaxLocalRawBrightness(pos) > 7) continue;
                if (home != null && pos.distSqr(home) < 10 * 10) continue;
                BlockPos at = pos.immutable();
                // Facing you, the way you'd place it yourself.
                float yaw = (float) (Mth.atan2(player.getZ() - (at.getZ() + 0.5), player.getX() - (at.getX() + 0.5)) * Mth.RAD_TO_DEG) - 90f;
                int rotation = net.minecraft.world.level.block.state.properties.RotationSegment.convertToSegment(yaw);
                level.setBlock(at, Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, rotation), 3);
                if (level.getBlockEntity(at) instanceof SignBlockEntity sign) {
                    List<String> rows = rows(words);
                    sign.updateText(text -> write(text, rows), net.minecraft.world.level.block.entity.SignTextSlot.FRONT);
                    sign.updateText(text -> write(text, rows), net.minecraft.world.level.block.entity.SignTextSlot.BACK);
                    sign.setWaxed(true);
                }
                StoryLog.beat(level.getServer(), player, "sign_words");
                return true;
            }
        }
        return false;
    }

    private static SignText write(SignText text, List<String> rows) {
        SignText.Mutable out = text.asMutable();
        for (int i = 0; i < 4; i++) out = out.setLine(i, Component.literal(i < rows.size() ? rows.get(i) : ""));
        return out.asImmutable();
    }

    private static List<String> rows(String words) {
        List<String> rows = new ArrayList<>();
        StringBuilder row = new StringBuilder();
        for (String word : words.split("\\s+")) {
            if (row.length() > 0 && row.length() + 1 + word.length() > 15) {
                rows.add(row.toString());
                row.setLength(0);
                if (rows.size() == 4) return rows;
            }
            if (row.length() > 0) row.append(' ');
            row.append(word.length() > 15 ? word.substring(0, 15) : word);
        }
        if (row.length() > 0 && rows.size() < 4) rows.add(row.toString());
        return rows;
    }

    // ---- he takes a few seconds -------------------------------------------------------------

    /** Black for two seconds. When it comes back, you are where you were twenty seconds ago. */
    private static boolean lostTime(ServerLevel level, ServerPlayer player) {
        if (player.isPassenger() || player.isFallFlying() || player.isSleeping() || player.isInWater()) return false;
        Deque<Vec3> trail = TRAIL.get(player.getUUID());
        if (trail == null || trail.size() < 25) return false;
        List<Vec3> past = new ArrayList<>(trail);
        Vec3 back = past.get(past.size() - 22);
        if (back.distanceToSqr(player.position()) < 8 * 8 || back.distanceToSqr(player.position()) > 60 * 60) return false;
        BlockPos feet = BlockPos.containing(back);
        if (!level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                || !level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
                || level.getBlockState(feet.below()).getCollisionShape(level, feet.below()).isEmpty()
                || !level.getFluidState(feet).isEmpty() || !level.getFluidState(feet.below()).isEmpty()
                || level.getBlockState(feet).is(BlockTags.FIRE)) return false;
        if (!level.noCollision(player, player.getDimensions(player.getPose()).makeBoundingBox(back))) return false;
        return run(new LostTime(player, back));
    }

    private static final class LostTime extends Running {
        private final Vec3 back;

        LostTime(ServerPlayer player, Vec3 back) {
            super(player);
            this.back = back;
        }

        @Override
        boolean tick(ServerLevel level, int age) {
            if (age == 1) {
                Fx.blackout(player, 46);
                Fx.soundTo(player, ModSounds.WINDING_DOWN, SoundSource.AMBIENT, 0.7f, 1.0f);
            } else if (age == 16) {
                player.teleportTo(back.x, back.y, back.z);
                player.resetFallDistance();
            } else if (age == 50) {
                player.sendSystemMessage(Component.literal("...").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC), true);
                StoryLog.beat(level.getServer(), player, "lost_time");
                return false;
            }
            return true;
        }
    }

    // ---- your door --------------------------------------------------------------------------

    /** A wooden door near you, behind you, opens by itself. Slowly. Nobody comes through. */
    private static boolean door(ServerLevel level, ServerPlayer player) {
        Vec3 view = player.getViewVector(1f);
        BlockPos c = player.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(c.offset(-16, -4, -16), c.offset(16, 4, 16))) {
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof DoorBlock door) || !state.is(BlockTags.WOODEN_DOORS)) continue;
            if (state.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER || state.getValue(DoorBlock.OPEN)) continue;
            Vec3 to = Vec3.atCenterOf(pos).subtract(player.getEyePosition());
            double dist = to.length();
            if (dist < 4 || dist > 16 || view.dot(to.normalize()) > 0.2) continue;
            if (!level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, new AABB(pos).inflate(1.5)).isEmpty()) continue;
            BlockPos at = pos.immutable();
            Fx.soundTo(player, Vec3.atCenterOf(at), ModSounds.DOOR_OPEN, SoundSource.BLOCKS, 0.8f, 1.0f);
            door.setOpen(null, level, state, at, true);
            StoryLog.beat(level.getServer(), player, "door");
            return true;
        }
        return false;
    }
}
