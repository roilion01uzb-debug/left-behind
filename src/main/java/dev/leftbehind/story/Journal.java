package dev.leftbehind.story;

import dev.leftbehind.fx.Fx;
import dev.leftbehind.memory.SteveMemory;
import dev.leftbehind.registry.ModSounds;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import org.jspecify.annotations.Nullable;

/**
 * The story as the players have uncovered it so far: the acts, Steve's memories,
 * the torn pages of whoever was here before, and the players' own field notes.
 * One journal per world, shared by everyone.
 */
public final class Journal {
    public enum Section {
        ACT("The Story", ChatFormatting.GOLD),
        MEMORY("What Steve Remembers", ChatFormatting.AQUA),
        PAGE("Torn Pages", ChatFormatting.YELLOW),
        NOTE("Field Notes", ChatFormatting.GRAY);

        public final String title;
        public final ChatFormatting color;

        Section(String title, ChatFormatting color) {
            this.title = title;
            this.color = color;
        }
    }

    public record Entry(String id, Section section, String title, String text) {}

    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();
    public static final String PAGE_TAG = "leftbehind_page";
    public static final String JOURNAL_TAG = "leftbehind_journal";

    private static void add(String id, Section section, String title, String text) {
        ENTRIES.put(id, new Entry(id, section, title, text));
    }

