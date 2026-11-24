package dev.leftbehind.entity;

import dev.leftbehind.fx.Fx;
import dev.leftbehind.memory.AngerStage;
import dev.leftbehind.memory.Mood;
import dev.leftbehind.memory.Relationship;
import dev.leftbehind.memory.SteveMemory;
import dev.leftbehind.registry.ModSounds;
import dev.leftbehind.story.Dialogue;
import dev.leftbehind.story.Journal;
import dev.leftbehind.story.Progress;
import dev.leftbehind.story.Reactions;
import dev.leftbehind.story.Scenes;
import dev.leftbehind.story.SteveVoice;
import dev.leftbehind.story.StoryDirector;
import java.util.EnumSet;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Original Steve. Not a monster. He watches, learns, remembers, and sometimes the remembering is too much.
 */
public class OriginalSteve extends PathfinderMob {
    /** What his body is doing, sent to clients so they can pose him. */
    public static final int STATE_NORMAL = 0;
    public static final int STATE_COWER = 1;
    public static final int STATE_STARE = 2;
    public static final int STATE_PANIC = 3;
    public static final int STATE_RAMPAGE = 4;

    private static final EntityDataAccessor<Integer> DATA_STATE = SynchedEntityData.defineId(OriginalSteve.class, EntityDataSerializers.INT);

    /** How far he runs from everyone before he lets himself fall apart. */
    private static final double FLEE_DISTANCE = 52.0;
    /** How close you can stand and watch before he notices you. */
    public static final double NOTICE_DISTANCE = 8.0;
    /** How far away his state can still be seen clearly. */
    public static final double WITNESS_DISTANCE = 20.0;

    private int imitateCooldown = 600;
    private int provokeCooldown = 1200;
    private int idleLineCooldown = 1200;

    private @Nullable UUID talkingTo;
    private int talkTicks;
    private @Nullable UUID followId;
    private @Nullable BlockPos stayAt;
    private int stayTicks;

    // Episode state. Not saved: if the world closes mid-episode he simply starts the trauma again where he stands.
    private int action = -1;
    private int actionTicks;
    private int comfortTicks;
    private int buildIndex;
    private int stuckTicks;
    private @Nullable Vec3 apparition;
    private @Nullable BlockPos traumaSpot;
    private @Nullable BlockPos smashTarget;
    private @Nullable Vec3 lastRampagePos;
    /** Testing only: holds him in one pose. */
    private int debugPose = -1;

