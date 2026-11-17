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

            // Steve, and talking to him.
            server.runCommand("leftbehind summon");
            context.waitTicks(30);
            server.runCommand("execute at " + STEVE + " anchored eyes run tp @a ^ ^ ^3.5 facing entity " + STEVE + " eyes");
            context.waitTicks(20);
            context.takeScreenshot("01_steve");
            server.runCommand("leftbehind debug talk");
            context.waitTicks(30);
            context.takeScreenshot("02_dialogue");
            server.runCommand("execute as @a run steve ask who");
            context.waitTicks(20);
            context.takeScreenshot("03_answer");
            server.runCommand("execute as @a run steve ask bye");
            context.waitTicks(10);
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
