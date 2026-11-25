package dev.leftbehind.entity;

import dev.leftbehind.memory.SteveMemory;
import dev.leftbehind.story.Journal;
import dev.leftbehind.story.Reactions;
import dev.leftbehind.story.SteveVoice;
import dev.leftbehind.story.StoryDirector;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Steve builds, like a player: he finds flat ground near you, gets the wood or stone it needs (chopping, digging,
 * making planks), clears the spot, and puts it up block by block. Walls with log corners, windows, a roof, a door,
 * torches inside. A "base" is a proper little house; a "shelter" is the dirt hut you make on your first night.
 *
 * <p>He builds with what you build with, when he can get it.
 */
public final class SteveBuild {
    private final OriginalSteve steve;
    private final SteveWork work;
    private @Nullable Plan plan;
    private long noMaterialSaid;

    /** One block of the build: what goes where. Air means "clear this". */
    record Spot(BlockPos pos, BlockState state) {}

    static final class Plan {
        final String kind;
        final BlockPos origin;
        final Direction front;
        final UUID owner;
        final Block wall;
        final List<Spot> spots = new ArrayList<>();
        int index;
        int digProgress;
        int stage;

        Plan(String kind, BlockPos origin, Direction front, UUID owner, Block wall) {
            this.kind = kind;
            this.origin = origin;
            this.front = front;
            this.owner = owner;
            this.wall = wall;
        }

        int size() {
            return kind.equals("base") ? 7 : 5;
        }

        int height() {
            return kind.equals("base") ? 3 : 2;
        }

        BlockPos center() {
            return origin.offset(size() / 2, 0, size() / 2);
        }
    }

    SteveBuild(OriginalSteve steve, SteveWork work) {
        this.steve = steve;
        this.work = work;
    }

    public boolean building() {
        return plan != null || saved != null;
    }

    /** "Want me to build a shelter?" Waiting for a yes, until then (game time). */
    private long offerUntil;
    private @Nullable UUID offerTo;

    public boolean offered(UUID player, long now) {
        return offerTo != null && offerTo.equals(player) && now < offerUntil;
    }

    public void clearOffer() {
        offerTo = null;
    }

    /** Dusk, and you have nowhere to sleep: he offers to put something up. */
    public void offerShelter(ServerLevel level, ServerPlayer player) {
        if (building() || StoryDirector.homeOf(player, level) != null) return;
        SteveMemory memory = SteveMemory.get(level.getServer());
        if (!memory.cooldown("offer_shelter", level.getGameTime(), 24000 * 2)) return;
        offerTo = player.getUUID();
        offerUntil = level.getGameTime() + 1200;
        SteveVoice.steve(steve, "It's getting dark and we have nowhere to go. Want me to build a shelter? Just say yes.");
    }

    public @Nullable String kind() {
        return plan == null ? null : plan.kind;
    }

    public void cancel() {
        plan = null;
    }

    public String progress() {
        Plan p = plan;
        if (p == null) return "nothing";
        return p.kind + " " + (100 * p.index / Math.max(1, p.spots.size())) + "%";
    }

    // ---- starting ----------------------------------------------------------------------------

    /** Someone asked him to build. Returns false if there's nowhere to build here (he says so). */
    public boolean start(ServerLevel level, SteveMemory memory, ServerPlayer player, String kind) {
        int size = kind.equals("base") ? 7 : 5;
        int height = kind.equals("base") ? 3 : 2;
        BlockPos origin = site(level, player, size, height);
        dev.leftbehind.LeftBehind.LOGGER.info("Steve, asked to build a {}: {}", kind, origin == null ? "no flat ground near " + player.blockPosition().toShortString() : "at " + origin.toShortString());
        if (origin == null) {
            SteveVoice.steve(steve, "There's no flat ground here. Show me somewhere flatter?");
            return false;
        }
        BlockPos center = origin.offset(size / 2, 0, size / 2);
        Direction front = Direction.getApproximateNearest(player.getX() - center.getX() - 0.5, 0, player.getZ() - center.getZ() - 0.5);
        if (!front.getAxis().isHorizontal()) front = Direction.SOUTH;
        Block wall = wallBlock(memory, kind);
        plan = new Plan(kind, origin, front, player.getUUID(), wall);
        blueprint(level, plan);
        work.cancel();
        if (kind.equals("base")) {
            SteveVoice.steve(steve, steve.getRandom(), "A real base. Yes. Here's good, it's flat. I'll need a lot of wood.",
                    "A proper house. Alright. Walls first, then the roof. Let's start.");
        } else {
            SteveVoice.steve(steve, "A shelter. Quick, before dark. Dirt is fine. Dirt is enough.");
        }
        if (wall != Blocks.DIRT && memory.habit("builds_with:" + BuiltInRegistries.BLOCK.getKey(wall)) > 0) {
            Reactions.remark(steve, "build_match", 600, "I'll use the same blocks you build with. So it matches.");
        }
        memory.observe("asked_build");
        return true;
    }

