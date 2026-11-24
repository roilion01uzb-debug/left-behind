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
            server.runCommand("execute at " + STEVE + " anchored eyes run tp @a ^ ^ ^4 facing entity " + STEVE + " eyes");
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
                server.runCommand("execute at " + STEVE + " anchored eyes run tp @a ^ ^ ^4 facing entity " + STEVE + " eyes");
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
            server.runCommand("leftbehind status");
            server.runCommand("execute as @a run steve say bye");
            context.waitTicks(60);
            // The poses he falls into during an episode.
            hud(context, false);
            String[] poses = {"cower", "stare", "panic", "rampage"};
            for (int i = 0; i < poses.length; i++) {
                server.runCommand("leftbehind debug pose " + (i + 1));
                server.runCommand("execute at " + STEVE + " anchored eyes run tp @a ^1.8 ^ ^2.8 facing entity " + STEVE + " feet");
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
            server.runCommand("execute at " + STEVE + " anchored eyes run tp @a ^ ^ ^5 facing entity " + STEVE + " eyes");
            context.waitTicks(20);
            server.runCommand("leftbehind breach");
            context.waitTicks(50);
            context.takeScreenshot("18_breach_running");
            server.runCommand("leftbehind status");
            context.waitTicks(450);
            server.runCommand("leftbehind status");
            server.runCommand("execute at " + STEVE + " anchored eyes run tp @a ^ ^ ^12 facing entity " + STEVE + " eyes");
            context.waitTicks(80);
            context.takeScreenshot("19_trauma_from_a_distance");
            context.waitTicks(120);
            context.takeScreenshot("19b_trauma_later");
            server.runCommand("execute at " + STEVE + " anchored eyes run tp @a ^ ^ ^5 facing entity " + STEVE + " eyes");
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
            server.runCommand("execute at " + STEVE + " anchored eyes run tp @a ^ ^ ^4 facing entity " + STEVE + " eyes");
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
            server.runCommand("execute at @p run fill ~ -31 ~-1 ~44 -29 ~1 air");
            server.runCommand("execute at @p run tp @p ~1 -31 ~ -90 0");
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