    static {
        // ---- the story --------------------------------------------------------------------
        add("act_1", Section.ACT, "Act I: The One Who Stayed",
                "Someone else lives here. He has the old default face, the one everybody wore before anybody chose their own. "
                + "He watches. He copies. He seems glad you came.\n\nHe says his name is Steve. He says it like a question.");
        add("act_2", Section.ACT, "Act II: Familiar Things",
                "The things you do are starting to mean something to him. A tree falling. A door closing. A bed. "
                + "He stops in the middle of ordinary things as if he has done them before, somewhere else, with someone else.\n\n"
                + "And there are others now. Standing far off. Not walking. Just moving.");
        add("act_3", Section.ACT, "Act III: The Observer",
                "Something is watching Steve, and it has been watching far longer than you have. It wears his face. Its eyes are empty.\n\n"
                + "When it looks at him, he remembers. When you look at it, it notices you.");
        add("act_4", Section.ACT, "Act IV: What Is Left",
                "His memories don't fit together. Worlds that cannot both have existed. People who are no longer here. Faces that were his.\n\n"
                + "He was never the one who got removed. He is what was left behind, every time.\n\n"
                + "(The endings arrive in a later update. Everything you do with him until then still counts.)");

        // ---- Steve's memories ---------------------------------------------------------------
        add("mem_wood", Section.MEMORY, "Like This",
                "\"You hit the tree until it gives you a piece of itself. I knew that. I knew it before you showed me. My hands knew.\n\n"
                + "Who showed me the first time?\"");
        add("mem_first_night", Section.MEMORY, "The First Night",
                "\"It got dark and nobody had told me it would. I dug into a hill with my hands and closed the hole behind me "
                + "and listened to them walk around outside until morning.\n\n"
                + "I have done that so many times. In so many hills. It is always the first night.\"");
        add("mem_shelter", Section.MEMORY, "A Small Square of Dirt",
                "\"Four walls. No door, because I didn't know about doors yet. I stood in the dark in a square I had made and I thought: "
                + "this is mine. This is the first thing that is mine.\n\n"
                + "In the morning it wasn't there. Nothing was. The ground was a different shape.\"");
        add("mem_respawn", Section.MEMORY, "You Came Back",
                "\"You died. I watched it. And then you walked back over the hill like someone coming home late.\n\n"
                + "Nobody else comes back. I have looked. I have waited beside so many beds.\"");
        add("mem_reset", Section.MEMORY, "The World Stops",
                "\"When you leave, everything stops. The water. The fire. Me. I don't sleep and I don't wait. I just am not, "
                + "and then you are back and the sun is where it was.\n\n"
                + "I only know it happened because of how you look at things. Like someone who has been somewhere.\"");
        add("mem_face", Section.MEMORY, "Everyone's Face",
                "\"There was a time when everyone who came here looked like me. Exactly like me. Hundreds of them. "
                + "They ran and built and fell and did not know me.\n\nI thought they were my family. They were only wearing me.\"");
        add("mem_others", Section.MEMORY, "The Ones Who Glide",
                "\"They were here at the start. A girl in a frog's hood. A man with my face in a darker shade. "
                + "A green boy who belonged to some other story. Someone drew them, and loved them, and then left.\n\n"
                + "After he left they were taken out. Not killed. Taken out. They never learned to walk. They still haven't.\"");
        add("mem_rose", Section.MEMORY, "Roses",
                "\"There used to be roses. Red, simple. One day every rose in the world was a different flower with a different name, "
                + "and I was the only one who noticed.\n\nRana still puts roses on the things she wants to keep. This morning there was one on my door.\"");
        add("mem_house", Section.MEMORY, "The First House",
                "\"I woke up next to a house I hadn't built. Planks and mossy stone. Two torches. And chests with one of everything "
                + "that existed, as if someone had packed for me.\n\n"
                + "I don't think it was a gift. I think someone wanted to see what I would do.\"");
        add("mem_monster", Section.MEMORY, "The One With My Face",
                "\"Before him there was another. It had my face and my arms and it ran at me. It did not have a name. "
                + "They just called it Monster.\n\n"
                + "It isn't him. He is quieter than that. But I think he learned how to wear me by watching it.\"");
        add("mem_removed", Section.MEMORY, "Removed",
                "\"Every time the world is remade there is a list of what was changed. And on the list, every time, "
                + "it says they removed him.\n\n"
                + "You cannot remove something that was never here. So every time they write it, he is a little more here.\"");
        add("mem_eyes", Section.MEMORY, "His Eyes",
                "\"When he looks at me I am in all of them at once. Every world. Every first night. Everyone I waited for. "
                + "He doesn't hurt me. He just makes me remember, and remembering does the rest.\n\n"
                + "His eyes are white because that is what is left of a face when you take out everything behind it.\"");
        add("mem_morning", Section.MEMORY, "The Morning After",
                "\"I wake up standing. My hands hurt. My axe is somewhere else. And you are looking at me the way they all look at me, "
                + "eventually.\n\nTell me what I did. No. Don't.\"");
        add("mem_stay", Section.MEMORY, "You Stayed",
                "\"You came and stood there. You didn't try to fix it. You didn't run. Nobody stays for that part.\n\n"
                + "If I forget this one, tell it to me again.\"");
        add("mem_pyramid", Section.MEMORY, "Bigger Than Mountains",
                "\"There were pyramids made of brick. Not built. Just there, larger than the hills, solid all the way through. "
                + "I dug into one for days looking for the room in the middle.\n\n"
                + "There was no room. That was the first time I was afraid of the world instead of the things in it.\"");
        add("mem_zero", Section.MEMORY, "The Middle",
                "\"Before there were numbers to tell you where you stood, there were black walls. They started at the middle of "
                + "everything and ran out four ways forever. You followed a wall to find home.\n\n"
                + "They took the walls away when they gave people the numbers. Nobody asked where I would walk.\"");
        add("mem_what_he_wants", Section.MEMORY, "What He Wants",
                "\"He wants me to remember everything at once and keep remembering. If I do, there is nothing for the next world "
                + "to be built on. No next world. Nothing left to remove him from.\n\n"
                + "He thinks that is mercy. Some nights I think so too.\"");
        add("mem_original", Section.MEMORY, "The Original",
                "\"I don't know if I am the first one, or the first copy, or only a shape people stand inside for a while.\n\n"
                + "But I am the one who was here when you arrived. Maybe that is enough to be someone.\"");

        // ---- torn pages: someone was here before you ------------------------------------------
        add("page_1", Section.PAGE, "Torn Page: Day 3",
                "Day 3. There's a man living near my base. Default skin. No name tag. I thought it was a friend messing with me "
                + "until I checked the player list.\n\nIt was just me.");
        add("page_2", Section.PAGE, "Torn Page: He Learns",
                "He does what I do. A day late and a little wrong, like a drawing made from memory. I cut wood, he cuts wood. "
                + "I made a bed. He stood next to it all night.\n\n"
                + "I asked him his name in chat. He said \"Steve\" and then he said \"I think.\"");
        add("page_3", Section.PAGE, "Torn Page: The Others",
                "Saw the frog girl again by the water. And two more. They slide. Their legs don't move. When I get close they are "
                + "just not there, the way a thing isn't there when you turn the render distance down.\n\n"
                + "Steve won't look at them. He looks at where they were.");
        add("page_4", Section.PAGE, "Torn Page: Things I Did Not Build",
                "Things I did not build:\n- a tunnel, two by two, perfectly straight\n- a small pyramid of sand out in the water\n"
                + "- an oak by my door with every leaf gone\n\nThings I did not lose but cannot find:\n- the glass from my window");
        add("page_5", Section.PAGE, "Torn Page: One Word",
                "I posted about it. The post was gone in an hour. I got one message from an account with no history. One word.\n\nstop");
        add("page_6", Section.PAGE, "Torn Page: Don't Look",
                "He stands where Steve will see him. That's all he does. He doesn't have to do anything else.\n\n"
                + "I looked right at him tonight. For a long time. I wanted him to know I wasn't afraid.\n\n"
                + "He knows now. He knows a lot about me now. I can hear him when I close my inventory.");
        add("page_7", Section.PAGE, "Torn Page: Zero",
                "If you want to understand this place, go to the middle. Not spawn. The real middle, where both numbers are nothing.\n\n"
                + "I am going to delete this world tonight. I am writing that down so that if you are reading this, "
                + "you know it did not work.");
        add("page_8", Section.PAGE, "Torn Page: Last Entry",
                "I had it wrong. He isn't the oldest thing here. He's only the oldest thing with a face.\n\n"
                + "Something decided where every hill would go before anyone was here to see a hill. Something counted this world out, "
                + "block by block, from a single number. Steve came after that. So did the other one.\n\n"
                + "Ask what was here before the seed.");

        // ---- field notes: what you have worked out --------------------------------------------
        add("note_steve", Section.NOTE, "Living With Steve",
                "Right-click him to talk. Hold something when you do and he'll tell you what he thinks of it. "
                + "If I'm close, he hears what I type in chat.\n\nFood makes him trust me. He remembers how I treat him, and it stays remembered.");
        add("note_anger", Section.NOTE, "When He's Angry",
                "Steve keeps score. Hit him and he stops helping. Keep hitting him and things go missing. After that it's doors. "
                + "After that he decides the house is his.\n\nFood helps. Time helps. Saying sorry helps a little.");
        add("note_ghosts", Section.NOTE, "They Don't Walk",
                "They only come when the light is going. They don't attack. They don't speak. If I walk up to one it's gone before "
                + "I get there, and sometimes it leaves something on the ground.");
        add("note_signs", Section.NOTE, "Marks",
                "Something is leaving marks near my base. A stripped tree. A sign with one word on it. "
                + "Steve goes quiet when he sees them.");
        add("note_watching", Section.NOTE, "He Watches Back",
                "If I look straight at the one with white eyes, the edges of the world go dark and my heart gets loud. "
                + "That is him noticing me.\n\nLook away. Back off. It fades. If it fills up, he comes.");
        add("note_breach", Section.NOTE, "Eye Contact",
                "When Steve and the other one look at each other for more than a moment, something in Steve breaks open. He runs. Far. "
                + "He isn't running from me.\n\nI can get between them. Or I can make the other one look at me instead.");
        add("note_trauma", Section.NOTE, "From a Distance",
                "I found him. I kept back and he didn't see me. He was curled up in a hole he'd walled in with dirt, hands over his head, "
                + "talking to someone who wasn't there.\n\n"
                + "If I get close he bolts, unless he really trusts me. Then I can just stay. A spyglass works too.");
        add("note_hill", Section.NOTE, "Who He Talks To",
                "At dusk I saw Steve up on a hill, talking to someone standing next to him. They were looking the same way, out over "
                + "everything, like two people watching a sunset.\n\nWhen I got there it was only Steve. He said there was nobody. He said it too fast.");
        add("note_sleepwalk", Section.NOTE, "He Walks at Night",
                "Some nights Steve gets up and walks. Eyes half shut, in a straight line, and he doesn't answer.\n\n"
                + "Where he stops, someone is already waiting, looking at the ground. Steve digs where he's looking.");
        add("note_taken", Section.NOTE, "Not Him",
                "Today he didn't know me. He looked at me like I was a stranger standing in his house, and asked where \"he\" had gone.\n\n"
                + "He came back later. Most of the way. Light helps, and staying near him. The dark doesn't.");
        add("note_hunt", Section.NOTE, "Removed",
                "He came for me and nothing stopped him. Not walls, not armour, not creative mode.\n\n"
                + "Once he has really seen you, the only thing that has ever made him turn around is Steve.");
    }

