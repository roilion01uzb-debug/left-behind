package dev.leftbehind.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.leftbehind.entity.Herobrine;
import dev.leftbehind.entity.OriginalSteve;
import dev.leftbehind.memory.Mood;
import dev.leftbehind.memory.Relationship;
import dev.leftbehind.memory.SteveMemory;
import dev.leftbehind.story.Dialogue;
import dev.leftbehind.story.Journal;
import dev.leftbehind.story.Progress;
import dev.leftbehind.story.Relics;
import dev.leftbehind.story.Sense;
import dev.leftbehind.story.StoryDirector;
import java.util.Map;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * /leftbehind: testing and settings for the story (operators only).
 * /steve: what the conversation and journal windows use behind the scenes (everyone).
 */
public final class LeftBehindCommand {
    private LeftBehindCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("leftbehind")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
                .then(Commands.literal("summon").executes(ctx -> summonSteve(ctx.getSource())))
                .then(Commands.literal("herobrine").executes(ctx -> herobrine(ctx.getSource(), "steve"))
                        .then(Commands.literal("steve").executes(ctx -> herobrine(ctx.getSource(), "steve")))
                        .then(Commands.literal("watch").executes(ctx -> herobrine(ctx.getSource(), "watch")))
                        .then(Commands.literal("hunt").executes(ctx -> herobrine(ctx.getSource(), "hunt"))))
                .then(Commands.literal("breach").executes(ctx -> breach(ctx.getSource())))
                .then(Commands.literal("calm").executes(ctx -> calm(ctx.getSource())))
                .then(Commands.literal("ghost").executes(ctx -> ghost(ctx.getSource(), -1))
                        .then(Commands.argument("who", IntegerArgumentType.integer(0, 2))
                                .executes(ctx -> ghost(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "who")))))
                .then(Commands.literal("act")
                        .then(Commands.argument("number", IntegerArgumentType.integer(1, 4))
                                .executes(ctx -> act(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "number")))))
                .then(Commands.literal("relic")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests((ctx, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                                        new String[]{"tree", "house", "pyramid", "zero", "pillar"}, builder))
                                .executes(ctx -> relic(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
                .then(Commands.literal("journal").then(Commands.literal("all").executes(ctx -> journalAll(ctx.getSource()))))
                .then(Commands.literal("anger")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("value", FloatArgumentType.floatArg(0, 100))
                                        .executes(ctx -> setAnger(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"),
                                                FloatArgumentType.getFloat(ctx, "value"))))))
                .then(Commands.literal("trust")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("value", FloatArgumentType.floatArg(0, 100))
                                        .executes(ctx -> setTrust(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"),
                                                FloatArgumentType.getFloat(ctx, "value"))))))
                .then(Commands.literal("sense")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("value", FloatArgumentType.floatArg(0, 100))
                                        .executes(ctx -> {
                                            ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                                            float value = FloatArgumentType.getFloat(ctx, "value");
                                            Sense.set(target, value);
                                            ctx.getSource().sendSuccess(() -> Component.literal(
                                                    "Herobrine's sense of " + target.getGameProfile().name() + " set to " + Math.round(value) + "%"), true);
                                            return 1;
                                        }))))
                .then(Commands.literal("stability")
                        .then(Commands.argument("value", FloatArgumentType.floatArg(0, 100))
                                .executes(ctx -> setStability(ctx.getSource(), FloatArgumentType.getFloat(ctx, "value")))))
                .then(Commands.literal("settings")
                        .then(setting("hunt", "set_no_hunt", "Herobrine hunting players"))
                        .then(setting("creative_kill", "set_no_creative_kill", "Herobrine killing players in creative mode"))
                        .then(setting("griefing", "set_no_grief", "Steve and the story changing blocks")))
                .then(Commands.literal("debug")
                        .then(Commands.literal("talk").executes(ctx -> {
                            ServerPlayer player = playerFor(ctx.getSource());
                            OriginalSteve steve = StoryDirector.findSteve(ctx.getSource().getServer().overworld());
                            if (player == null || steve == null) return 0;
                            Dialogue.open(steve, player);
                            return 1;
                        }))
                        .then(Commands.literal("pose")
                                .then(Commands.argument("pose", IntegerArgumentType.integer(-1, 4))
                                        .executes(ctx -> {
                                            OriginalSteve steve = StoryDirector.findSteve(ctx.getSource().getServer().overworld());
                                            if (steve == null) return 0;
                                            steve.setDebugPose(IntegerArgumentType.getInteger(ctx, "pose"));
                                            return 1;
                                        })))
                        .then(Commands.literal("fx")
                                .then(Commands.argument("kind", IntegerArgumentType.integer(0, 4))
                                        .then(Commands.argument("value", FloatArgumentType.floatArg(0, 1000))
                                                .executes(ctx -> {
                                                    ServerPlayer player = playerFor(ctx.getSource());
                                                    if (player == null) return 0;
                                                    dev.leftbehind.fx.Fx.screen(player, IntegerArgumentType.getInteger(ctx, "kind"),
                                                            FloatArgumentType.getFloat(ctx, "value"));
                                                    return 1;
                                                })))))
                .then(Commands.literal("reset").executes(ctx -> reset(ctx.getSource()))));

        dispatcher.register(Commands.literal("steve")
                .then(Commands.literal("ask")
                        .then(Commands.argument("topic", StringArgumentType.word())
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayer();
                                    if (player != null) Dialogue.ask(player, StringArgumentType.getString(ctx, "topic"));
                                    return 1;
                                })))
                .then(Commands.literal("him")
                        .then(Commands.argument("topic", StringArgumentType.word())
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayer();
                                    if (player != null) Dialogue.askHerobrine(player, StringArgumentType.getString(ctx, "topic"));
                                    return 1;
                                })))
                .then(Commands.literal("journal")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayer();
                            if (player != null) Journal.open(player);
                            return 1;
                        })
                        .then(Commands.argument("entry", StringArgumentType.word())
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayer();
                                    if (player != null) Journal.openEntry(player, StringArgumentType.getString(ctx, "entry"));
                                    return 1;
                                }))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> setting(String name, String flag, String what) {
        return Commands.literal(name)
                .then(Commands.argument("on", BoolArgumentType.bool())
                        .executes(ctx -> {
                            boolean on = BoolArgumentType.getBool(ctx, "on");
                            SteveMemory m = memory(ctx.getSource());
                            if (on) m.clearFlag(flag);
                            else m.setFlag(flag);
                            ctx.getSource().sendSuccess(() -> Component.literal(what + ": " + (on ? "on" : "off")), true);
                            return 1;
                        }));
    }

    private static SteveMemory memory(CommandSourceStack source) {
        return SteveMemory.get(source.getServer());
    }

    /** The player who ran the command, or any player if it came from the console. */
    private static @Nullable ServerPlayer playerFor(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        return player != null ? player : StoryDirector.randomPlayer(source.getServer().overworld());
    }

    private static int status(CommandSourceStack source) {
        SteveMemory m = memory(source);
        ServerLevel level = source.getServer().overworld();
        OriginalSteve steve = StoryDirector.findSteve(level);
        StringBuilder sb = new StringBuilder("§3Left Behind§r  " + Progress.actName(m.act()) + "\n");
        sb.append("Steve: ").append(m.steveDead ? "§cdead§r" : steve == null ? "not loaded" : "at " + steve.blockPosition().toShortString())
                .append(", mood ").append(m.mood().name().toLowerCase()).append('\n');
        sb.append("Memory stability: ").append(Math.round(m.stability)).append("/100, breaches: ").append(m.breaches).append('\n');
        sb.append("Journal: ").append(m.count("mem_")).append(" memories, ").append(m.count("page_")).append(" pages, ")
                .append(m.count("note_")).append(" notes\n");
        Herobrine h = StoryDirector.activeHerobrine();
        sb.append("Herobrine: ").append(h == null ? "not here" : h.mode().name().toLowerCase() + " at " + h.blockPosition().toShortString()).append('\n');
        sb.append("Habits learned: ").append(m.habits).append('\n');
        for (Map.Entry<UUID, Relationship> e : m.relationships.entrySet()) {
            ServerPlayer p = source.getServer().getPlayerList().getPlayer(e.getKey());
            String name = p != null ? p.getName().getString() : e.getKey().toString().substring(0, 8);
            Relationship r = e.getValue();
            sb.append(" ").append(name).append(": trust ").append(Math.round(r.trust))
                    .append(", anger ").append(Math.round(r.anger)).append(" (").append(r.stage().name().toLowerCase()).append(")");
            if (p != null) sb.append(", sensed ").append(Math.round(Sense.get(p))).append("%");
            sb.append('\n');
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
        m.setMood(Mood.NORMAL);
        m.setDirty();
        ServerPlayer player = playerFor(source);
        if (player == null || StoryDirector.spawnSteveAt(level, m, player.blockPosition(), 4, 8) == null) {
            source.sendFailure(Component.literal("Couldn't find a place for Steve. Try again outdoors."));
            return 0;
        }
        if (m.counter("act") == 0) Progress.setAct(source.getServer(), m, 1, true);
        source.sendSuccess(() -> Component.literal("Steve is back."), true);
        return 1;
    }

    private static int herobrine(CommandSourceStack source, String mode) {
        ServerLevel level = source.getServer().overworld();
        ServerPlayer player = playerFor(source);
        Herobrine herobrine;
        if (mode.equals("steve")) {
            OriginalSteve steve = StoryDirector.findSteve(level);
            if (steve == null) {
                source.sendFailure(Component.literal("Steve isn't here. Run /leftbehind summon first."));
                return 0;
            }
            herobrine = StoryDirector.spawnHerobrine(level, steve);
        } else if (player == null) {
            source.sendFailure(Component.literal("There is nobody for him to look at."));
            return 0;
        } else if (mode.equals("hunt")) {
            herobrine = StoryDirector.spawnHunt(level, player);
        } else {
            herobrine = StoryDirector.spawnHerobrineWatching(level, player);
        }
        if (herobrine == null) {
            source.sendFailure(Component.literal("He couldn't find anywhere to stand."));
            return 0;
        }
        BlockPos pos = herobrine.blockPosition();
        source.sendSuccess(() -> Component.literal("He is at " + pos.toShortString() + "."), false);
        return 1;
    }

    private static int breach(CommandSourceStack source) {
        ServerLevel level = source.getServer().overworld();
        OriginalSteve steve = StoryDirector.findSteve(level);
        if (steve == null) {
            source.sendFailure(Component.literal("Steve isn't here."));
            return 0;
        }
        StoryDirector.memoryBreach(level, steve, null);
        source.sendSuccess(() -> Component.literal("Memory breach triggered."), true);
        return 1;
    }

    private static int calm(CommandSourceStack source) {
        SteveMemory m = memory(source);
        ServerLevel level = source.getServer().overworld();
        OriginalSteve steve = StoryDirector.findSteve(level);
        if (steve != null && m.mood().inEpisode()) steve.beginReturn(level, m);
        else m.setMood(Mood.NORMAL);
        source.sendSuccess(() -> Component.literal("The episode is over."), true);
        return 1;
    }

    private static int ghost(CommandSourceStack source, int who) {
        ServerLevel level = source.getServer().overworld();
        ServerPlayer player = playerFor(source);
        if (player == null) return 0;
        int variant = who >= 0 ? who : (int) (memory(source).counter("ghost_count") % 3);
        if (StoryDirector.spawnGhost(level, player, variant) == null) {
            source.sendFailure(Component.literal("Nowhere for them to appear."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Someone is here."), false);
        return 1;
    }

    private static int act(CommandSourceStack source, int number) {
        Progress.setAct(source.getServer(), memory(source), number, true);
        source.sendSuccess(() -> Component.literal("Now in " + Progress.actName(number) + "."), true);
        return 1;
    }

    private static int relic(CommandSourceStack source, String name) {
        ServerLevel level = source.getServer().overworld();
        SteveMemory m = memory(source);
        BlockPos at = Relics.place(level, name);
        if (at == null) {
            source.sendFailure(Component.literal("Couldn't place that here. Try somewhere flatter (or, for zero, nearer to 0 0)."));
            return 0;
        }
        m.setFlag("relic_" + name);
        m.clearFlag("found_" + name);
        m.setCounter("relic_" + name + "_x", at.getX());
        m.setCounter("relic_" + name + "_y", at.getY());
        m.setCounter("relic_" + name + "_z", at.getZ());
        source.sendSuccess(() -> Component.literal("It is at " + at.toShortString() + "."), false);
        return 1;
    }

    private static int journalAll(CommandSourceStack source) {
        for (String prefix : new String[]{"act_", "mem_", "page_", "note_"}) {
            for (String id : Journal.ids()) if (id.startsWith(prefix)) Journal.unlock(source.getServer(), id);
        }
        source.sendSuccess(() -> Component.literal("Every journal entry is unlocked."), true);
        return 1;
    }

    private static int setAnger(CommandSourceStack source, ServerPlayer player, float value) {
        SteveMemory m = memory(source);
        Relationship r = m.relationship(player.getUUID());
        r.anger = value;
        m.setDirty();
        source.sendSuccess(() -> Component.literal("Steve's anger at " + player.getName().getString() + " is now " + Math.round(value)
                + " (" + r.stage().name().toLowerCase() + ")."), true);
        return 1;
    }

    private static int setTrust(CommandSourceStack source, ServerPlayer player, float value) {
        SteveMemory m = memory(source);
        m.relationship(player.getUUID()).trust = value;
        m.setDirty();
        source.sendSuccess(() -> Component.literal("Steve's trust in " + player.getName().getString() + " is now " + Math.round(value) + "."), true);
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
        m.stability = 100f;
        m.relationships.clear();
        m.habits.clear();
        m.breaches = 0;
        m.steveDead = false;
        m.counters.clear();
        m.flags.clear();
        m.journal.clear();
        m.nightLog.clear();
        m.setDirty();
        Sense.clear();
        OriginalSteve steve = StoryDirector.findSteve(source.getServer().overworld());
        if (steve != null) steve.orderNothing();
        source.sendSuccess(() -> Component.literal("Steve has forgotten everything. The story starts again."), true);
        return 1;
    }
}
