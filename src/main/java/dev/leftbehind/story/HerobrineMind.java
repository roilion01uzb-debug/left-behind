package dev.leftbehind.story;

import dev.leftbehind.entity.OriginalSteve;
import dev.leftbehind.memory.SteveMemory;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * What Herobrine says to you, from what he knows about you. He has been watching: where you sleep, where you dig,
 * what you carry, how much Steve trusts you, where you keep going back to. He picks the thing that will land.
 *
 * <p>Every line here is lower case and recorded in his voice.
 */
public final class HerobrineMind {
    private HerobrineMind() {}

    /** A whisper for this player, right now. */
    public static String whisperFor(ServerLevel level, ServerPlayer player) {
        SteveMemory memory = SteveMemory.get(level.getServer());
        List<String> pool = new ArrayList<>();
        BlockPos home = StoryDirector.homeOf(player, level);
        OriginalSteve steve = StoryDirector.findSteve(level);
        float trust = memory.trust(player.getUUID());
        ItemStack held = player.getMainHandItem();
        boolean underground = !level.canSeeSky(player.blockPosition().above()) && player.getY() < level.getSeaLevel() - 6;

        if (home != null) {
            if (player.blockPosition().distSqr(home) < 24 * 24) {
                add(pool, "this is a small house", "i have been inside", "you sleep on the left side", "your door does not keep anything out");
            } else {
                add(pool, "your house is empty right now", "i know where you sleep", "go home. i will be there");
            }
        } else {
            add(pool, "you have nowhere to go back to", "no bed. no door. good");
        }
        if (underground) {
            add(pool, "it is darker down here for you. not for me", "every tunnel ends somewhere", "dig a little deeper", "the stone remembers who dug it");
        }
        if (level.isDarkOutside()) add(pool, "you should be inside", "the night belongs to me", "your torches will not last");
        if (level.isRaining()) add(pool, "the rain covers my footsteps");
        if (steve != null && !steve.inEpisode()) {
            if (trust >= 50f) add(pool, "he trusts you. that is a mistake", "he talks about you", "you cannot keep him");
            else if (trust < 15f) add(pool, "he does not like you either", "even he wants you gone");
            else add(pool, "he does not remember you. he will not remember you", "ask him what he did");
            if (steve.distanceToSqr(player) > 60 * 60) add(pool, "he is far away from you now", "he cannot hear you from there");
        } else {
            add(pool, "he is not coming");
        }
        if (held.is(ItemTags.SWORDS)) add(pool, "a sword. how sweet of you");
        else if (held.is(ItemTags.PICKAXES)) add(pool, "keep digging. go on");
        else if (held.is(Items.TORCH)) add(pool, "you think the light is for you");
        else if (held.isEmpty()) add(pool, "empty hands");
        if (player.getHealth() < 8f) add(pool, "you are bleeding", "lie down. it is easier");
        if (Stalker.known(memory, player.getUUID()) >= 6) {
            add(pool, "i know all of your places", "you always walk the same way", "i learned you", "you come back to the same places");
        }
        if (memory.counter("hunts") > 0) add(pool, "you got away from me last time", "last time was practice");
        if (memory.breaches > 0) add(pool, "he ran from me. you saw", "he always runs");
        if (pool.size() < 6) add(pool, "you are not the first one he followed", "it remembers because i let it", "look away", "stop", "i was here first");
        return pool.get(level.getRandom().nextInt(pool.size()));
    }

    private static void add(List<String> pool, String... lines) {
        for (String line : lines) pool.add(line);
    }
}
