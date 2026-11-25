package dev.leftbehind.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/**
 * Starts a real singleplayer world, walks through the main features and takes screenshots.
 * Run with: ./gradlew runClientGameTest
 */
public class LeftBehindClientTest implements FabricClientGameTest {
    private static final String STEVE = "@e[type=leftbehind:original_steve,limit=1,sort=nearest]";
    private static final String HIM = "@e[type=leftbehind:herobrine,limit=1,sort=nearest]";

    private static void zoom(ClientGameTestContext context, int fov) {
        context.runOnClient(client -> client.options.fov().set(fov));
    }

    private static void hud(ClientGameTestContext context, boolean visible) {
        context.runOnClient(client -> {
            if (client.gui.hud.isHidden() == visible) client.gui.hud.toggle();
        });
    }

    /** A short run of just the ladder, logging what Steve's goals are doing (LB_QUICK=ladder). */
    private static void ladderDebug(ClientGameTestContext context, TestServerContext server) {
        server.runCommand("gamemode creative @a");
        server.runCommand("time set noon");
        server.runCommand("leftbehind settings hunt false");
        server.runCommand("leftbehind summon");
        server.runCommand("leftbehind trust @a 50");
        context.waitTicks(20);
        server.runCommand("execute as @a at @s run tp @s ~ ~ ~ 90 0");
        server.runCommand("execute at @p run fill ~-3 ~ ~-4 ~-12 ~6 ~4 stone");
        server.runCommand("execute at @p run fill ~-2 ~ ~ ~-2 ~6 ~ ladder[facing=east]");
        server.runCommand("execute at @p run tp " + STEVE + " ~ ~ ~");
        server.runCommand("execute at @p run tp @p ~-5 ~7 ~ -90 30");
        context.waitTicks(10);
        context.runOnClient(client -> client.player.connection.sendChat("steve follow me"));
        context.waitTicks(40);
        server.runOnServer(s -> dev.leftbehind.story.StoryDirector.findSteve(s.overworld())
                .orderFollow(s.getPlayerList().getPlayers().getFirst()));
        for (int i = 0; i < 30; i++) {
            server.runOnServer(s -> {
                var steve = dev.leftbehind.story.StoryDirector.findSteve(s.overworld());
                var player = s.getPlayerList().getPlayers().getFirst();
                StringBuilder goals = new StringBuilder();
                steve.getGoalSelector().getAvailableGoals().forEach(g -> {
                    if (g.isRunning()) goals.append(g.getGoal().getClass().getSimpleName()).append(' ');
                });
                var path = steve.getNavigation().createPath(player, 1);
                dev.leftbehind.LeftBehind.LOGGER.info("LADDER at {} goals [{}] path {} reach {} nav {} body {} climbable {}",
                        steve.position(), goals, path == null ? "null" : path.getNodeCount() + " to " + path.getTarget(),
                        path != null && path.canReach(), steve.getNavigation().isDone() ? "done" : "moving",
                        steve.body().describe(), steve.onClimbable());
            });
            context.waitTicks(20);
        }
        context.takeScreenshot("ladder_debug");
    }

    private static boolean haunt(TestServerContext server, String what) {
        return server.computeOnServer(s -> {
            var player = s.getPlayerList().getPlayers().getFirst();
            return dev.leftbehind.story.Haunt.force(player.level(), player, what);
        });
    }