    /** The blocks you build with most, if he can get them himself. Otherwise planks (or dirt for a quick shelter). */
    private Block wallBlock(SteveMemory memory, String kind) {
        if (kind.equals("shelter")) return work.count(Items.COBBLESTONE) >= 40 ? Blocks.COBBLESTONE : Blocks.DIRT;
        String best = null;
        int bestCount = 0;
        for (String key : memory.habitKeys()) {
            if (!key.startsWith("builds_with:")) continue;
            int n = memory.habit(key);
            if (n > bestCount) {
                bestCount = n;
                best = key.substring("builds_with:".length());
            }
        }
        if (best != null) {
            Identifier id = Identifier.tryParse(best);
            Block block = id == null ? Blocks.AIR : BuiltInRegistries.BLOCK.getValue(id);
            Item item = block.asItem();
            if (block != Blocks.AIR && item != Items.AIR && (work.count(item) >= 60 || block.defaultBlockState().is(BlockTags.PLANKS)
                    || block == Blocks.COBBLESTONE || block == Blocks.STONE_BRICKS && work.count(item) > 0)) {
                return block;
            }
        }
        if (work.count(Items.COBBLESTONE) >= 70) return Blocks.COBBLESTONE;
        Item planks = work.planksType();
        return planks instanceof BlockItem bi ? bi.getBlock() : Blocks.OAK_PLANKS;
    }

    /** Flat, dry ground near the player with nothing built on it. Returns the corner, at floor height. */
    private @Nullable BlockPos site(ServerLevel level, ServerPlayer player, int size, int height) {
        Vec3 look = player.getLookAngle().multiply(1, 0, 1);
        double baseAngle = look.lengthSqr() < 0.01 ? 0 : Math.atan2(look.z, look.x);
        BlockPos best = null;
        for (int r = size / 2 + 2; r <= 12 && best == null; r += 2) {
            for (int k = 0; k < 12 && best == null; k++) {
                double angle = baseAngle + (k % 2 == 0 ? 1 : -1) * (k / 2) * Math.PI / 6;
                int cx = (int) Math.floor(player.getX() + Math.cos(angle) * r);
                int cz = (int) Math.floor(player.getZ() + Math.sin(angle) * r);
                best = fits(level, cx - size / 2, cz - size / 2, size, height);
            }
        }
        return best;
    }

