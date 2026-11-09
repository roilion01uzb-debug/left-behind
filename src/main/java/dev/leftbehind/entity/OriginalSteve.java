package dev.leftbehind.entity;

import dev.leftbehind.memory.AngerStage;
import dev.leftbehind.memory.Relationship;
import dev.leftbehind.memory.SteveMemory;
import dev.leftbehind.story.StoryDirector;
import dev.leftbehind.story.SteveVoice;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * Original Steve. Not a monster. He watches, learns, remembers, and sometimes forgets on purpose.
 */
public class OriginalSteve extends PathfinderMob {
    private int imitateCooldown = 600;
    private int provokeCooldown = 1200;
    private int idleLineCooldown = 2400;

    public OriginalSteve(EntityType<? extends OriginalSteve> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
        this.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.WOODEN_AXE));
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 40.0)
                .add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.FOLLOW_RANGE, 48.0);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new LostNightGoal());
        this.goalSelector.addGoal(2, new AvoidEntityGoal<>(this, Player.class,
                p -> p instanceof Player player && anger(player).atLeast(AngerStage.RESENTMENT) && !anger(player).atLeast(AngerStage.TERRITORIAL),
                10.0F, 0.9, 1.1, e -> true));
        this.goalSelector.addGoal(3, new ProvokeGoal());
        this.goalSelector.addGoal(4, new ImitateWoodcuttingGoal());
        this.goalSelector.addGoal(5, new FollowTrustedPlayerGoal());
        this.goalSelector.addGoal(6, new OpenDoorGoal(this, true));
        this.goalSelector.addGoal(7, new WaterAvoidingRandomStrollGoal(this, 0.8));
        this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 12.0F));
        this.goalSelector.addGoal(9, new RandomLookAroundGoal(this));
    }

    // ---- memory helpers -------------------------------------------------

    public @Nullable SteveMemory memory() {
        return level() instanceof ServerLevel server ? SteveMemory.get(server.getServer()) : null;
    }

    private AngerStage anger(Player player) {
        SteveMemory memory = memory();
        if (memory == null) return AngerStage.CALM;
        Relationship r = memory.relationships.get(player.getUUID());
        return r == null ? AngerStage.CALM : r.stage();
    }

    private boolean inEpisode() {
        SteveMemory memory = memory();
        return memory != null && memory.lostNight;
    }

    private boolean canGrief(ServerLevel level) {
        return level.getGameRules().get(GameRules.MOB_GRIEFING);
    }

    // ---- lifecycle --------------------------------------------------------

    @Override
    public void tick() {
        super.tick();
        if (!(level() instanceof ServerLevel level)) return;
        SteveMemory memory = memory();
        // There is only one Original Steve. An older copy that wakes up in an unloaded chunk fades away.
        if (memory != null && memory.steveId != null && !memory.steveId.equals(getUUID())) {
            discard();
            return;
        }
        if (imitateCooldown > 0) imitateCooldown--;
        if (provokeCooldown > 0) provokeCooldown--;
        if (idleLineCooldown > 0) idleLineCooldown--;
        if (idleLineCooldown == 0 && memory != null && !memory.lostNight) {
            idleLineCooldown = 3600 + random.nextInt(3600);
            StoryDirector.idleThought(this, memory, level);
        }
    }

    @Override
    public boolean removeWhenFarAway(double distSqr) {
        return false;
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
        if (inEpisode() && !source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            return false; // Nobody can touch him while he's gone.
        }
        boolean hurt = super.hurtServer(level, source, damage);
        if (hurt && source.getEntity() instanceof ServerPlayer player && isAlive()) {
            SteveMemory memory = memory();
            if (memory != null) {
                Relationship r = memory.relationship(player.getUUID());
                AngerStage before = r.stage();
                r.timesHurt++;
                r.addAnger(12f);
                r.addTrust(-8f);
                AngerStage after = r.stage();
                if (after != before) {
                    SteveVoice.steve(this, switch (after) {
                        case RESENTMENT -> "Why would you do that?";
                        case PROVOCATION -> "I'll remember this. I remember everything.";
                        case RETALIATION -> "You think this is your world?";
                        case TERRITORIAL -> "This was my house first.";
                        case ESCALATION -> "Fine.";
                        default -> "...";
                    });
                } else if (random.nextInt(3) == 0) {
                    SteveVoice.steve(this, random, "Stop.", "Hey!", "That hurt.", "...again?");
                }
            }
        }
        return hurt;
    }

    @Override
    public void die(DamageSource source) {
        super.die(source);
        SteveMemory memory = memory();
        if (memory != null && level() instanceof ServerLevel level) {
            memory.steveDead = true;
            memory.setDirty();
            SteveVoice.steve(this, "...not again.");
            StoryDirector.onSteveDeath(level, this, source);
        }
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!(level() instanceof ServerLevel level) || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.SUCCESS;
        }
        SteveMemory memory = memory();
        if (memory == null || memory.lostNight) return InteractionResult.PASS;
        ItemStack stack = player.getItemInHand(hand);
        Relationship r = memory.relationship(player.getUUID());
        if (stack.has(DataComponents.FOOD)) {
            stack.shrink(1);
            heal(6f);
            r.gifts++;
            r.addTrust(8f);
            r.addAnger(-15f);
            memory.addStability(3f);
            SteveVoice.steve(this, random, "Thanks.", "For me? ...thank you.", "You're kind. People used to be kind.",
                    "I remember this taste. I don't remember where from.");
            return InteractionResult.SUCCESS_SERVER;
        }
        // The morning after: give him an axe back and he takes it like nothing happened.
        if (stack.is(net.minecraft.tags.ItemTags.AXES) && getMainHandItem().isEmpty()) {
            setItemSlot(EquipmentSlot.MAINHAND, stack.split(1));
            r.addTrust(4f);
            SteveVoice.steve(this, "Where did mine go? I had it yesterday.");
            return InteractionResult.SUCCESS_SERVER;
        }
        StoryDirector.talk(this, serverPlayer, memory, r);
        return InteractionResult.SUCCESS_SERVER;
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putInt("imitate_cooldown", imitateCooldown);
        output.putInt("provoke_cooldown", provokeCooldown);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        imitateCooldown = input.getIntOr("imitate_cooldown", 600);
        provokeCooldown = input.getIntOr("provoke_cooldown", 1200);
    }

    private void swingArm() {
        swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true);
    }

    // ---- goals --------------------------------------------------------------

    /** Follows the player he trusts most, from a respectful distance. */
    class FollowTrustedPlayerGoal extends Goal {
        private @Nullable Player target;

        FollowTrustedPlayerGoal() {
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            SteveMemory memory = memory();
            if (memory == null || memory.lostNight) return false;
            Player best = null;
            float bestTrust = 40f;
            for (Player player : level().players()) {
                if (player.isSpectator() || player.distanceToSqr(OriginalSteve.this) > 32 * 32) continue;
                Relationship r = memory.relationships.get(player.getUUID());
                if (r == null || r.stage().atLeast(AngerStage.RESENTMENT)) continue;
                if (r.trust > bestTrust) {
                    bestTrust = r.trust;
                    best = player;
                }
            }
            target = best;
            return target != null && distanceToSqr(target) > 8 * 8;
        }

        @Override
        public boolean canContinueToUse() {
            return target != null && target.isAlive() && distanceToSqr(target) > 4 * 4 && !inEpisode();
        }

        @Override
        public void tick() {
            if (target == null) return;
            getLookControl().setLookAt(target, 10f, getMaxHeadXRot());
            if (getNavigation().isDone() || tickCount % 20 == 0) getNavigation().moveTo(target, 0.9);
        }

        @Override
        public void stop() {
            target = null;
            getNavigation().stop();
        }
    }

    /** After watching players cut trees enough times, he starts doing it too. Badly. */
    class ImitateWoodcuttingGoal extends Goal {
        private @Nullable BlockPos log;
        private int chopTicks;

        ImitateWoodcuttingGoal() {
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            SteveMemory memory = memory();
            if (memory == null || memory.lostNight || imitateCooldown > 0 || memory.habit("chop_wood") < 5) return false;
            if (getMainHandItem().isEmpty()) return false; // He lost his axe. He doesn't know where.
            imitateCooldown = 400;
            log = null;
            double best = Double.MAX_VALUE;
            BlockPos origin = blockPosition();
            for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-10, -2, -10), origin.offset(10, 4, 10))) {
                if (level().getBlockState(pos).is(BlockTags.LOGS)) {
                    double d = pos.distSqr(origin);
                    if (d < best) {
                        best = d;
                        log = pos.immutable();
                    }
                }
            }
            return log != null;
        }

        @Override
        public void start() {
            chopTicks = 0;
            if (log != null) getNavigation().moveTo(log.getX() + 0.5, log.getY(), log.getZ() + 0.5, 0.8);
        }

        @Override
        public boolean canContinueToUse() {
            return log != null && chopTicks < 200 && level().getBlockState(log).is(BlockTags.LOGS) && !inEpisode();
        }

        @Override
        public void tick() {
            if (log == null || !(level() instanceof ServerLevel level)) return;
            getLookControl().setLookAt(net.minecraft.world.phys.Vec3.atCenterOf(log));
            if (log.distToCenterSqr(position()) < 3.5 * 3.5) {
                chopTicks++;
                if (chopTicks % 10 == 0) swingArm();
                if (chopTicks >= 60) {
                    if (canGrief(level)) level.destroyBlock(log, true, OriginalSteve.this);
                    SteveMemory memory = memory();
                    if (memory != null && memory.habit("chop_wood") == 5) {
                        SteveVoice.steve(OriginalSteve.this, "Like this? This is how you do it, right?");
                        memory.observe("chop_wood");
                    }
                    log = null;
                }
            } else if (getNavigation().isDone()) {
                getNavigation().moveTo(log.getX() + 0.5, log.getY(), log.getZ() + 0.5, 0.8);
                chopTicks++;
            }
        }

        @Override
        public void stop() {
            log = null;
            imitateCooldown = 1200 + random.nextInt(1200);
        }
    }

    /**
     * Anger made personal: goes to the player's home and does something that will upset them.
     * Provocation: takes a torch. Retaliation: breaks the door. Escalation: takes the bed.
     */
    class ProvokeGoal extends Goal {
        private @Nullable ServerPlayer victim;
        private @Nullable BlockPos home;
        private int ticks;

        ProvokeGoal() {
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            if (provokeCooldown > 0 || !(level() instanceof ServerLevel level) || !canGrief(level)) return false;
            SteveMemory memory = memory();
            if (memory == null || memory.lostNight) return false;
            provokeCooldown = 200;
            for (ServerPlayer player : level.players()) {
                Relationship r = memory.relationships.get(player.getUUID());
                if (r == null || !r.stage().atLeast(AngerStage.PROVOCATION)) continue;
                BlockPos bed = StoryDirector.homeOf(player, level);
                if (bed != null && bed.distSqr(blockPosition()) < 64 * 64) {
                    victim = player;
                    home = bed;
                    return true;
                }
            }
            return false;
        }

        @Override
        public void start() {
            ticks = 0;
            if (home != null) getNavigation().moveTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, 1.0);
        }

        @Override
        public boolean canContinueToUse() {
            return home != null && victim != null && ticks < 600 && !inEpisode();
        }

        @Override
        public void tick() {
            ticks++;
            if (home == null || victim == null || !(level() instanceof ServerLevel level)) return;
            if (home.distToCenterSqr(position()) > 4 * 4) {
                if (getNavigation().isDone()) getNavigation().moveTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, 1.0);
                return;
            }
            SteveMemory memory = memory();
            if (memory == null) return;
            AngerStage stage = memory.relationship(victim.getUUID()).stage();
            String act = StoryDirector.provoke(level, OriginalSteve.this, victim, home, stage);
            if (act != null) {
                swingArm();
                home = null;
            }
        }

        @Override
        public void stop() {
            victim = null;
            home = null;
            provokeCooldown = 2400 + random.nextInt(2400);
        }
    }

    /** During a lost night Steve is somewhere else. The director moves him; this goal keeps him from wandering. */
    class LostNightGoal extends Goal {
        LostNightGoal() {
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
        }

        @Override
        public boolean canUse() {
            return inEpisode();
        }

        @Override
        public void tick() {
            if (level() instanceof ServerLevel level && tickCount % 20 == 0) {
                StoryDirector.lostNightTick(level, OriginalSteve.this);
            }
        }
    }
}
