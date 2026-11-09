package dev.leftbehind.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import dev.leftbehind.entity.Herobrine;
import dev.leftbehind.entity.OriginalSteve;
import dev.leftbehind.memory.Relationship;
import dev.leftbehind.memory.SteveMemory;
import dev.leftbehind.story.StoryDirector;
import java.util.Map;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** /leftbehind: testing and debugging tools for the story. Operators only. */
public final class LeftBehindCommand {
    private LeftBehindCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("leftbehind")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
                .then(Commands.literal("summon").executes(ctx -> summonSteve(ctx.getSource())))
                .then(Commands.literal("herobrine").executes(ctx -> herobrine(ctx.getSource())))
                .then(Commands.literal("breach").executes(ctx -> breach(ctx.getSource())))
                .then(Commands.literal("anger")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("value", FloatArgumentType.floatArg(0, 100))
                                        .executes(ctx -> setAnger(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"),
                                                FloatArgumentType.getFloat(ctx, "value"))))))
                .then(Commands.literal("stability")
                        .then(Commands.argument("value", FloatArgumentType.floatArg(0, 100))
                                .executes(ctx -> setStability(ctx.getSource(), FloatArgumentType.getFloat(ctx, "value")))))
                .then(Commands.literal("reset").executes(ctx -> reset(ctx.getSource()))));
    }

    private static SteveMemory memory(CommandSourceStack source) {
        return SteveMemory.get(source.getServer());
    }

    private static int status(CommandSourceStack source) {
        SteveMemory m = memory(source);
        ServerLevel level = source.getServer().overworld();
        OriginalSteve steve = StoryDirector.findSteve(level);
        StringBuilder sb = new StringBuilder("§3Left Behind§r\n");
        sb.append("Steve: ").append(m.steveDead ? "§cdead§r" : steve == null ? "not loaded" : "at " + steve.blockPosition().toShortString()).append('\n');
        sb.append("Memory stability: ").append(Math.round(m.stability)).append("/100\n");
        sb.append("Memory breaches: ").append(m.breaches).append(m.lostNight ? " §5(lost night in progress)§r" : "").append('\n');
        sb.append("Habits learned: ").append(m.habits).append('\n');
        for (Map.Entry<UUID, Relationship> e : m.relationships.entrySet()) {
            ServerPlayer p = source.getServer().getPlayerList().getPlayer(e.getKey());
            String name = p != null ? p.getName().getString() : e.getKey().toString().substring(0, 8);
            Relationship r = e.getValue();
            sb.append(" ").append(name).append(": trust ").append(Math.round(r.trust))
                    .append(", anger ").append(Math.round(r.anger)).append(" (").append(r.stage().name().toLowerCase()).append(")\n");
        }
        if (!m.nightLog.isEmpty()) sb.append("Last night he: ").append(String.join("; ", m.nightLog));
        String text = sb.toString();
        source.sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    private static int summonSteve(CommandSourceStack source) {
        SteveMemory m = memory(source);
        ServerLevel level = source.getServer().overworld();
        OriginalSteve existing = StoryDirector.findSteve(level);
        if (existing != null) existing.discard();
        m.steveDead = false;
        m.lostNight = false;
        m.setDirty();
        ServerPlayer player = source.getPlayer();
        if (player == null || StoryDirector.spawnSteveNear(level, m, player) == null) {
            source.sendFailure(Component.literal("Couldn't find a place for Steve. Try again outdoors."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Steve is back."), true);
        return 1;
    }

    private static int herobrine(CommandSourceStack source) {
        ServerLevel level = source.getServer().overworld();
        OriginalSteve steve = StoryDirector.findSteve(level);
        if (steve == null) {
            source.sendFailure(Component.literal("Steve isn't loaded. Use /leftbehind summon first."));
            return 0;
        }
        Herobrine h = StoryDirector.spawnHerobrine(level, steve);
        source.sendSuccess(() -> Component.literal(h == null ? "He didn't come." : "He's watching Steve now."), true);
        return h == null ? 0 : 1;
    }

    private static int breach(CommandSourceStack source) {
        ServerLevel level = source.getServer().overworld();
        OriginalSteve steve = StoryDirector.findSteve(level);
        if (steve == null) {
            source.sendFailure(Component.literal("Steve isn't loaded."));
            return 0;
        }
        StoryDirector.memoryBreach(level, steve, null);
        source.sendSuccess(() -> Component.literal("Memory breach triggered."), true);
        return 1;
    }

    private static int setAnger(CommandSourceStack source, ServerPlayer player, float value) {
        SteveMemory m = memory(source);
        Relationship r = m.relationship(player.getUUID());
        r.anger = value;
        source.sendSuccess(() -> Component.literal("Steve's anger toward " + player.getName().getString() + " is now "
                + Math.round(value) + " (" + r.stage().name().toLowerCase() + ")."), true);
        return 1;
    }

    private static int setStability(CommandSourceStack source, float value) {
        SteveMemory m = memory(source);
        m.stability = value;
        m.setDirty();
        source.sendSuccess(() -> Component.literal("Memory stability is now " + Math.round(value) + "."), true);
        return 1;
    }

    private static int reset(CommandSourceStack source) {
        SteveMemory m = memory(source);
        ServerLevel level = source.getServer().overworld();
        OriginalSteve steve = StoryDirector.findSteve(level);
        if (steve != null) steve.discard();
        m.relationships.clear();
        m.habits.clear();
        m.nightLog.clear();
        m.stability = 100f;
        m.breaches = 0;
        m.lostNight = false;
        m.steveDead = false;
        m.steveId = null;
        m.episodeEnd = 0;
        m.setDirty();
        source.sendSuccess(() -> Component.literal("Steve has forgotten everything. He'll find you again soon."), true);
        return 1;
    }
}