    private @Nullable BlockPos fits(ServerLevel level, int x0, int z0, int size, int height) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int dx = 0; dx < size; dx++) {
            for (int dz = 0; dz < size; dz++) {
                if (!level.hasChunkAt(new BlockPos(x0 + dx, 0, z0 + dz))) return null;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x0 + dx, z0 + dz);
                BlockState ground = level.getBlockState(new BlockPos(x0 + dx, y - 1, z0 + dz));
                if (!ground.getFluidState().isEmpty() || ground.is(BlockTags.LOGS) || ground.is(BlockTags.LEAVES)) return null;
                min = Math.min(min, y);
                max = Math.max(max, y);
            }
        }
        if (max - min > 1) return null;
        BlockPos origin = new BlockPos(x0, max, z0);
        for (BlockPos p : BlockPos.betweenClosed(origin, origin.offset(size - 1, height + 1, size - 1))) {
            BlockState st = level.getBlockState(p);
            if (st.isAir() || st.canBeReplaced()) continue;
            if (!SteveWork.diggable(st) || st.is(BlockTags.LOGS) || st.is(BlockTags.LEAVES)) return null;
        }
        // Not on top of anyone's home.
        for (ServerPlayer p : level.players()) {
            BlockPos home = StoryDirector.homeOf(p, level);
            if (home != null && home.distSqr(origin) < 9 * 9) return null;
        }
        return origin;
    }

    private void blueprint(ServerLevel level, Plan p) {
        int s = p.size();
        int h = p.height();
        BlockPos o = p.origin;
        boolean base = p.kind.equals("base");
        BlockState foundation = (p.wall == Blocks.DIRT ? Blocks.DIRT : Blocks.COBBLESTONE).defaultBlockState();
        BlockState wall = p.wall.defaultBlockState();
        Block logBlock = logFor(p.wall);
        BlockState roof = base ? planksFor(p.wall).defaultBlockState() : wall;
        int m = s / 2;
        // Where the door goes, on the side facing you.
        int doorX = switch (p.front) {
            case WEST -> 0;
            case EAST -> s - 1;
            default -> m;
        };
        int doorZ = switch (p.front) {
            case NORTH -> 0;
            case SOUTH -> s - 1;
            default -> m;
        };
        // Ground first: fill the dips, clear what's in the way.
        for (int dx = 0; dx < s; dx++) {
            for (int dz = 0; dz < s; dz++) {
                BlockPos under = o.offset(dx, -1, dz);
                if (!level.getBlockState(under).isFaceSturdy(level, under, Direction.UP)) p.spots.add(new Spot(under, foundation));
            }
        }
        for (BlockPos q : BlockPos.betweenClosed(o, o.offset(s - 1, h, s - 1))) {
            if (!level.getBlockState(q).isAir()) p.spots.add(new Spot(q.immutable(), Blocks.AIR.defaultBlockState()));
        }
        // Walls, a layer at a time, with log corners.
        for (int y = 0; y < h; y++) {
            for (int dx = 0; dx < s; dx++) {
                for (int dz = 0; dz < s; dz++) {
                    boolean edge = dx == 0 || dx == s - 1 || dz == 0 || dz == s - 1;
                    if (!edge) continue;
                    BlockPos q = o.offset(dx, y, dz);
                    boolean corner = (dx == 0 || dx == s - 1) && (dz == 0 || dz == s - 1);
                    boolean door = dx == doorX && dz == doorZ && y < 2;
                    boolean window = base && y == 1 && !corner && !door && (dx == m || dz == m);
                    if (door) continue;
                    if (window) {
                        if (work.count(Items.GLASS) >= 3) p.spots.add(new Spot(q, Blocks.GLASS.defaultBlockState()));
                        continue;
                    }
                    BlockState st = corner && base ? logBlock.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y) : wall;
                    p.spots.add(new Spot(q, st));
                }
            }
        }
        // The roof, back to front.
        for (int i = 0; i < s; i++) {
            for (int j = 0; j < s; j++) {
                int dx = p.front == Direction.EAST ? i : p.front == Direction.WEST ? s - 1 - i : j;
                int dz = p.front == Direction.SOUTH ? i : p.front == Direction.NORTH ? s - 1 - i : j;
                p.spots.add(new Spot(o.offset(dx, h, dz), roof));
            }
        }
        // The door, then light inside.
        BlockPos doorPos = o.offset(doorX, 0, doorZ);
        if (base) {
            p.spots.add(new Spot(doorPos, Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, p.front.getOpposite())
                    .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)));
            BlockPos back = doorPos.relative(p.front.getOpposite(), s - 2).above();
            p.spots.add(new Spot(back, Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, p.front)));
            BlockPos overDoor = doorPos.relative(p.front.getOpposite()).above(2);
            if (h >= 3) p.spots.add(new Spot(overDoor, Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, p.front.getOpposite())));
        } else {
            BlockPos inside = o.offset(m, 1, m).relative(p.front.getOpposite(), m - 1);
            p.spots.add(new Spot(inside, Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, p.front)));
        }
    }

    private static Block logFor(Block wall) {
        String id = BuiltInRegistries.BLOCK.getKey(wall).getPath();
        if (id.endsWith("_planks")) {
            Identifier log = Identifier.withDefaultNamespace(id.replace("_planks", "_log"));
            Block b = BuiltInRegistries.BLOCK.getValue(log);
            if (b instanceof RotatedPillarBlock) return b;
        }
        return Blocks.OAK_LOG;
    }

    private Block planksFor(Block wall) {
        if (wall.defaultBlockState().is(BlockTags.PLANKS)) return wall;
        Item planks = work.planksType();
        return planks instanceof BlockItem bi ? bi.getBlock() : Blocks.OAK_PLANKS;
    }

    /** What he has to carry to put this block down (null for clearing). */
    private static @Nullable Item itemFor(BlockState st) {
        if (st.isAir()) return null;
        if (st.is(Blocks.WALL_TORCH)) return Items.TORCH;
        return st.getBlock().asItem();
    }

    private static boolean done(ServerLevel level, Spot s) {
        BlockState now = level.getBlockState(s.pos());
        if (s.state().isAir()) return now.isAir();
        return now.getBlock() == s.state().getBlock();
    }

    // ---- the work ----------------------------------------------------------------------------

    @Nullable SteveWork.Job next(ServerLevel level, SteveMemory memory, ServerPlayer anchor) {
        if (saved != null && plan == null) {
            blueprint(level, saved);
            plan = saved;
            saved = null;
        }
        Plan p = plan;
        if (p == null) return null;
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(p.owner);
        if (steve.blockPosition().distSqr(p.origin) > 64 * 64) return null; // you took him somewhere else; it'll wait
        skipDone(level, p);
        if (p.index >= p.spots.size()) {
            finish(level, memory, p, owner);
            return null;
        }
        Spot s = p.spots.get(p.index);
        Item need = itemFor(s.state());
        if (need != null && work.count(need) == 0) {
            int total = 0;
            for (int i = p.index; i < p.spots.size(); i++) {
                if (itemFor(p.spots.get(i).state()) == need && !done(level, p.spots.get(i))) total++;
            }
            if (need == Items.GLASS || !worthFetching(need)) {
                // No glass: an open window will do. No coal for torches, no wood for a door: not worth a trip.
                p.spots.remove(p.index);
                return null;
            }
            SteveWork.Job get = work.obtain(level, memory, anchor, need, total);
            if (get == null) {
                if (level.getGameTime() - noMaterialSaid > 2400) {
                    noMaterialSaid = level.getGameTime();
                    SteveVoice.steve(steve, need == Items.TORCH ? "I'm out of torches. I need coal for the inside."
                            : steve.getRandom().nextBoolean() ? "There's nothing near enough to build with. Bring me logs and I'll keep going."
                            : "I've run out. Bring me some blocks and I'll finish it.");
                }
                if (need == Items.TORCH || need == Items.OAK_DOOR) p.spots.remove(p.index);
                return null;
            }
            Reactions.remark(steve, "build_fetch", 90, "I need more wood. Back in a minute.", "More blocks. Hold on.",
                    "Not enough. I'll get more and come back.");
            return get;
        }
        return work.new Task("build", s.pos(), 1600, (l, m, t) -> step(l, m, t));
    }

    /** Torches and the door only get made from what he already carries; nobody walks off to find coal mid-build. */
    private boolean worthFetching(Item need) {
        if (need == Items.TORCH) {
            boolean fuel = work.count(Items.COAL) + work.count(Items.CHARCOAL) > 0;
            return fuel && (work.count(Items.STICK) > 0 || work.count(s -> s.is(net.minecraft.tags.ItemTags.PLANKS)) > 0
                    || work.count(s -> s.is(net.minecraft.tags.ItemTags.LOGS)) > 0);
        }
        if (need == Items.OAK_DOOR) {
            return work.count(s -> s.is(net.minecraft.tags.ItemTags.PLANKS)) >= 6 || work.count(s -> s.is(net.minecraft.tags.ItemTags.LOGS)) >= 2;
        }
        return true;
    }

    private void skipDone(ServerLevel level, Plan p) {
        while (p.index < p.spots.size() && done(level, p.spots.get(p.index))) p.index++;
    }

    private boolean step(ServerLevel level, SteveMemory memory, SteveWork.Task t) {
        Plan p = plan;
        if (p == null) return true;
        skipDone(level, p);
        if (p.index >= p.spots.size()) return true;
        Spot s = p.spots.get(p.index);
        Item need = itemFor(s.state());
        if (need != null && work.count(need) == 0) return true;
        t.target(s.pos());
        // Never put a block where he (or you) is standing.
        AABB box = new AABB(s.pos());
        boolean blocked = s.state().isSolid() && (steve.getBoundingBox().intersects(box)
                || !level.getEntitiesOfClass(net.minecraft.world.entity.player.Player.class, box).isEmpty());
        if (blocked) {
            if (p.spots.size() - p.index > 1) p.spots.add(p.spots.remove(p.index));
            BlockPos out = p.center().relative(p.front, p.size());
            steve.getNavigation().moveTo(out.getX() + 0.5, out.getY(), out.getZ() + 0.5, 1.0);
            return t.ticks() > 200;
        }
        if (!t.walk(s.pos(), 4.2, 1.0)) return false;
        steve.getLookControl().setLookAt(Vec3.atCenterOf(s.pos()));
        BlockState now = level.getBlockState(s.pos());
        if (s.state().isAir()) {
            // Clearing: plants go at once, ground takes digging.
            if (now.canBeReplaced()) {
                level.destroyBlock(s.pos(), false, steve);
                p.index++;
                return false;
            }
            if (p.digProgress == 0) steve.setItemSlot(EquipmentSlot.MAINHAND, work.bestTool(now).copyWithCount(1));
            int needed = SteveWork.breakTicks(level, s.pos(), now, work.bestTool(now));
            if (++p.digProgress % 5 == 0) {
                steve.swingArm();
                level.playSound(null, s.pos(), now.getSoundType().getHitSound(), SoundSource.BLOCKS, 0.5f, 0.8f);
            }
            level.destroyBlockProgress(steve.getId() + 11, s.pos(), Math.min(9, p.digProgress * 10 / Math.max(1, needed)));
            if (p.digProgress < needed) return false;
            level.destroyBlockProgress(steve.getId() + 11, s.pos(), -1);
            for (ItemStack drop : Block.getDrops(now, level, s.pos(), level.getBlockEntity(s.pos()), steve, work.bestTool(now))) work.give(drop);
            level.destroyBlock(s.pos(), false, steve);
            p.digProgress = 0;
            p.index++;
            return false;
        }
        if (t.ticks() % 5 != 0) return false;
        if (!now.canBeReplaced()) {
            p.index++;
            return false;
        }
        steve.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(need));
        level.setBlock(s.pos(), s.state(), 3);
        if (s.state().getBlock() instanceof DoorBlock) {
            level.setBlock(s.pos().above(), s.state().setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 3);
        }
        level.playSound(null, s.pos(), s.state().getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1f, 0.9f);
        steve.swingArm();
        work.take(x -> x.is(need), 1);
        memory.observe("did_build");
        p.index++;
        milestones(p);
        return false;
    }

    private void milestones(Plan p) {
        int wallsEnd = 0;
        for (int i = 0; i < p.spots.size(); i++) if (p.spots.get(i).pos().getY() < p.origin.getY() + p.height()) wallsEnd = i;
        if (p.stage == 0 && p.index > p.spots.size() / 4) {
            p.stage = 1;
            Reactions.remark(steve, "build_quarter", 60, "It's starting to look like something.", "Look. Walls. Real walls.");
        } else if (p.stage == 1 && p.index > wallsEnd) {
            p.stage = 2;
            Reactions.remark(steve, "build_roof", 60, "The walls are up. Now the roof.", "Roof next. A house isn't a house without a roof.");
        }
    }

    private void finish(ServerLevel level, SteveMemory memory, Plan p, @Nullable ServerPlayer owner) {
        plan = null;
        if (p.kind.equals("base")) {
            SteveVoice.steve(steve, steve.getRandom(), "There. A house. Our house. ...your house. I'll stay outside if you want.",
                    "Finished. It has a door. Remember to close it.");
            if (memory.setFlag("steve_built_base")) Journal.unlock(level.getServer(), "note_built");
            dev.leftbehind.story.StoryLog.beat(level.getServer(), null, "built");
        } else {
            SteveVoice.steve(steve, "Done. It's not much. It doesn't have to be much. It just has to have walls.");
        }
        memory.addCounter("builds_done", 1);
        memory.setCounter("base_x", p.origin.getX());
        memory.setCounter("base_y", p.origin.getY());
        memory.setCounter("base_z", p.origin.getZ());
        if (owner != null) memory.relationship(owner.getUUID()).addTrust(3f);
    }

    // ---- saving ------------------------------------------------------------------------------

    void save(ValueOutput output) {
        Plan p = plan != null ? plan : saved;
        if (p == null) return;
        output.putString("build_kind", p.kind);
        output.putLong("build_origin", p.origin.asLong());
        output.putInt("build_front", p.front.get3DDataValue());
        output.putString("build_owner", p.owner.toString());
        output.putString("build_wall", BuiltInRegistries.BLOCK.getKey(p.wall).toString());
    }

    /** A build saved with the world: the plan is drawn again from the ground when he next works. */
    private @Nullable Plan saved;

    void load(ValueInput input) {
        String kind = input.getStringOr("build_kind", "");
        if (kind.isEmpty()) return;
        try {
            BlockPos origin = BlockPos.of(input.getLongOr("build_origin", 0L));
            Direction front = Direction.from3DDataValue(input.getIntOr("build_front", 3));
            UUID owner = UUID.fromString(input.getStringOr("build_owner", ""));
            Identifier wallId = Identifier.tryParse(input.getStringOr("build_wall", "minecraft:oak_planks"));
            Block wall = wallId == null ? Blocks.OAK_PLANKS : BuiltInRegistries.BLOCK.getValue(wallId);
            saved = new Plan(kind, origin, front, owner, wall);
        } catch (IllegalArgumentException e) {
            saved = null;
        }
    }
}