    public OriginalSteve(EntityType<? extends OriginalSteve> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
        this.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.WOODEN_AXE));
        this.setDropChance(EquipmentSlot.MAINHAND, 1.0f);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 40.0)
                .add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.ATTACK_DAMAGE, 4.0)
                .add(Attributes.FOLLOW_RANGE, 48.0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_STATE, STATE_NORMAL);
    }

    public int getState() {
        return this.entityData.get(DATA_STATE);
    }

    public void setState(int state) {
        if (getState() != state) this.entityData.set(DATA_STATE, state);
    }

    public void setDebugPose(int pose) {
        this.debugPose = pose;
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new EpisodeGoal());
        this.goalSelector.addGoal(1, new SceneGoal());
        this.goalSelector.addGoal(2, new TalkGoal());
        this.goalSelector.addGoal(3, new MeleeAttackGoal(this, 1.15, true));
        this.goalSelector.addGoal(4, new AvoidEntityGoal<>(this, Player.class,
                p -> p instanceof Player player && anger(player).atLeast(AngerStage.RESENTMENT) && !anger(player).atLeast(AngerStage.TERRITORIAL),
                10.0F, 0.9, 1.1, e -> true));
        this.goalSelector.addGoal(5, new ProvokeGoal());
        this.goalSelector.addGoal(6, new StayGoal());
        this.goalSelector.addGoal(7, new ImitateWoodcuttingGoal());
        this.goalSelector.addGoal(8, new FollowPlayerGoal());
        this.goalSelector.addGoal(9, new OpenDoorGoal(this, true));
        this.goalSelector.addGoal(10, new WaterAvoidingRandomStrollGoal(this, 0.8));
        this.goalSelector.addGoal(11, new LookAtPlayerGoal(this, Player.class, 12.0F));
        this.goalSelector.addGoal(12, new RandomLookAroundGoal(this));
    }

    // ---- memory helpers -------------------------------------------------

    public @Nullable SteveMemory memory() {
        return level() instanceof ServerLevel server ? SteveMemory.get(server.getServer()) : null;
    }

    private AngerStage anger(Player player) {
        SteveMemory memory = memory();
        return memory == null ? AngerStage.CALM : memory.anger(player.getUUID());
    }

    public boolean inEpisode() {
        SteveMemory memory = memory();
        return memory != null && memory.mood().inEpisode();
    }

    private boolean canGrief(ServerLevel level) {
        return StoryDirector.griefingAllowed(level);
    }

    public void say(String... lines) {
        SteveVoice.steve(this, random, lines);
    }

    // ---- conversation and orders ---------------------------------------------

    public void startTalking(ServerPlayer player) {
        talkingTo = player.getUUID();
        talkTicks = 400;
    }

    public void stopTalking() {
        talkTicks = 0;
        talkingTo = null;
    }

    public void orderFollow(ServerPlayer player) {
        followId = player.getUUID();
        stayAt = null;
    }

    public void orderStay() {
        stayAt = blockPosition();
        stayTicks = 20 * 60 * 10;
        followId = null;
    }

    public @Nullable UUID followingId() {
        return followId;
    }

    public boolean isStaying() {
        return stayAt != null;
    }

    public void orderNothing() {
        followId = null;
        stayAt = null;
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
        if (talkTicks > 0) talkTicks--;
        if (memory == null) return;
        if (debugPose >= 0) {
            setState(debugPose);
            getNavigation().stop();
            return;
        }
        boolean episode = memory.mood().inEpisode();
        if (!episode) {
            if (getState() != STATE_NORMAL && !Scenes.sleepwalking()) setState(STATE_NORMAL);
            if (isSprinting()) setSprinting(false);
            if (idleLineCooldown == 0 && !Scenes.busy()) {
                idleLineCooldown = 2400 + random.nextInt(2400);
                Reactions.idleThought(this, memory, level);
            }
            // He keeps half an eye on anything that goes for someone he trusts.
            if (tickCount % 20 == 0 && getTarget() == null) lookForThreats(level, memory);
            if (getTarget() != null && (!getTarget().isAlive() || getTarget().distanceToSqr(this) > 24 * 24)) setTarget(null);
        }
    }

    private void lookForThreats(ServerLevel level, SteveMemory memory) {
        if (getMainHandItem().isEmpty()) return;
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(this) > 16 * 16 || memory.trust(player.getUUID()) < 50f) continue;
            for (Mob mob : level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(8.0), m -> m instanceof Enemy && m.getTarget() == player)) {
                setTarget(mob);
                Reactions.remark(this, "defend", 60, "Leave them alone!", "Not this one.", "Behind you!");
                return;
            }
        }
    }

    @Override
    public boolean removeWhenFarAway(double distSqr) {
        return false;
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
        boolean hurt = super.hurtServer(level, source, damage);
        if (!hurt || !isAlive() || !(source.getEntity() instanceof ServerPlayer player)) return hurt;
        SteveMemory memory = memory();
        if (memory == null) return hurt;
        Relationship r = memory.relationship(player.getUUID());
        Mood mood = memory.mood();
        if (mood == Mood.RAMPAGE) {
            return hurt; // He isn't here. He won't remember it, and it won't count against you.
        }
        if (mood.inEpisode()) {
            r.addTrust(-12f);
            memory.addSanity(-4f);
            say("No, no, no, no", "Not you too!", "Please!");
            beginFlee(level, memory, 300);
            return hurt;
        }
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
            if (after.atLeast(AngerStage.RESENTMENT)) Journal.unlock(level.getServer(), "note_anger");
            if (after.atLeast(AngerStage.RETALIATION)) Progress.grant(player, "angry");
        } else if (random.nextInt(3) == 0) {
            say("Stop.", "Hey!", "That hurt.", "...again?");
        }
        // Push him far enough and he hits back.
        if (after.atLeast(AngerStage.RETALIATION) && player.distanceToSqr(this) < 3.5 * 3.5 && !player.isCreative()) {
            swingArm();
            player.hurtServer(level, damageSources().mobAttack(this), 3f);
        }
        return hurt;
    }

    @Override
    public void die(DamageSource source) {
        super.die(source);
        SteveMemory memory = memory();
        if (memory != null && level() instanceof ServerLevel level) {
            memory.steveDead = true;
            memory.setMood(Mood.NORMAL);
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
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        SteveMemory memory = memory();
        if (memory == null) return InteractionResult.PASS;
        Relationship r = memory.relationship(player.getUUID());
        Mood mood = memory.mood();
        if (mood == Mood.RAMPAGE) {
            Reactions.remark(this, "rampage_touch", 5, "...", "It was right here. It was RIGHT HERE.", "Who put this here? Who put YOU here?");
            return InteractionResult.SUCCESS_SERVER;
        }
        if (mood == Mood.FLEEING || mood == Mood.TRAUMA) {
            if (r.trust >= 60f) {
                Reactions.remark(this, "trauma_touch", 4, "Don't go.", "Is it you? Is it really you?", "Just... stay. Don't say anything.");
                comfortTicks += 60;
            } else {
                say("Don't touch me!", "Get back!");
                beginFlee(level, memory, 200);
            }
            return InteractionResult.SUCCESS_SERVER;
        }
        ItemStack stack = player.getItemInHand(hand);
        if (!stack.isEmpty() && !player.isShiftKeyDown() && Reactions.item(this, serverPlayer, memory, r, stack)) {
            return InteractionResult.SUCCESS_SERVER;
        }
        dev.leftbehind.talk.Talk.interact(this, serverPlayer);
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

    public void swingArm() {
        swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true);
    }

    private @Nullable ServerPlayer nearestPlayer(ServerLevel level) {
        ServerPlayer best = null;
        double bestDist = Double.MAX_VALUE;
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator()) continue;
            double d = p.distanceToSqr(this);
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return best;
    }

    // ---- episodes ---------------------------------------------------------

    /** He runs. Not to hide: to be far enough away that nobody gets hurt. */
    public void beginFlee(ServerLevel level, SteveMemory memory, int ticks) {
        memory.setMood(Mood.FLEEING);
        memory.setCounter("flee_until", level.getGameTime() + ticks);
        action = -1;
        comfortTicks = 0;
        stopTalking();
        setTarget(null);
        getNavigation().stop();
    }

    private void beginTrauma(SteveMemory memory) {
        memory.setMood(Mood.TRAUMA);
        traumaSpot = blockPosition();
        double angle = random.nextDouble() * Math.PI * 2;
        apparition = position().add(Math.cos(angle) * 9, 1.0, Math.sin(angle) * 9);
        action = -1;
        actionTicks = 0;
        buildIndex = 0;
        setSprinting(false);
        getNavigation().stop();
    }

    /** The episode is over. He walks back as if he had only gone for a walk. */
    public void beginReturn(ServerLevel level, SteveMemory memory) {
        boolean rampaged = memory.mood() == Mood.RAMPAGE;
        memory.setMood(Mood.RETURNING);
        memory.setCounter("return_until", level.getGameTime() + 1200);
        setState(STATE_NORMAL);
        setSprinting(false);
        action = -1;
        // The morning-after consequence: he left his axe at the place he doesn't remember visiting.
        ItemStack axe = getMainHandItem();
        if (rampaged && !axe.isEmpty() && !memory.nightLog.isEmpty()) {
            spawnAtLocation(level, axe.copy());
            setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        }
    }

    private void finishEpisode(ServerLevel level, SteveMemory memory, boolean comforted) {
        memory.setMood(Mood.NORMAL);
        setState(STATE_NORMAL);
        setSprinting(false);
        action = -1;
        comfortTicks = 0;
        if (comforted) {
            say("...thank you for staying. Nobody ever stayed.", "You didn't leave. Why didn't you leave?");
            memory.addSanity(18f);
            Journal.unlock(level.getServer(), "mem_stay");
            Fx.memoryDust(this, 24);
        } else if (memory.nightLog.isEmpty()) {
            say("Morning.", "I had a bad dream. I think.", "Sorry. I went for a walk. Did I miss anything?");
        } else {
            say("Good morning! ...why are you looking at me like that?",
                    "Morning. Did something happen last night?", "Hey. Sleep okay? I slept great.");
            Journal.unlock(level.getServer(), "mem_morning");
            Progress.grantAll(level.getServer(), "morning");
        }
    }

    private void episodeTick(ServerLevel level, SteveMemory memory) {
        long time = level.getGameTime();
        switch (memory.mood()) {
            case FLEEING -> fleeTick(level, memory, time);
            case TRAUMA -> traumaTick(level, memory, time);
            case RAMPAGE -> rampageTick(level, memory, time);
            case RETURNING -> returnTick(level, memory, time);
            default -> { }
        }
    }

    private void fleeTick(ServerLevel level, SteveMemory memory, long time) {
        setState(STATE_PANIC);
        setSprinting(true);
        ServerPlayer nearest = nearestPlayer(level);
        double dist = nearest == null ? 999.0 : nearest.distanceTo(this);
        if (dist >= FLEE_DISTANCE || time >= memory.counter("flee_until")) {
            beginTrauma(memory);
            return;
        }
        if (getNavigation().isDone() || tickCount % 30 == 0) {
            Vec3 from = nearest != null ? nearest.position() : position();
            Vec3 to = LandRandomPos.getPosAway(this, 20, 7, from);
            if (to == null) to = DefaultRandomPos.getPosAway(this, 16, 7, from);
            if (to != null) getNavigation().moveTo(to.x, to.y, to.z, 1.2);
        }
        if (tickCount % 50 == 0) Fx.sound(this, ModSounds.PANIC_BREATH, SoundSource.NEUTRAL, 1.0f, 1.1f);
        if (tickCount % 6 == 0) Fx.tears(this);
    }

    private static final int ACT_COWER = 0;
    private static final int ACT_PACE = 1;
    private static final int ACT_STARE = 2;
    private static final int ACT_LASH = 3;
    private static final int ACT_BUILD = 4;

    private void traumaTick(ServerLevel level, SteveMemory memory, long time) {
        if (traumaSpot == null || apparition == null) beginTrauma(memory);
        setSprinting(false);
        if (--actionTicks <= 0) chooseAction(level);
        switch (action) {
            case ACT_COWER -> {
                setState(STATE_COWER);
                getNavigation().stop();
                if (tickCount % 70 == 0) Fx.sound(this, ModSounds.PANIC_BREATH, SoundSource.NEUTRAL, 0.9f, 0.95f + random.nextFloat() * 0.15f);
                if (tickCount % 8 == 0) Fx.tears(this);
                if (tickCount % 160 == 0) {
                    say("It's not real. It's not real. It's not real.", "I didn't break it. I didn't. I didn't.",
                            "Please. I stayed. I always stayed.", "Don't reset. Don't reset. Don't...", "I can't find the door. There was a door.");
                }
            }
            case ACT_PACE -> {
                setState(STATE_NORMAL);
                if (getNavigation().isDone() && traumaSpot != null) {
                    BlockPos to = traumaSpot.offset(random.nextInt(9) - 4, 0, random.nextInt(9) - 4);
                    getNavigation().moveTo(to.getX() + 0.5, to.getY(), to.getZ() + 0.5, 0.75);
                }
                if (tickCount % 90 == 0) Fx.sound(this, ModSounds.MUTTER, SoundSource.NEUTRAL, 0.9f, 0.9f + random.nextFloat() * 0.2f);
                if (tickCount % 170 == 0) {
                    say("Count the blocks. One. Two. One. Two.", "The house was here. It was right here.",
                            "Where did they go? They were all right here.", "Somebody moved the sun again.");
                }
            }
            case ACT_STARE -> {
                setState(STATE_STARE);
                getNavigation().stop();
                if (apparition != null) {
                    getLookControl().setLookAt(apparition.x, apparition.y + 0.6, apparition.z, 30f, 30f);
                    if (tickCount % 6 == 0) {
                        // What he is looking at. There is nothing there. There is nearly nothing there.
                        Fx.particles(level, ParticleTypes.SMOKE, apparition, 3, 0.12, 0.002);
                        Fx.particles(level, ParticleTypes.SMOKE, apparition.add(0, 0.7, 0), 2, 0.1, 0.002);
                    }
                }
                if (tickCount % 150 == 0) {
                    say("He's standing right there. Can't you see him?", "Stop looking at me.", "I know what you want. You can't have this one.");
                }
            }
            case ACT_LASH -> {
                setState(STATE_NORMAL);
                if (apparition != null) {
                    getLookControl().setLookAt(apparition.x, apparition.y, apparition.z, 30f, 30f);
                    if (distanceToSqr(apparition) > 3 * 3 && getNavigation().isDone()) {
                        getNavigation().moveTo(apparition.x, apparition.y - 1, apparition.z, 1.1);
                    }
                }
                if (tickCount % 11 == 0) {
                    swingArm();
                    Vec3 front = getEyePosition().add(getViewVector(1f).scale(1.6));
                    Fx.particles(level, ParticleTypes.CRIT, front, 5, 0.25, 0.1);
                    level.playSound(null, blockPosition(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.NEUTRAL, 0.6f, 0.8f);
                }
                if (tickCount % 120 == 0) say("GET OUT!", "You're not me! You're NOT me!", "I won't do it again! I WON'T!");
            }
            case ACT_BUILD -> {
                setState(STATE_NORMAL);
                getNavigation().stop();
                if (tickCount % 14 == 0 && traumaSpot != null) {
                    // A wall on three sides. The side facing whoever is watching stays open: he never finishes it.
                    int[][] ring = {{1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};
                    if (buildIndex < ring.length) {
                        int[] offset = ring[buildIndex];
                        BlockPos pos = blockPosition().offset(offset[0], 0, offset[1]);
                        buildIndex++;
                        ServerPlayer watcher = nearestPlayer(level);
                        boolean facesWatcher = false;
                        if (watcher != null) {
                            Vec3 toWatcher = watcher.position().subtract(position());
                            facesWatcher = toWatcher.x * offset[0] + toWatcher.z * offset[1] > 0.25 * toWatcher.horizontalDistance();
                        }
                        BlockState at = level.getBlockState(pos);
                        if (!facesWatcher && canGrief(level) && at.canBeReplaced() && at.getFluidState().isEmpty()
                                && level.getBlockState(pos.below()).isSolid()) {
                            level.setBlock(pos, Blocks.DIRT.defaultBlockState(), 3);
                            level.playSound(null, pos, SoundEvents.GRAVEL_PLACE, SoundSource.BLOCKS, 0.8f, 0.8f);
                            getLookControl().setLookAt(Vec3.atCenterOf(pos));
                            swingArm();
                        }
                    } else {
                        Journal.unlock(level.getServer(), "mem_shelter");
                        actionTicks = 0;
                    }
                }
                if (tickCount % 140 == 0) say("Walls. Need walls before dark.", "Dirt is fine. Dirt is enough. Just until morning.");
            }
            default -> { }
        }

        if (tickCount % 10 == 0) noticePlayers(level, memory, time);

        // After enough of these, staying away isn't enough any more. He goes back to the place that matters.
        long since = time - memory.counter("episode_start");
        if (level.isDarkOutside() && since > 1200 && (memory.breaches >= 3 || memory.sanity < 30f) && canGrief(level) && comfortTicks == 0) {
            memory.setMood(Mood.RAMPAGE);
            smashTarget = null;
            stuckTicks = 0;
            Fx.sound(this, ModSounds.BREACH, SoundSource.NEUTRAL, 1.2f, 0.7f);
            SteveVoice.steve(this, "It was MY house.");
        }
    }

    private void chooseAction(ServerLevel level) {
        int previous = action;
        int roll = random.nextInt(100);
        if (roll < 34) action = ACT_COWER;
        else if (roll < 56) action = ACT_PACE;
        else if (roll < 74) action = ACT_STARE;
        else if (roll < 88) action = ACT_LASH;
        else action = buildIndex == 0 ? ACT_BUILD : ACT_COWER;
        if (previous == -1) action = ACT_COWER;
        actionTicks = action == ACT_LASH ? 80 + random.nextInt(60) : 140 + random.nextInt(140);
        if (action == ACT_BUILD) actionTicks = 200;
    }

    private void noticePlayers(ServerLevel level, SteveMemory memory, long time) {
        ServerPlayer nearest = nearestPlayer(level);
        if (nearest == null) return;
        double dist = nearest.distanceTo(this);
        if (dist > WITNESS_DISTANCE || !nearest.hasLineOfSight(this)) {
            if (comfortTicks > 0) comfortTicks = Math.max(0, comfortTicks - 5);
            return;
        }
        // Someone has followed him out here and is watching.
        if (memory.setFlag("trauma_seen")) Journal.unlock(level.getServer(), "note_trauma");
        Progress.grant(nearest, "witness");
        if (dist > NOTICE_DISTANCE) return;
        float trust = memory.trust(nearest.getUUID());
        if (trust >= 60f && !nearest.isSprinting()) {
            if (comfortTicks == 0) say("...you came after me.", "You shouldn't be here. ...don't go.");
            comfortTicks += 10;
            action = ACT_COWER;
            actionTicks = Math.max(actionTicks, 40);
            getLookControl().setLookAt(nearest, 20f, 20f);
            if (comfortTicks % 100 == 0) {
                Fx.memoryDust(this, 6);
                say("It's quieter when you're here.", "I can hear the grass again.", "Is the sun still there? Good. Good.");
            }
            if (comfortTicks >= 400) {
                memory.relationship(nearest.getUUID()).addTrust(10f);
                Progress.grant(nearest, "stay");
                finishEpisode(level, memory, true);
            }
        } else if (memory.cooldown("stay_back", time, 100)) {
            say("Stay back!", "Don't come closer! I don't know what I'll do!", "Go away! Please!");
            beginFlee(level, memory, 160);
        }
    }

    private void rampageTick(ServerLevel level, SteveMemory memory, long time) {
        setState(STATE_RAMPAGE);
        ServerPlayer trusted = StoryDirector.mostTrusted(level, memory);
        if (trusted == null) return;
        BlockPos home = StoryDirector.homeOf(trusted, level);
        BlockPos center = home != null ? home : trusted.blockPosition();
        double distSqr = blockPosition().distSqr(center);
        if (distSqr > 14 * 14) {
            setSprinting(true);
            if (getNavigation().isDone() || tickCount % 40 == 0) {
                Vec3 goal = Vec3.atBottomCenterOf(center);
                Vec3 step = distSqr > 30 * 30 ? DefaultRandomPos.getPosTowards(this, 20, 7, goal, Math.PI / 2) : goal;
                if (step != null) getNavigation().moveTo(step.x, step.y, step.z, 1.15);
            }
            // If the ground won't let him through, the memory carries him. Only when nobody is close enough to see it.
            if (tickCount % 20 == 0) {
                if (lastRampagePos != null && lastRampagePos.distanceToSqr(position()) < 1.0) stuckTicks += 20;
                else stuckTicks = 0;
                lastRampagePos = position();
                ServerPlayer nearest = nearestPlayer(level);
                if (stuckTicks >= 200 && (nearest == null || nearest.distanceToSqr(this) > 32 * 32)) {
                    StoryDirector.moveNear(level, this, center, 10, 14);
                    stuckTicks = 0;
                }
            }
            return;
        }
        setSprinting(false);
        // Anyone who gets in the way is just another thing in the house.
        if (tickCount % 20 == 0) {
            for (ServerPlayer player : level.players()) {
                if (player.distanceToSqr(this) < 2.6 * 2.6 && !player.isCreative() && !player.isSpectator()) {
                    swingArm();
                    player.hurtServer(level, damageSources().mobAttack(this), 4f);
                    Reactions.remark(this, "rampage_hit", 8, "Get out of my house.", "You're not supposed to be here.", "...");
                }
            }
        }
        if (smashTarget == null) {
            if (tickCount % 40 == 0) {
                smashTarget = StoryDirector.findSmashable(level, center, random);
                if (smashTarget == null && random.nextInt(3) == 0) {
                    level.playSound(null, blockPosition(), SoundEvents.WOOD_STEP, SoundSource.HOSTILE, 0.8f, 0.8f);
                    if (getNavigation().isDone()) {
                        BlockPos to = center.offset(random.nextInt(13) - 6, 0, random.nextInt(13) - 6);
                        getNavigation().moveTo(to.getX() + 0.5, to.getY(), to.getZ() + 0.5, 0.9);
                    }
                }
            }
            return;
        }
        Vec3 target = Vec3.atCenterOf(smashTarget);
        getLookControl().setLookAt(target);
        if (distanceToSqr(target) > 3.2 * 3.2) {
            if (getNavigation().isDone() || tickCount % 20 == 0) getNavigation().moveTo(target.x, target.y, target.z, 1.0);
            if (++stuckTicks > 160) {
                smashTarget = null;
                stuckTicks = 0;
            }
            return;
        }
        stuckTicks = 0;
        if (tickCount % 12 == 0) swingArm();
        if (tickCount % 36 == 0) {
            String what = StoryDirector.describeSmash(level.getBlockState(smashTarget));
            if (what != null) {
                level.destroyBlock(smashTarget, false, this);
                memory.nightLog.add(what + " at " + smashTarget.getX() + " " + smashTarget.getY() + " " + smashTarget.getZ());
                memory.setDirty();
                if (random.nextInt(3) == 0) say("Not mine. Not mine. Not mine.", "They changed it. They always change it.", "Where's MY door?");
            }
            smashTarget = null;
        }
    }

    private void returnTick(ServerLevel level, SteveMemory memory, long time) {
        setState(STATE_NORMAL);
        ServerPlayer trusted = StoryDirector.mostTrusted(level, memory);
        if (trusted == null || trusted.distanceToSqr(this) < 8 * 8 || time >= memory.counter("return_until")) {
            if (trusted != null && trusted.distanceToSqr(this) > 48 * 48 && !trusted.hasLineOfSight(this)) {
                StoryDirector.moveNear(level, this, trusted.blockPosition(), 14, 22);
            }
            finishEpisode(level, memory, false);
            return;
        }
        if (getNavigation().isDone() || tickCount % 40 == 0) {
            Vec3 goal = trusted.position();
            Vec3 step = trusted.distanceToSqr(this) > 30 * 30 ? DefaultRandomPos.getPosTowards(this, 20, 7, goal, Math.PI / 2) : goal;
            if (step != null) getNavigation().moveTo(step.x, step.y, step.z, 0.95);
        }
    }

    // ---- goals --------------------------------------------------------------

    /** Everything he does while the memory has him. */
    class EpisodeGoal extends Goal {
        EpisodeGoal() {
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
        }

        @Override
        public boolean canUse() {
            return inEpisode();
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void start() {
            setTarget(null);
        }

        @Override
        public void tick() {
            SteveMemory memory = memory();
            if (memory != null && level() instanceof ServerLevel level) episodeTick(level, memory);
        }

        @Override
        public void stop() {
            setState(STATE_NORMAL);
            setSprinting(false);
            getNavigation().stop();
        }
    }

    /** While a scene has him (the hill, the sleepwalk), the scene moves him. */
    class SceneGoal extends Goal {
        SceneGoal() {
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
        }

        @Override
        public boolean canUse() {
            return Scenes.busy() && !inEpisode();
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void start() {
            setTarget(null);
        }

        @Override
        public void tick() {
            if (level() instanceof ServerLevel level) Scenes.steveTick(level, OriginalSteve.this);
        }

        @Override
        public void stop() {
            if (!inEpisode()) setState(STATE_NORMAL);
        }
    }

    /** Stands still and looks at whoever he is talking to. */
    class TalkGoal extends Goal {
        private @Nullable Player partner;

        TalkGoal() {
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            if (talkTicks <= 0 || talkingTo == null) return false;
            partner = level().getPlayerByUUID(talkingTo);
            return partner != null && partner.distanceToSqr(OriginalSteve.this) < 8 * 8;
        }

        @Override
        public void start() {
            getNavigation().stop();
        }

        @Override
        public void tick() {
            if (partner != null) getLookControl().setLookAt(partner, 30f, 30f);
        }

        @Override
        public void stop() {
            partner = null;
        }
    }

    /** "Stay here." He does, for a while. */
    class StayGoal extends Goal {
        StayGoal() {
            setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            return stayAt != null;
        }

        @Override
        public void tick() {
            if (stayAt == null) return;
            if (--stayTicks <= 0) {
                stayAt = null;
                return;
            }
            if (stayAt.distToCenterSqr(position()) > 3 * 3 && getNavigation().isDone()) {
                getNavigation().moveTo(stayAt.getX() + 0.5, stayAt.getY(), stayAt.getZ() + 0.5, 0.8);
            }
        }
    }

    /** Follows whoever asked him to, or else the player he trusts most, from a respectful distance. */
    class FollowPlayerGoal extends Goal {
        private @Nullable Player target;
        private boolean ordered;

        FollowPlayerGoal() {
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            SteveMemory memory = memory();
            if (memory == null) return false;
            target = null;
            ordered = false;
            if (followId != null) {
                Player player = level().getPlayerByUUID(followId);
                if (player != null && !player.isSpectator() && player.distanceToSqr(OriginalSteve.this) < 48 * 48
                        && !memory.anger(followId).atLeast(AngerStage.RESENTMENT)) {
                    target = player;
                    ordered = true;
                    return distanceToSqr(target) > 5 * 5;
                }
            }
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
            return target != null && distanceToSqr(target) > 9 * 9;
        }

        @Override
        public boolean canContinueToUse() {
            return target != null && target.isAlive() && distanceToSqr(target) > (ordered ? 3 * 3 : 4 * 4) && !inEpisode();
        }

        @Override
        public void tick() {
            if (target == null) return;
            getLookControl().setLookAt(target, 10f, getMaxHeadXRot());
            if (getNavigation().isDone() || tickCount % 20 == 0) getNavigation().moveTo(target, distanceToSqr(target) > 12 * 12 ? 1.15 : 0.9);
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
            if (memory == null || imitateCooldown > 0 || memory.habit("chop_wood") < 5) return false;
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
            getLookControl().setLookAt(Vec3.atCenterOf(log));
            if (log.distToCenterSqr(position()) < 3.5 * 3.5) {
                chopTicks++;
                if (chopTicks % 10 == 0) swingArm();
                if (chopTicks >= 60) {
                    if (canGrief(level)) level.destroyBlock(log, true, OriginalSteve.this);
                    SteveMemory memory = memory();
                    if (memory != null && memory.setFlag("imitated_wood")) {
                        SteveVoice.steve(OriginalSteve.this, "Like this? This is how you do it, right?");
                        Journal.unlock(level.getServer(), "mem_wood");
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
            if (memory == null) return false;
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

    /** Used by the melee goal: he only ever fights things that go for his friends. */
    @Override
    public void setTarget(@Nullable LivingEntity target) {
        if (target instanceof Player) return;
        super.setTarget(target);
    }
}
