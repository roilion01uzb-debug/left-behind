package dev.leftbehind.story;

import dev.leftbehind.entity.Herobrine;
import dev.leftbehind.entity.OriginalSteve;
import dev.leftbehind.entity.SteveWork;
import dev.leftbehind.memory.Relationship;
import dev.leftbehind.memory.SteveMemory;
import dev.leftbehind.registry.ModDamage;
import net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.player.ItemEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Steve watches. Only what he actually sees counts: he has to be nearby with a line of sight.
 * What he sees changes what he has learned, how he feels about you, and what he says.
 */
public final class Observation {
    private Observation() {}

    public static void register() {
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
            if (level instanceof ServerLevel server && player instanceof ServerPlayer serverPlayer) onBreak(server, serverPlayer, state);
        });

        // Only watches. It must return null: any result here, even PASS, replaces the item's own use,
        // which stops every block from being placed (that was the bug in 0.2.0 and 0.2.1).
        ItemEvents.USE_ON.register(context -> {
            if (context.getLevel() instanceof ServerLevel server && context.getPlayer() instanceof ServerPlayer player
                    && context.getItemInHand().getItem() instanceof BlockItem blockItem) {
                onPlace(server, player, blockItem.getBlock());
            }
            return null;
        });

        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;
            ItemStack stack = player.getItemInHand(hand);
            String tag = Journal.readTag(stack);
            if (tag == null) return InteractionResult.PASS;
            if (tag.equals("journal")) {
                Journal.open(serverPlayer);
            } else {
                boolean isNew = Journal.unlock(serverPlayer.level().getServer(), tag);
                if (isNew) stack.shrink(1);
                Journal.openEntry(serverPlayer, tag);
            }
            return InteractionResult.SUCCESS_SERVER;
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!(entity.level() instanceof ServerLevel server) || entity instanceof OriginalSteve) return;
            SteveMemory memory = SteveMemory.get(server.getServer());
            if (entity instanceof ServerPlayer dead) {
                Relationship r = memory.relationship(dead.getUUID());
                r.setMark("deaths", r.mark("deaths") + 1);
                if (source.is(ModDamage.REMOVED)) r.setMark("removed_pending", 1);
                else if (watching(server, dead) != null) r.setMark("died_pending", 1);
                memory.setDirty();
                OriginalSteve steve = watching(server, dead);
                if (steve != null && !source.is(ModDamage.REMOVED)) {
                    memory.addSanity(-4f);
                    Reactions.remark(steve, "death", 30, "No! ...no. Wait. Wait for it.", "Not again. Please come back. They sometimes come back.");
                }
                return;
            }
            if (!(source.getEntity() instanceof ServerPlayer player)) return;
            OriginalSteve steve = watching(server, player);
            if (steve == null) return;
            Relationship r = memory.relationship(player.getUUID());
            r.killsWitnessed++;
            memory.observe("kill");
            // The same act means different things depending on what he has been through.
            if (entity instanceof Enemy) {
                if (memory.breaches == 0) {
                    r.addTrust(1.5f);
                    Reactions.remark(steve, "kill_monster", 45, "Nice one.", "Thanks.", "You're good at that.", "It would have got me. Probably.");
                } else {
                    memory.addSanity(-1f);
                    Reactions.remark(steve, "kill_monster", 45, "It keeps happening the same way.", "That's how it started last time.",
                            "Good. ...I think good.");
                }
            } else if (entity instanceof AbstractVillager) {
                r.addTrust(-6f);
                memory.addSanity(-4f);
                Reactions.remark(steve, "kill_villager", 10, "They were just standing there. They were only standing there.",
                        "Why? They had a house. They had a door.");
            } else if (entity instanceof Animal) {
                r.addTrust(-0.5f);
                Reactions.remark(steve, "kill_animal", 90, "It wasn't doing anything.", "Did you need to? ...I suppose you need to eat.",
                        "I named that one. It doesn't matter. I'll name another.");
            }
            memory.setDirty();
        });

        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            if (alive) return;
            ServerLevel level = newPlayer.level();
            OriginalSteve steve = StoryDirector.findSteve(level);
            if (steve != null && !steve.inEpisode() && steve.distanceToSqr(newPlayer) < 24 * 24) {
                Reactions.remark(steve, "respawn", 30, "You came back.", "There you are. I counted to a hundred. It only took sixty.",
                        "You came back. How do you do that? I've only ever seen me do that.");
            }
        });

        ServerPlayerEvents.JOIN.register(player -> {
            SteveMemory memory = SteveMemory.get(player.level().getServer());
            Progress.catchUp(player, memory);
            Relationship r = memory.relationship(player.getUUID());
            long left = r.mark("left_ms");
            if (left > 0 && System.currentTimeMillis() - left > 60L * 60L * 1000L) r.setMark("missed_pending", 1);
            memory.setDirty();
        });

        ServerPlayerEvents.LEAVE.register(player -> {
            SteveMemory memory = SteveMemory.get(player.level().getServer());
            memory.relationship(player.getUUID()).setMark("left_ms", System.currentTimeMillis());
            memory.setDirty();
            // He doesn't follow you out of the world. He just stops.
            Herobrine herobrine = StoryDirector.activeHerobrine();
            if (herobrine != null && player.getUUID().equals(herobrine.targetId())) herobrine.discard();
        });

        EntitySleepEvents.START_SLEEPING.register((entity, pos) -> {
            if (!(entity instanceof ServerPlayer player)) return;
            ServerLevel level = player.level();
            OriginalSteve steve = watching(level, player);
            if (steve == null) return;
            SteveMemory memory = SteveMemory.get(level.getServer());
            memory.observe("sleep");
            Reactions.remark(steve, "sleep", 60, "Sleep. I'll watch the door.", "Goodnight. I'll be right here. I don't have anywhere else.",
                    "You just lie down and the night goes away? That's not fair.");
        });

        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> dev.leftbehind.talk.Talk.hear(sender, message.signedContent()));
    }

    private static void onBreak(ServerLevel level, ServerPlayer player, BlockState state) {
        OriginalSteve steve = watching(level, player);
        if (steve == null) return;
        SteveMemory memory = SteveMemory.get(level.getServer());
        if (state.is(BlockTags.LOGS)) {
            int n = memory.observe("chop_wood");
            if (n == 1) SteveVoice.steve(steve, "What are you doing to that tree?");
            else if (n == 4) SteveVoice.steve(steve, "Oh. You're building something.");
            else if (n > 5) Reactions.remark(steve, "chop", 240, "Leave some standing. For the look of it.", "Another one. I'm keeping count.");
        } else if (state.is(BlockTags.BEDS)) {
            memory.observe("break_bed");
            memory.addSanity(-2f);
            Reactions.remark(steve, "break_bed", 10, "Don't. Somebody sleeps there.");
        } else if (state.is(Blocks.TORCH) || state.is(Blocks.WALL_TORCH) || state.is(BlockTags.LANTERNS)) {
            Reactions.remark(steve, "break_light", 60, "Leave the lights. Please.", "It gets dark faster than you think.");
        } else if (state.is(net.minecraft.tags.BlockItemTags.DIAMOND_ORES.block())) {
            Reactions.remark(steve, "diamond", 120, "You found some. Don't tell anyone. That's what people say, isn't it?");
        } else if (state.is(Blocks.OBSIDIAN)) {
            Reactions.remark(steve, "obsidian", 300, "That's the black stone. Like the middle.");
        } else if (state.is(BlockTags.CROPS)) {
            Reactions.remark(steve, "harvest", 300, "You grew that. Out of nothing. I watched it the whole time.");
        } else if (state.is(Blocks.GLASS) || state.is(Blocks.GLASS_PANE)) {
            Reactions.remark(steve, "glass", 120, "I don't like that sound. Windows breaking. I don't know why.");
        } else if (state.is(BlockTags.BASE_STONE_OVERWORLD)) {
            int n = memory.observe("mine");
            if (n == 12) Reactions.remark(steve, "mine", 600, "You dig a lot. What are you looking for down there?");
        } else if (state.is(BlockTags.LEAVES)) {
            Reactions.remark(steve, "leaves", 400, "Don't take all the leaves. I've seen a tree with all the leaves gone. It means something.");
        }
    }

    private static void onPlace(ServerLevel level, ServerPlayer player, Block block) {
        OriginalSteve steve = watching(level, player);
        if (steve == null) return;
        SteveMemory memory = SteveMemory.get(level.getServer());
        memory.observe("build");
        BlockState state = block.defaultBlockState();
        if (block == Blocks.TORCH || state.is(BlockTags.LANTERNS)) {
            int n = memory.observe("place_torch");
            Reactions.remark(steve, "place_torch", n < 3 ? 20 : 300, "Good. More light.", "Put one by the door too.", "That helps. That actually helps.");
        } else if (state.is(BlockTags.BEDS)) {
            Reactions.remark(steve, "place_bed", 120, "You're staying? ...good. Good.", "A bed. So this is home now.");
            memory.addSanity(2f);
        } else if (state.is(BlockTags.DOORS)) {
            Reactions.remark(steve, "place_door", 120, "A door. Remember to close it. They always forget to close it.");
        } else if (block == Blocks.CRAFTING_TABLE) {
            Reactions.remark(steve, "place_table", 300, "Everything starts with one of those.");
        } else if (block == Blocks.TNT) {
            memory.addSanity(-3f);
            Reactions.remark(steve, "place_tnt", 20, "No. No, no, no. Not that. Not near the house.");
        } else if (block == Blocks.CHEST) {
            Reactions.remark(steve, "place_chest", 300, "I had one with everything in it, once. Everything there was. Then there was more than everything.");
        } else if (block == Blocks.DIRT && level.isDarkOutside()) {
            if (Reactions.remark(steve, "place_dirt", 600, "A small square of dirt. That's how I did my first night, too.")
                    && memory.trust(player.getUUID()) >= 25f) {
                Journal.unlock(level.getServer(), "mem_shelter");
            }
        } else if (block == Blocks.BRICKS) {
            Reactions.remark(steve, "place_bricks", 300, "They built with those. Don't build it too big.");
        } else if (block == Blocks.JACK_O_LANTERN || block == Blocks.CARVED_PUMPKIN) {
            Reactions.remark(steve, "place_pumpkin", 300, "It has a face. Why did you give it a face?");
        } else if (state.is(BlockTags.ALL_SIGNS)) {
            Reactions.remark(steve, "place_sign", 200, "What did you write? Don't tell me if it's one word.");
        } else if (state.is(BlockTags.SMALL_FLOWERS)) {
            Reactions.remark(steve, "place_flower", 300, "That's nice. Rana would... never mind. That's nice.");
        } else if (block == Blocks.NETHERRACK || block == Blocks.OBSIDIAN) {
            Reactions.remark(steve, "place_nether", 300, "You're not opening anything, are you? Some doors go both ways.");
        } else if (block == Blocks.CAMPFIRE) {
            Reactions.remark(steve, "place_campfire", 300, "Can I sit by it? I won't touch it.");
        } else if (memory.habit("build") == 40) {
            Reactions.remark(steve, "build", 600, "It's getting big. Is there a room for me? I don't need a room. I just wondered.");
        } else if (memory.habit("build") == 200) {
            Reactions.remark(steve, "build2", 600, "You build things that stay. I like things that stay.");
        }
    }

    /**
     * A player made something. If Steve saw it, he learns how (he uses the game's real recipe from then on),
     * says so, and offers to make you one. Things you make again and again, he offers to make for you when he can.
     */
    public static void crafted(ServerPlayer player, ItemStack stack, int count) {
        if (stack.isEmpty() || !(player.level() instanceof ServerLevel level)) return;
        OriginalSteve steve = watching(level, player);
        if (steve == null) return;
        SteveMemory memory = SteveMemory.get(level.getServer());
        net.minecraft.world.item.Item item = stack.getItem();
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString();
        int times = memory.observe("crafted:" + id);
        memory.observe("craft");
        long now = level.getGameTime();
        String name = stack.getHoverName().getString();
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (SteveWork.learn(memory, item)) {
            memory.relationship(player.getUUID()).addTrust(0.5f);
            int known = SteveWork.knownCount(memory) - 20;
            if (known == 5) Journal.unlock(level.getServer(), "note_learning");
            if (!memory.cooldown("learn_line", now, 200)) return;
            String voiced = LEARN_LINES[steve.getRandom().nextInt(LEARN_LINES.length)];
            SteveVoice.steve(steve, name + "? " + voiced, voiced);
            steve.work().offer(player.getUUID(), item, 1, true, now);
            return;
        }
        // He's seen it before. If you keep making it and he has what it takes, he offers.
        if (times >= 3 && times % 3 == 0 && steve.work().missingFor(level, item) == null && memory.cooldown("craft_offer", now, 2400)) {
            String voiced = "You make a lot of those. I have enough for one. Want me to make it?";
            SteveVoice.steve(steve, cap(lower) + " again. " + voiced, voiced);
            steve.work().offer(player.getUUID(), item, 1, true, now);
            return;
        }
        if (times == 1 && memory.cooldown("craft_seen", now, 600)) {
            Reactions.remark(steve, "craft_known", 60, "I know that one. I can make those too, you know.", "Oh, I know that one.");
        }
    }

    /** What he says when he learns something by watching. All recorded. */
    static final String[] LEARN_LINES = {
            "I watched how you made that. I think I can do it too. Do you want one?",
            "So that's how it goes together. I'll remember. Should I make you one?",
            "I didn't know that one. I do now. I can make you one, if you want.",
            "Show me again sometime. No, I've got it. Want me to make one?",
    };

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static @Nullable OriginalSteve watching(ServerLevel level, Entity player) {
        if (level != level.getServer().overworld()) return null;
        OriginalSteve steve = StoryDirector.findSteve(level);
        if (steve == null || steve.inEpisode() || steve.distanceToSqr(player) > 20 * 20 || !steve.hasLineOfSight(player)) return null;
        return steve;
    }
}