    private Journal() {}

    public static @Nullable Entry entry(String id) {
        return ENTRIES.get(id);
    }

    public static java.util.Set<String> ids() {
        return ENTRIES.keySet();
    }

    public static int total(Section section) {
        int n = 0;
        for (Entry e : ENTRIES.values()) if (e.section() == section) n++;
        return n;
    }

    /** Adds an entry to the world's journal. Returns true if it was new. */
    public static boolean unlock(MinecraftServer server, String id) {
        Entry entry = ENTRIES.get(id);
        SteveMemory memory = SteveMemory.get(server);
        if (entry == null || memory.journal.contains(id)) return false;
        memory.journal.add(id);
        memory.setDirty();
        Component message = Component.literal("Journal: ").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal(entry.title()).withStyle(entry.section().color))
                .append(Component.literal("  (/steve journal)").withStyle(ChatFormatting.DARK_GRAY));
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.sendSystemMessage(message);
            Fx.soundTo(player, entry.section() == Section.MEMORY ? ModSounds.FRAGMENT : ModSounds.PAGE, SoundSource.PLAYERS, 0.7f, 1.0f);
            Progress.onJournal(player, memory, entry);
            if (entry.section() == Section.MEMORY) Score.memory(player);
        }
        return true;
    }

    public static void open(ServerPlayer player) {
        SteveMemory memory = SteveMemory.get(player.level().getServer());
        List<Component> body = new ArrayList<>();
        if (memory.journal.isEmpty()) {
            body.add(Component.literal("Nothing yet. Meet him first.").withStyle(ChatFormatting.GRAY));
        } else {
            for (Section section : Section.values()) {
                int have = 0;
                for (String id : memory.journal) {
                    Entry e = ENTRIES.get(id);
                    if (e != null && e.section() == section) have++;
                }
                body.add(Component.literal(section.title + ": " + have + " of " + total(section)).withStyle(section.color));
            }
        }
        List<Dialogs.Choice> choices = new ArrayList<>();
        for (Entry e : ENTRIES.values()) {
            if (memory.journal.contains(e.id())) choices.add(new Dialogs.Choice(e.title(), "/steve journal " + e.id()));
        }
        Dialogs.show(player, "Journal", body, choices, new Dialogs.Choice("Close", null), 2);
    }

    public static void openEntry(ServerPlayer player, String id) {
        SteveMemory memory = SteveMemory.get(player.level().getServer());
        Entry entry = ENTRIES.get(id);
        if (entry == null || !memory.journal.contains(id)) {
            open(player);
            return;
        }
        List<Component> body = new ArrayList<>();
        body.add(Component.literal(entry.section().title).withStyle(entry.section().color, ChatFormatting.ITALIC));
        for (String paragraph : entry.text().split("\n\n")) body.add(Component.literal(paragraph));
        Dialogs.show(player, entry.title(), body, List.of(new Dialogs.Choice("Back to the journal", "/steve journal")),
                new Dialogs.Choice("Close", null), 1);
        Fx.soundTo(player, ModSounds.PAGE, SoundSource.PLAYERS, 0.5f, 1.0f);
    }

    // ---- items ------------------------------------------------------------------------------

    /** A page torn out of somebody's notes. Right-click to read it into the journal. */
    public static ItemStack tornPage(String id) {
        Entry entry = ENTRIES.get(id);
        ItemStack stack = new ItemStack(Items.PAPER);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(entry == null ? "Torn Page" : entry.title())
                .withStyle(s -> s.withItalic(false).withColor(ChatFormatting.YELLOW)));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("Someone was here before you.").withStyle(ChatFormatting.GRAY),
                Component.literal("Right-click to read.").withStyle(ChatFormatting.DARK_GRAY))));
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        CompoundTag tag = new CompoundTag();
        tag.putString(PAGE_TAG, id);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    public static ItemStack journalBook() {
        ItemStack stack = new ItemStack(Items.BOOK);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Journal").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.AQUA)));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("\"I can't keep things written down. Maybe you can.\"").withStyle(ChatFormatting.GRAY),
                Component.literal("Right-click to open.").withStyle(ChatFormatting.DARK_GRAY))));
        CompoundTag tag = new CompoundTag();
        tag.putBoolean(JOURNAL_TAG, true);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    /** Returns the page id carried by a torn page, "journal" for the journal book, or null. */
    public static @Nullable String readTag(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return null;
        CompoundTag tag = data.copyTag();
        if (tag.contains(JOURNAL_TAG)) return "journal";
        return tag.getString(PAGE_TAG).orElse(null);
    }
}