    /** Waits up to maxTicks for something on the server to become true. */
    private static boolean waitFor(ClientGameTestContext context, TestServerContext server, int maxTicks,
                                   java.util.function.Predicate<net.minecraft.server.MinecraftServer> test) {
        for (int t = 0; t < maxTicks; t += 10) {
            if (server.computeOnServer(test::test)) return true;
            context.waitTicks(10);
        }
        return server.computeOnServer(test::test);
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            TestServerContext server = world.getServer();
            context.waitTicks(40);
            if ("ladder".equals(System.getenv("LB_QUICK"))) {
                ladderDebug(context, server);
                return;
            }

            // The basics still work with the mod installed: placing a block in survival.
            server.runCommand("gamemode survival @a");
            server.runCommand("item replace entity @a weapon.mainhand with minecraft:stone 16");
            BlockPos feet = server.computeOnServer(s -> s.getPlayerList().getPlayers().getFirst().blockPosition());
            context.getInput().lookAt(feet.offset(3, -1, 0));
            context.waitTicks(5);
            context.getInput().pressKey(options -> options.keyUse);
            context.waitTicks(10);
            int placed = server.computeOnServer(s -> {
                int count = 0;
                for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-5, 0, -5), feet.offset(5, 1, 5))) {
                    if (s.overworld().getBlockState(pos).is(Blocks.STONE)) count++;
                }
                return count;
            });
            context.takeScreenshot("00_place_block");
            if (placed == 0) throw new AssertionError("Could not place a block with Left Behind installed");
            server.runCommand("gamemode creative @a");
            server.runCommand("time set noon");
            server.runCommand("leftbehind settings hunt false");

            // Steve, and talking to him by typing in chat.
            server.runCommand("leftbehind summon");
            context.waitTicks(10);
            server.runCommand("execute at " + STEVE + " anchored eyes rotated ~ 0 run tp @a ^ ^ ^4 facing entity " + STEVE + " eyes");
            context.waitTicks(50);
            context.takeScreenshot("01_steve_greets");
            context.runOnClient(client -> {
                String voice = dev.leftbehind.talk.VoiceIndex.sound("steve", "Oh. Hello. You can see me? Most of them just walk through.");
                if (voice.isEmpty() || client.getSoundManager().getSoundEvent(net.minecraft.resources.Identifier.fromNamespaceAndPath("leftbehind_voice", voice)) == null) {
                    throw new AssertionError("Steve's greeting has no recorded voice");
                }
            });
            // He asks your name by himself a few seconds after meeting you.
            context.waitTicks(200);
            context.takeScreenshot("02_steve_asks");
            String[][] conversation = {
                    {"I'm Temur", "03_name"},
                    {"who are you?", "04_who"},
                    {"why?", "05_why"},
                    {"have you seen netherite?", "06_new_word"},
                    {"it's a super strong black metal from the nether", "07_learned"},
                    {"tell me a joke", "08_joke"},
                    {"what is netherite", "09_recall"},
                    {"u ok?", "10_okay"},
                    {"follow me", "11_follow"},
            };
            for (String[] line : conversation) {
                server.runCommand("execute at " + STEVE + " anchored eyes rotated ~ 0 run tp @a ^ ^ ^4 facing entity " + STEVE + " eyes");
                context.runOnClient(client -> client.player.connection.sendChat(line[0]));
                context.waitTicks(80);
                context.takeScreenshot(line[1]);
                context.waitTicks(Math.min(200, 90 + line[0].length() * 2));
            }
            context.runOnClient(client -> {
                if (dev.leftbehind.client.talk.SpeechClient.voicedLines() < 5) {
                    throw new AssertionError("Steve only spoke " + dev.leftbehind.client.talk.SpeechClient.voicedLines() + " lines out loud");
                }
            });
            // 0.6: Steve does his part. Ask him for wood, and he goes and chops a tree and brings you the logs.
            server.runCommand("clear @a");
            server.runCommand("execute at " + STEVE + " run fill ~5 ~ ~-1 ~5 ~5 ~-1 oak_log");
            server.runCommand("execute at " + STEVE + " run fill ~3 ~4 ~-3 ~7 ~6 ~1 oak_leaves[persistent=true] keep");
            server.runCommand("execute at " + STEVE + " anchored eyes rotated ~ 0 run tp @a ^ ^ ^4 facing entity " + STEVE + " eyes");
            context.waitTicks(10);
            context.runOnClient(client -> client.player.connection.sendChat("steve can you get me some wood"));
            context.waitTicks(60);
            context.takeScreenshot("32_asked_for_wood");
            boolean chopping = waitFor(context, server, 400, s -> {
                var steve = dev.leftbehind.story.StoryDirector.findSteve(s.overworld());
                return steve != null && "gather".equals(steve.work().jobName());
            });
            if (!chopping) throw new AssertionError("Steve never went to chop the tree he was asked for");
            hud(context, false);
            server.runCommand("execute at " + STEVE + " anchored eyes rotated ~ 0 run tp @a ^2 ^1 ^5 facing entity " + STEVE + " eyes");
            context.waitTicks(30);
            context.takeScreenshot("33_steve_chopping");
            hud(context, true);
            boolean gotLogs = waitFor(context, server, 1400, s -> s.getPlayerList().getPlayers().getFirst().getInventory()
                    .countItem(net.minecraft.world.item.Items.OAK_LOG) > 0);
            context.takeScreenshot("34_here_you_go");
            if (!gotLogs) throw new AssertionError("Steve chopped but never handed over the logs");

            // He watches you make something new, learns the recipe, and offers. Say yes and he makes one, from scratch.
            server.runCommand("execute at " + STEVE + " anchored eyes rotated ~ 0 run tp @a ^ ^ ^4 facing entity " + STEVE + " eyes");
            context.waitTicks(10);
            server.runOnServer(s -> {
                var player = s.getPlayerList().getPlayers().getFirst();
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.LADDER, 3).onCraftedBy(player, 3);
            });
            context.waitTicks(50);
            context.takeScreenshot("35_learned_ladder");
            boolean learned = server.computeOnServer(s -> dev.leftbehind.entity.SteveWork.knows(
                    dev.leftbehind.memory.SteveMemory.get(s), net.minecraft.world.item.Items.LADDER));
            if (!learned) throw new AssertionError("Steve watched a ladder being made and didn't learn it");
            server.runOnServer(s -> dev.leftbehind.story.StoryDirector.findSteve(s.overworld()).work()
                    .give(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_LOG, 4)));
            context.runOnClient(client -> client.player.connection.sendChat("yes please"));
            boolean ladder = waitFor(context, server, 2400, s -> s.getPlayerList().getPlayers().getFirst().getInventory()
                    .countItem(net.minecraft.world.item.Items.LADDER) > 0);
            context.takeScreenshot("36_made_ladder");
            server.runCommand("leftbehind status");
            if (!ladder) throw new AssertionError("Steve said yes to making a ladder but never brought one");
            boolean table = server.computeOnServer(s -> {
                BlockPos at = s.getPlayerList().getPlayers().getFirst().blockPosition();
                for (BlockPos pos : BlockPos.betweenClosed(at.offset(-12, -3, -12), at.offset(12, 3, 12))) {
                    if (s.overworld().getBlockState(pos).is(Blocks.CRAFTING_TABLE)) return true;
                }
                return false;
            });
            if (!table) throw new AssertionError("Steve made a ladder without putting down a crafting table");
            // 0.7: one table, his own, not a new one every time (Temur: "why he keeps showing off his crafting table?").
            int tables = server.computeOnServer(s -> {
                BlockPos at = s.getPlayerList().getPlayers().getFirst().blockPosition();
                int n = 0;
                for (BlockPos pos : BlockPos.betweenClosed(at.offset(-24, -4, -24), at.offset(24, 4, 24))) {
                    if (s.overworld().getBlockState(pos).is(Blocks.CRAFTING_TABLE)) n++;
                }
                return n;
            });
            if (tables > 1) throw new AssertionError("Steve put down " + tables + " crafting tables for one ladder");

            // Questions he answers from what he really knows.
            String[][] questions = {
                    {"how do you make a chest?", "37_how_chest"},
                    {"what do you have?", "38_what_he_has"},
                    {"where can i find diamonds", "39_where_diamonds"},
                    {"i like emeralds", "40_likes"},
            };
            for (String[] line : questions) {
                server.runCommand("execute at " + STEVE + " anchored eyes rotated ~ 0 run tp @a ^ ^ ^4 facing entity " + STEVE + " eyes");
                context.runOnClient(client -> client.player.connection.sendChat(line[0]));
                context.waitTicks(70);
                context.takeScreenshot(line[1]);
                context.waitTicks(120);
            }

            server.runCommand("leftbehind status");
            server.runCommand("execute as @a run steve say bye");
            context.waitTicks(60);
            // The poses he falls into during an episode.
            hud(context, false);
            String[] poses = {"cower", "stare", "panic", "rampage"};
            for (int i = 0; i < poses.length; i++) {
                server.runCommand("leftbehind debug pose " + (i + 1));
                server.runCommand("execute at " + STEVE + " anchored eyes rotated ~ 0 run tp @a ^1.8 ^ ^2.8 facing entity " + STEVE + " feet");
                context.waitTicks(15);
                context.takeScreenshot("06_pose_" + poses[i]);
            }
            server.runCommand("leftbehind debug pose -1");

            // Herobrine, by day and by night.
            server.runCommand("execute as @a at @s run tp @s ~30 ~ ~ ~ 0");
            context.waitTicks(10);
            server.runCommand("execute as @a at @s rotated ~ 0 run summon leftbehind:herobrine ^ ^ ^3");
            context.waitTicks(10);
            server.runCommand("execute as @a at @s anchored eyes run tp @s ~ ~ ~ facing entity " + HIM + " eyes");
            context.waitTicks(25);
            context.takeScreenshot("10_herobrine_day");
            // A fresh one for the night shot: in Act I he leaves once he has been stared at for a few seconds.
            server.runCommand("kill @e[type=leftbehind:herobrine]");
            server.runCommand("time set midnight");
            context.waitTicks(30);
            server.runCommand("leftbehind sense @a 0");
            server.runCommand("execute as @a at @s rotated ~ 0 run summon leftbehind:herobrine ^ ^ ^3");
            context.waitTicks(4);
            server.runCommand("execute as @a at @s anchored eyes run tp @s ~ ~ ~ facing entity " + HIM + " eyes");
            context.waitTicks(10);
            context.takeScreenshot("11_herobrine_night");
            zoom(context, 30);
            context.waitTicks(4);
            context.takeScreenshot("11b_herobrine_eyes");
            zoom(context, 70);
            server.runCommand("leftbehind status");
            server.runCommand("kill @e[type=leftbehind:herobrine]");
            server.runCommand("leftbehind debug fx 0 0");

            // The removed ones.
            server.runCommand("execute as @a at @s rotated ~ 0 run summon leftbehind:remnant ^-2.2 ^ ^8.6 {variant:0}");
            server.runCommand("execute as @a at @s rotated ~ 0 run summon leftbehind:remnant ^ ^ ^8.6 {variant:1}");
            server.runCommand("execute as @a at @s rotated ~ 0 run summon leftbehind:remnant ^2.2 ^ ^8.6 {variant:2}");
            server.runCommand("execute as @a at @s anchored eyes run tp @s ~ ~ ~ facing entity @e[type=leftbehind:remnant,limit=1,nbt={variant:1}] eyes");
            context.waitTicks(25);
            context.takeScreenshot("12_ghosts_night");
            server.runCommand("time set noon");
            context.waitTicks(25);
            context.takeScreenshot("13_ghosts_day");
            zoom(context, 30);
            context.waitTicks(5);
            context.takeScreenshot("13b_ghosts_close");
            zoom(context, 70);
            server.runCommand("execute as @a at @s rotated ~ 0 run tp @s ^ ^ ^3");
            context.waitTicks(12);
            context.takeScreenshot("14_ghosts_fading");
            context.waitTicks(30);

            // Screen effects.
            hud(context, true);
            server.runCommand("leftbehind debug fx 0 0.6");
            context.waitTicks(40);
            context.takeScreenshot("15_dread");
            server.runCommand("leftbehind debug fx 0 1.0");
            server.runCommand("leftbehind debug fx 1 60");
            context.waitTicks(30);
            context.takeScreenshot("16_dread_static");
            server.runCommand("leftbehind debug fx 2 40");
            context.waitTicks(6);
            context.takeScreenshot("17_eyes");
            server.runCommand("leftbehind debug fx 0 0");
            context.waitTicks(60);

            // A real memory breach: he runs, then falls apart where he can still be seen.
            hud(context, false);
            server.runCommand("execute at " + STEVE + " anchored eyes rotated ~ 0 run tp @a ^ ^ ^5 facing entity " + STEVE + " eyes");
            context.waitTicks(20);
            server.runCommand("leftbehind breach");
            context.waitTicks(50);
            context.takeScreenshot("18_breach_running");
            server.runCommand("leftbehind status");
            context.waitTicks(450);
            server.runCommand("leftbehind status");
            server.runCommand("execute at " + STEVE + " anchored eyes rotated ~ 0 run tp @a ^ ^ ^12 facing entity " + STEVE + " eyes");
            context.waitTicks(80);
            context.takeScreenshot("19_trauma_from_a_distance");
            context.waitTicks(120);
            context.takeScreenshot("19b_trauma_later");
            server.runCommand("execute at " + STEVE + " anchored eyes rotated ~ 0 run tp @a ^ ^ ^5 facing entity " + STEVE + " eyes");
            context.waitTicks(30);
            context.takeScreenshot("20_too_close");
            server.runCommand("leftbehind status");
            server.runCommand("leftbehind calm");
            context.waitTicks(40);
            server.runCommand("leftbehind status");

            hud(context, true);
            server.runCommand("leftbehind journal all");
            context.waitTicks(10);
            server.runCommand("execute as @a run steve journal");
            context.waitTicks(20);
            context.takeScreenshot("04_journal");
            server.runCommand("execute as @a run steve journal mem_eyes");
            context.waitTicks(20);
            context.takeScreenshot("05_journal_entry");
            context.setScreen(() -> null);
            context.waitTicks(5);

            // 0.6: Herobrine from the first evening, by himself. Far off, facing you. Look properly and he isn't there.
            server.runCommand("leftbehind calm");
            server.runCommand("kill @e[type=leftbehind:herobrine]");
            server.runCommand("execute as @a at @s run tp @s ~ ~ ~ 0 0");
            server.runCommand("time set 12400");
            boolean glimpse = waitFor(context, server, 200, s -> dev.leftbehind.story.StoryDirector.activeHerobrine() != null);
            if (!glimpse) throw new AssertionError("No glimpse of Herobrine on the first evening");
            hud(context, false);
            context.takeScreenshot("41_first_evening");
            server.runCommand("execute as @a at @s anchored eyes run tp @s ~ ~ ~ facing entity " + HIM + " eyes");
            zoom(context, 30);
            context.waitTicks(4);
            context.takeScreenshot("41b_first_evening_close");
            boolean gone = waitFor(context, server, 120, s -> dev.leftbehind.story.StoryDirector.activeHerobrine() == null);
            context.takeScreenshot("42_nobody_there");
            zoom(context, 70);
            hud(context, true);
            if (!gone) throw new AssertionError("The glimpse didn't vanish when looked at");

            // 0.5: the hill. At dusk he walks up somewhere high and talks to someone standing next to him.
            server.runCommand("leftbehind calm");
            server.runCommand("kill @e[type=leftbehind:herobrine]");
            server.runCommand("time set 12000");
            server.runCommand("execute at " + STEVE + " run tp @a ~ ~ ~6");
            context.waitTicks(20);
            server.runCommand("leftbehind scene hill");
            boolean together = waitFor(context, server, 900, s -> dev.leftbehind.story.Scenes.together()
                    && dev.leftbehind.story.StoryDirector.activeHerobrine() != null);
            if (!together) throw new AssertionError("The hill scene never brought Herobrine to Steve");
            hud(context, false);
            zoom(context, 30);
            server.runCommand("execute as @a at @s anchored eyes run tp @s ~ ~ ~ facing entity " + STEVE + " eyes");
            context.waitTicks(8);
            context.takeScreenshot("24_the_hill");
            server.runCommand("execute as @a at @s run tp @s ~ ~ ~ ~180 0");
            zoom(context, 70);
            hud(context, true);
            context.waitTicks(120);
            server.runCommand("execute at " + STEVE + " anchored eyes rotated ~ 0 run tp @a ^ ^ ^4 facing entity " + STEVE + " eyes");
            context.waitTicks(60);
            context.takeScreenshot("25_theres_nobody");
            if (server.computeOnServer(s -> dev.leftbehind.story.StoryDirector.activeHerobrine() != null)) {
                throw new AssertionError("Herobrine was still on the hill when the player got there");
            }
            waitFor(context, server, 400, s -> !dev.leftbehind.story.Scenes.busy());

            // Act II's card plays its own variation of the theme, through the game's music player.
            server.runCommand("leftbehind act 2");
            context.waitTicks(40);
            context.runOnClient(client -> {
                if (!dev.leftbehind.client.fx.ScoreClient.last().equals("music.act_2")) {
                    throw new AssertionError("The act card played \"" + dev.leftbehind.client.fx.ScoreClient.last() + "\", not music.act_2");
                }
            });

            // The sleepwalk: at night he walks off, and someone is waiting where he stops.
            server.runCommand("kill @e[type=leftbehind:herobrine]");
            server.runCommand("time set 18000");
            server.runCommand("execute at " + STEVE + " run tp @a ~ ~ ~6");
            context.waitTicks(20);
            server.runCommand("leftbehind scene sleepwalk");
            boolean digging = waitFor(context, server, 1400, s -> dev.leftbehind.story.Scenes.together());
            if (!digging) throw new AssertionError("Steve never got to where the sleepwalk was taking him");
            hud(context, false);
            server.runCommand("execute at " + STEVE + " rotated ~ 0 run tp @a ^ ^3 ^-13 facing entity " + STEVE + " feet");
            context.waitTicks(10);
            context.takeScreenshot("26_sleepwalk");
            hud(context, true);
            waitFor(context, server, 400, s -> !dev.leftbehind.story.Scenes.busy());
            context.takeScreenshot("27_sleepwalk_wakes");
            int found = server.computeOnServer(s -> s.overworld().getEntities(net.minecraft.world.entity.EntityTypes.ITEM,
                    e -> e.getItem().has(net.minecraft.core.component.DataComponents.CUSTOM_NAME)).size());
            if (found == 0) throw new AssertionError("Steve dug and found nothing");

            // Caves: a tunnel deep down, his old torches ahead, the sign, and someone at the end of the light.
            server.runCommand("time set noon");
            // The test world is flat, so the cave is built: a closed stone tube with a roof, under the ground.
            server.runCommand("execute at @p run fill ~-2 -64 ~-3 ~46 -54 ~3 stone");
            server.runCommand("execute at @p run fill ~ -62 ~-1 ~44 -60 ~1 air");
            server.runCommand("execute at @p run tp @p ~1 -62 ~ -90 0");
            context.waitTicks(40);
            server.runCommand("execute as @a run leftbehind cave sign");
            server.runCommand("execute as @a run leftbehind cave torches");
            context.waitTicks(20);
            context.takeScreenshot("28_cave_torches");
            int torches = server.computeOnServer(s -> {
                BlockPos at = s.getPlayerList().getPlayers().getFirst().blockPosition();
                int n = 0;
                for (BlockPos pos : BlockPos.betweenClosed(at.offset(-2, -2, -3), at.offset(44, 3, 3))) {
                    if (s.overworld().getBlockState(pos).is(Blocks.WALL_TORCH)) n++;
                }
                return n;
            });
            if (torches == 0) throw new AssertionError("No torches were waiting in the cave");
            server.runCommand("execute as @a run leftbehind cave footsteps");
            context.waitTicks(20);
            context.takeScreenshot("29_cave_sign");
            server.runCommand("execute as @a run leftbehind cave end");
            context.waitTicks(12);
            context.takeScreenshot("30_end_of_the_light");
            context.waitTicks(60);
            context.takeScreenshot("31_torch_out");
            server.runCommand("execute at @p run tp @p ~ ~100 ~");
            server.runCommand("execute as @a at @s run spreadplayers ~ ~ 0 1 false @s");
            context.waitTicks(40);
            server.runCommand("leftbehind status");

            // He learns your places and waits where you're going.
            server.runOnServer(s -> {
                var player = s.getPlayerList().getPlayers().getFirst();
                var r = dev.leftbehind.memory.SteveMemory.get(s).relationship(player.getUUID());
                int cx = Math.floorDiv(player.getBlockX() + 50, 24);
                int cz = Math.floorDiv(player.getBlockZ(), 24);
                for (int i = 0; i < 6; i++) r.marks.put("haunt:" + (cx + (i % 2)) + "," + (cz + i / 2 - 1), 5L);
            });
            server.runCommand("leftbehind sense @a 0");
            server.runCommand("execute as @a run leftbehind herobrine ahead");
            context.waitTicks(10);
            BlockPos waiting = server.computeOnServer(s -> {
                var h = dev.leftbehind.story.StoryDirector.activeHerobrine();
                return h == null ? null : h.blockPosition();
            });
            if (waiting == null) throw new AssertionError("Herobrine didn't go to wait where the player goes");
            server.runCommand("execute as @a at @s anchored eyes run tp @s ~ ~ ~ facing entity " + HIM + " eyes");
            zoom(context, 30);
            context.waitTicks(10);
            context.takeScreenshot("43_waiting_ahead");
            zoom(context, 70);
            server.runCommand("tp @a " + waiting.getX() + " " + waiting.getY() + " " + waiting.getZ());
            boolean arrived = waitFor(context, server, 200, s -> dev.leftbehind.memory.SteveMemory.get(s).counter("waited_for") > 0);
            context.takeScreenshot("44_you_always_come_this_way");
            if (!arrived) throw new AssertionError("Arriving where he waited did nothing");

            // Structures: the Pillars (seven, then eight), the Hallway (the lights go out behind you), the Obsidian Box (knocking).
            server.runCommand("time set noon");
            server.runCommand("execute as @a at @s run tp @s ~ ~ ~ -90 10");
            context.waitTicks(10);
            server.runCommand("execute as @a run leftbehind structure pillars");
            context.waitTicks(20);
            hud(context, false);
            server.runCommand("execute as @a at @s run tp @s ~-6 ~4 ~ -90 15");
            context.waitTicks(20);
            context.takeScreenshot("45_pillars");
            server.runOnServer(s -> {
                var memory = dev.leftbehind.memory.SteveMemory.get(s);
                var player = s.getPlayerList().getPlayers().getFirst();
                int x = (int) memory.counter("st_pillars_x"), y = (int) memory.counter("st_pillars_y"), z = (int) memory.counter("st_pillars_z");
                double a = Math.PI * 2 * memory.counter("st_pillars_gap") / 8;
                float yaw = (float) Math.toDegrees(Math.atan2(Math.cos(a), -Math.sin(a)));
                player.teleportTo(s.overworld(), x + 0.5, y, z + 0.5, java.util.Set.of(), yaw, 0f, false);
            });
            boolean eight = waitFor(context, server, 300, s -> dev.leftbehind.memory.SteveMemory.get(s).flag("st_pillars_done"));
            server.runCommand("execute as @a at @s run tp @s ~ ~ ~ ~180 0");
            context.waitTicks(10);
            context.takeScreenshot("46_eight_pillars");
            hud(context, true);
            if (!eight) throw new AssertionError("The eighth pillar never came");

            server.runCommand("execute as @a at @s run tp @s ~40 ~ ~ -90 0");
            context.waitTicks(20);
            // The test world is flat and shallow, so the hallway gets a hill of stone to go down into.
            server.runCommand("execute at @p run fill ~-12 -60 ~-6 ~54 -46 ~6 stone");
            server.runCommand("execute at @p run tp @p ~ -45 ~ -90 20");
            context.waitTicks(10);
            server.runCommand("execute as @a run leftbehind structure hallway");
            context.waitTicks(20);
            hud(context, false);
            server.runCommand("execute at @p run tp @p ~-6 ~ ~ -90 20");
            context.waitTicks(20);
            context.takeScreenshot("47_hallway_stairs");
            server.runOnServer(s -> {
                var memory = dev.leftbehind.memory.SteveMemory.get(s);
                var player = s.getPlayerList().getPlayers().getFirst();
                var dir = net.minecraft.core.Direction.from2DDataValue((int) memory.counter("st_hallway_dir"));
                BlockPos at = new BlockPos((int) memory.counter("st_hallway_x"), (int) memory.counter("st_hallway_y"), (int) memory.counter("st_hallway_z"));
                BlockPos mid = at.relative(dir, 9 + 14).below(9);
                player.teleportTo(s.overworld(), mid.getX() + 0.5, mid.getY(), mid.getZ() + 0.5, java.util.Set.of(), dir.toYRot(), 0f, false);
            });
            context.waitTicks(30);
            context.takeScreenshot("48_hallway_words");
            server.runOnServer(s -> {
                var memory = dev.leftbehind.memory.SteveMemory.get(s);
                var player = s.getPlayerList().getPlayers().getFirst();
                var dir = net.minecraft.core.Direction.from2DDataValue((int) memory.counter("st_hallway_dir"));
                BlockPos at = new BlockPos((int) memory.counter("st_hallway_x"), (int) memory.counter("st_hallway_y"), (int) memory.counter("st_hallway_z"));
                BlockPos room = at.relative(dir, 9 + 34 + 3).below(9);
                player.teleportTo(s.overworld(), room.getX() + 0.5, room.getY(), room.getZ() + 0.5, java.util.Set.of(), dir.toYRot(), 10f, false);
            });
            boolean lightsOut = waitFor(context, server, 100, s -> dev.leftbehind.memory.SteveMemory.get(s).flag("st_hallway_done"));
            context.waitTicks(10);
            context.takeScreenshot("49_hallway_room");
            server.runCommand("execute as @a at @s run tp @s ~ ~ ~ ~180 0");
            context.waitTicks(60);
            context.takeScreenshot("50_lights_going_out");
            hud(context, true);
            if (!lightsOut) throw new AssertionError("Reaching the room at the end of the hallway did nothing");
            server.runCommand("execute at @p run tp @p ~ ~100 ~");
            server.runCommand("execute as @a at @s run spreadplayers ~ ~ 0 1 false @s");
            context.waitTicks(30);

            server.runCommand("execute as @a at @s run tp @s ~-30 ~ ~ -90 10");
            context.waitTicks(20);
            server.runCommand("execute as @a run leftbehind structure box");
            context.waitTicks(10);
            hud(context, false);
            server.runCommand("execute as @a at @s run tp @s ~-2 ~1 ~ -90 10");
            context.waitTicks(80);
            context.takeScreenshot("51_obsidian_box");
            hud(context, true);
            server.runOnServer(s -> {
                var memory = dev.leftbehind.memory.SteveMemory.get(s);
                BlockPos at = new BlockPos((int) memory.counter("st_box_x"), (int) memory.counter("st_box_y"), (int) memory.counter("st_box_z"));
                s.overworld().setBlock(at.offset(-2, 1, 0), Blocks.AIR.defaultBlockState(), 3);
            });
            boolean opened = waitFor(context, server, 100, s -> dev.leftbehind.memory.SteveMemory.get(s).flag("st_box_done"));
            context.takeScreenshot("52_box_opened");
            if (!opened) throw new AssertionError("Opening the box did nothing");
            server.runCommand("leftbehind status");

            // ---- 0.7 -----------------------------------------------------------------------------------
            // He climbs a ladder to get to you, the way you would.
            server.runCommand("leftbehind calm");
            server.runCommand("kill @e[type=leftbehind:herobrine]");
            server.runCommand("time set noon");
            // Away from the stone hill the hallway test built, onto the flat ground, with Steve.
            server.runCommand("execute at @p run spreadplayers ~ ~-60 0 1 false @p");
            server.runCommand("execute as @a at @s run tp @s ~ ~ ~ 90 0");
            server.runCommand("leftbehind summon");
            context.waitTicks(20);
            server.runCommand("execute at @p run fill ~-3 ~ ~-4 ~-12 ~6 ~4 stone");
            server.runCommand("execute at @p run fill ~-2 ~ ~ ~-2 ~6 ~ ladder[facing=east]");
            server.runCommand("execute at @p run tp " + STEVE + " ~ ~ ~");
            server.runCommand("execute at @p run tp @p ~-5 ~7 ~ -90 30");
            context.waitTicks(10);
            BlockPos top = server.computeOnServer(s -> s.getPlayerList().getPlayers().getFirst().blockPosition());
            context.runOnClient(client -> client.player.connection.sendChat("steve follow me"));
            context.waitTicks(40);
            // Whatever he made of that, he is following now: this part is about the ladder, not the talking.
            server.runOnServer(s -> dev.leftbehind.story.StoryDirector.findSteve(s.overworld())
                    .orderFollow(s.getPlayerList().getPlayers().getFirst()));
            boolean climbed = waitFor(context, server, 700, s -> {
                var steve = dev.leftbehind.story.StoryDirector.findSteve(s.overworld());
                return steve != null && steve.getY() >= top.getY() - 0.5;
            });
            hud(context, false);
            server.runCommand("execute as @a at @s anchored eyes run tp @s ~ ~ ~ facing entity " + STEVE + " eyes");
            context.waitTicks(10);
            context.takeScreenshot("53_climbed_up");
            hud(context, true);
            server.runCommand("leftbehind status");
            if (!climbed) throw new AssertionError("Steve never got up the ladder to the player");

            // He swims out of water instead of sinking.
            server.runCommand("execute as @a at @s run tp @s ~-20 -60 ~");
            context.waitTicks(10);
            server.runCommand("execute at @p run fill ~-14 -64 ~-6 ~-2 -61 ~6 stone");
            server.runCommand("execute at @p run fill ~-13 -63 ~-5 ~-3 -61 ~5 water");
            server.runCommand("execute at @p run tp " + STEVE + " ~-8 -62 ~");
            context.waitTicks(30);
            hud(context, false);
            server.runCommand("execute at " + STEVE + " anchored eyes rotated ~ 0 run tp @a ^ ^2 ^6 facing entity " + STEVE + " eyes");
            context.waitTicks(10);
            context.takeScreenshot("54_swimming");
            hud(context, true);
            server.runCommand("execute at @p run tp @p ~ -60 ~");
            boolean dry = waitFor(context, server, 600, s -> {
                var steve = dev.leftbehind.story.StoryDirector.findSteve(s.overworld());
                return steve != null && !steve.isInWater() && steve.onGround();
            });
            context.takeScreenshot("55_out_of_the_water");
            if (!dry) throw new AssertionError("Steve stayed in the water");

            // Ask him to build a shelter. He picks a flat spot and builds walls, a roof and a door.
            // Somewhere flat and open, away from the cliff and the pool.
            server.runCommand("execute at @p run spreadplayers ~ ~40 0 1 false @p");
            context.waitTicks(10);
            server.runCommand("execute at @p run tp " + STEVE + " ~3 ~ ~");
            server.runOnServer(s -> dev.leftbehind.story.StoryDirector.findSteve(s.overworld()).work()
                    .give(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COBBLESTONE, 64)));
            server.runOnServer(s -> dev.leftbehind.story.StoryDirector.findSteve(s.overworld()).work()
                    .give(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_PLANKS, 16)));
            server.runCommand("execute at " + STEVE + " anchored eyes rotated ~ 0 run tp @a ^ ^ ^4 facing entity " + STEVE + " eyes");
            context.waitTicks(10);
            context.runOnClient(client -> client.player.connection.sendChat("can you build us a shelter"));
            boolean started = waitFor(context, server, 200,
                    s -> dev.leftbehind.story.StoryDirector.findSteve(s.overworld()).work().chores().build().building());
            context.takeScreenshot("56_build_shelter");
            if (!started) throw new AssertionError("Steve didn't start building when asked for a shelter");
            boolean built = waitFor(context, server, 3600, s -> dev.leftbehind.memory.SteveMemory.get(s).counter("builds_done") > 0);
            hud(context, false);
            server.runOnServer(s -> {
                var memory = dev.leftbehind.memory.SteveMemory.get(s);
                var player = s.getPlayerList().getPlayers().getFirst();
                player.teleportTo(s.overworld(), memory.counter("base_x") + 8.5, memory.counter("base_y") + 4, memory.counter("base_z") + 8.5,
                        java.util.Set.of(), 135f, 25f, false);
            });
            context.waitTicks(20);
            context.takeScreenshot("57_shelter_built");
            hud(context, true);
            server.runCommand("leftbehind status");
            if (!built) throw new AssertionError("Steve started a shelter and never finished it");

            // The new things that happen to you. Each is forced here once, to see it, out in the open.
            server.runCommand("leftbehind act 3");
            server.runCommand("time set 18000");
            server.runCommand("kill @e[type=leftbehind:herobrine]");
            server.runCommand("execute at @p run spreadplayers ~ ~-40 0 1 false @p");
            server.runCommand("execute as @a at @s run tp @s ~ ~ ~ 0 0");
            context.waitTicks(220); // the act card fades
            if (!haunt(server, "other_steve")) throw new AssertionError("The other Steve didn't appear");
            context.waitTicks(4);
            hud(context, false);
            server.runCommand("execute as @a at @s anchored eyes run tp @s ~ ~ ~ facing entity " + HIM + " eyes");
            zoom(context, 25);
            context.waitTicks(3);
            context.takeScreenshot("58_the_other_steve");
            zoom(context, 70);
            hud(context, true);
            boolean otherGone = waitFor(context, server, 200, s -> dev.leftbehind.story.StoryDirector.activeHerobrine() == null);
            if (!otherGone) throw new AssertionError("The other Steve stayed when looked at");
            server.runCommand("execute at @p run summon cow ~4 ~ ~3");
            server.runCommand("execute at @p run summon cow ~-4 ~ ~3");
            server.runCommand("execute at @p run summon sheep ~3 ~ ~-4");
            server.runCommand("execute at @p run summon pig ~-3 ~ ~-4");
            server.runCommand("execute at @p run summon cow ~6 ~ ~-2");
            context.waitTicks(10);
            if (!haunt(server, "animals")) throw new AssertionError("The animals didn't stare");
            context.waitTicks(60);
            hud(context, false);
            context.takeScreenshot("59_animals_stare");
            hud(context, true);
            server.runCommand("execute at @p run fill ~-1 ~ ~-7 ~1 ~1 ~-5 air");
            server.runCommand("execute at @p run setblock ~ ~ ~-6 oak_door[half=lower,facing=north]");
            server.runCommand("execute at @p run setblock ~ ~1 ~-6 oak_door[half=upper,facing=north]");
            server.runCommand("execute as @a at @s run tp @s ~ ~ ~ 0 0");
            context.waitTicks(5);
            if (!haunt(server, "door")) throw new AssertionError("The door didn't open by itself");
            server.runCommand("execute at @p run fill ~-5 ~ ~-5 ~5 ~ ~5 torch replace air");
            if (!haunt(server, "reset")) throw new AssertionError("Nothing flickered");
            context.waitTicks(20);
            context.takeScreenshot("60_world_flickers");
            context.waitTicks(45);
            context.takeScreenshot("61_world_back");
            int torchesBack = server.computeOnServer(s -> {
                BlockPos at = s.getPlayerList().getPlayers().getFirst().blockPosition();
                int n = 0;
                for (BlockPos pos : BlockPos.betweenClosed(at.offset(-5, -1, -5), at.offset(5, 1, 5))) {
                    if (s.overworld().getBlockState(pos).is(Blocks.TORCH)) n++;
                }
                return n;
            });
            if (torchesBack < 20) throw new AssertionError("The flicker really removed blocks (" + torchesBack + " torches left)");
            server.runCommand("execute at @p run fill ~-5 ~ ~-5 ~5 ~ ~5 air replace torch");
            server.runCommand("execute as @a at @s run tp @s ~ ~ ~ 0 0");
            if (!haunt(server, "sign_words")) throw new AssertionError("No sign with the player's words");
            context.waitTicks(10);
            context.takeScreenshot("62_sign_with_your_words");
            haunt(server, "echo");
            haunt(server, "footsteps");
            context.waitTicks(40);
            server.runCommand("execute at " + STEVE + " anchored eyes rotated ~ 0 run tp @a ^ ^ ^4 facing entity " + STEVE + " eyes");
            context.waitTicks(700);
            context.takeScreenshot("63_steve_explains");
            int beats = server.computeOnServer(s -> dev.leftbehind.memory.SteveMemory.get(s).storyLog.size());
            if (beats < 5) throw new AssertionError("The story log only has " + beats + " entries");
            server.runCommand("execute as @a run steve journal");
            context.waitTicks(20);
            context.takeScreenshot("64_where_things_stand");
            context.setScreen(() -> null);
            context.waitTicks(5);
            server.runCommand("leftbehind status");

            // The hunt.
            server.runCommand("leftbehind settings hunt true");
            server.runCommand("gamemode survival @a");
            server.runCommand("leftbehind herobrine hunt");
            context.waitTicks(30);
            server.runCommand("execute as @a at @s anchored eyes run tp @s ~ ~ ~ facing entity " + HIM + " eyes");
            context.waitTicks(10);
            context.takeScreenshot("21_hunt_start");
            context.waitTicks(60);
            server.runCommand("execute as @a at @s anchored eyes run tp @s ~ ~ ~ facing entity " + HIM + " eyes");
            context.waitTicks(5);
            context.takeScreenshot("22_hunt_chase");
            server.runCommand("leftbehind status");
            context.waitTicks(200);
            context.takeScreenshot("23_removed");
            server.runCommand("leftbehind status");
        }
    }
}
