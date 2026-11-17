package dev.leftbehind.talk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** Reading what players type: slang, contractions, names, yes and no, answers and definitions. */
final class Words {
    private Words() {}

    private static final Map<String, String> SLANG = new HashMap<>();

    static {
        String[][] pairs = {
                {"u", "you"}, {"yu", "you"}, {"r", "are"}, {"ur", "your"}, {"y", "why"}, {"wat", "what"}, {"wut", "what"},
                {"whats", "what is"}, {"wats", "what is"}, {"whos", "who is"}, {"wheres", "where is"}, {"hows", "how is"}, {"thats", "that is"},
                {"its", "it is"}, {"im", "i am"}, {"ive", "i have"}, {"youre", "you are"}, {"youve", "you have"}, {"theyre", "they are"},
                {"theres", "there is"}, {"hes", "he is"}, {"shes", "she is"}, {"isnt", "is not"}, {"arent", "are not"}, {"wasnt", "was not"},
                {"dont", "do not"}, {"doesnt", "does not"}, {"didnt", "did not"}, {"cant", "can not"}, {"cannot", "can not"}, {"wont", "will not"},
                {"wouldnt", "would not"}, {"couldnt", "could not"}, {"shouldnt", "should not"}, {"havent", "have not"}, {"hasnt", "has not"},
                {"lets", "let us"}, {"wanna", "want to"}, {"gonna", "going to"}, {"gotta", "got to"}, {"gimme", "give me"}, {"lemme", "let me"},
                {"kinda", "kind of"}, {"sorta", "sort of"}, {"dunno", "do not know"}, {"idk", "i do not know"}, {"ikr", "yes"},
                {"pls", "please"}, {"plz", "please"}, {"thx", "thanks"}, {"ty", "thanks"}, {"tysm", "thanks"}, {"thanx", "thanks"},
                {"ok", "okay"}, {"k", "okay"}, {"kk", "okay"}, {"okey", "okay"}, {"oki", "okay"}, {"okie", "okay"},
                {"yeah", "yes"}, {"yea", "yes"}, {"yep", "yes"}, {"yup", "yes"}, {"ye", "yes"}, {"yah", "yes"}, {"yas", "yes"}, {"ya", "yes"},
                {"nah", "no"}, {"nope", "no"}, {"naw", "no"}, {"hru", "how are you"}, {"wyd", "what are you doing"}, {"wbu", "what about you"},
                {"hbu", "what about you"}, {"lol", "haha"}, {"lmao", "haha"}, {"lmfao", "haha"}, {"rofl", "haha"}, {"xd", "haha"}, {"hahaha", "haha"},
                {"omg", "oh my god"}, {"bc", "because"}, {"cuz", "because"}, {"coz", "because"}, {"abt", "about"}, {"rn", "right now"},
                {"tmrw", "tomorrow"}, {"gn", "good night"}, {"gm", "good morning"}, {"sup", "what is up"}, {"wassup", "what is up"},
                {"hii", "hi"}, {"hiii", "hi"}, {"heyy", "hey"}, {"helo", "hello"}, {"hellp", "help"}, {"favourite", "favorite"}, {"fav", "favorite"},
                {"luv", "love"}, {"bby", "baby"}, {"msg", "message"}, {"bro", "bro"}, {"steev", "steve"}, {"stev", "steve"},
        };
        for (String[] pair : pairs) SLANG.put(pair[0], pair[1]);
    }

    /** Lower case, no punctuation, slang expanded, single spaces, padded with a space on each side. */
    static String normalize(String raw) {
        String s = raw.toLowerCase(Locale.ROOT).replace('’', '\'').replace("'", "");
        s = s.replaceAll("[^a-z0-9_ ]", " ").replaceAll("([a-z])\\1{2,}", "$1");
        StringBuilder out = new StringBuilder(" ");
        for (String token : s.trim().split(" +")) {
            if (token.isEmpty()) continue;
            out.append(SLANG.getOrDefault(token, token)).append(' ');
        }
        return out.toString();
    }

    static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        for (String t : text.trim().split(" +")) if (!t.isEmpty()) out.add(t);
        return out;
    }

    static int count(String text) {
        return tokens(text).size();
    }

    static String singular(String word) {
        if (word.length() > 4 && word.endsWith("ies")) return word.substring(0, word.length() - 3) + "y";
        if (word.length() > 4 && (word.endsWith("ches") || word.endsWith("shes") || word.endsWith("xes"))) return word.substring(0, word.length() - 2);
        if (word.length() > 3 && word.endsWith("s") && !word.endsWith("ss") && !word.endsWith("us")) return word.substring(0, word.length() - 1);
        return word;
    }

    private static final Set<String> QUESTION_WORDS = Set.of("what", "why", "how", "who", "where", "when", "which", "do", "does", "did",
            "are", "is", "can", "could", "would", "will", "should", "have", "has", "was", "were");

    static boolean startsLikeQuestion(String text) {
        List<String> t = tokens(text);
        return !t.isEmpty() && QUESTION_WORDS.contains(t.get(0));
    }

    /** 1 for yes, 0 for no, -1 if it's neither. */
    static int yesNo(String text) {
        for (String no : new String[]{" no ", " not ", " never ", " do not ", " nothing ", " no way "}) if (text.contains(no)) return 0;
        for (String yes : new String[]{" yes ", " sure ", " of course ", " definitely ", " always ", " absolutely ", " i do ", " i am ",
                " i have ", " i did ", " kind of ", " a little ", " sometimes ", " probably ", " totally ", " obviously ", " okay ", " right ",
                " true ", " i think so ", " yes please ", " ofc ", " a lot ", " very "}) {
            if (text.contains(yes)) return 1;
        }
        return -1;
    }

    private static final Pattern NAME_ALWAYS = Pattern.compile(
            " (?:my name is|my names|name is|call me|i am called|they call me|people call me|you can call me|my name) ([a-z0-9_]+) ");
    private static final Pattern NAME_ASKED = Pattern.compile(" (?:i am|it is|this is|i go by) ([a-z0-9_]+) ");
    private static final Set<String> NOT_NAMES = Set.of("yes", "no", "okay", "sure", "steve", "herobrine", "nothing", "why", "what",
            "who", "nobody", "secret", "hi", "hello", "hey", "you", "me", "not", "the", "a", "an", "fine", "good", "here", "back", "sorry",
            "just", "ok", "idk", "haha", "lol", "player", "maybe", "your", "my", "it", "is");

    /**
     * A name, if the message gives one. "My name is ..." always counts. When he has just asked,
     * "I'm ...", "it's ..." or a single word count too. Keeps the capital letters the player used.
     */
    static @Nullable String nameStatement(String text, String raw, boolean asked) {
        String candidate = null;
        Matcher m = NAME_ALWAYS.matcher(text);
        if (m.find()) candidate = m.group(1);
        if (candidate == null && asked) {
            m = NAME_ASKED.matcher(text);
            if (m.find()) candidate = m.group(1);
            else if (count(text) <= 2) {
                List<String> t = tokens(text);
                candidate = t.get(t.size() - 1);
            }
        }
        if (candidate == null && !asked) {
            // "I'm Temur" with a capital letter, but not "I'm tired".
            Matcher im = Pattern.compile("(?i)\\bI(?:'| a)?m ([A-Z][A-Za-z0-9_]{1,15})\\b").matcher(raw);
            if (im.find() && !COMMON.contains(im.group(1).toLowerCase(Locale.ROOT))) candidate = im.group(1).toLowerCase(Locale.ROOT);
        }
        if (candidate == null || NOT_NAMES.contains(candidate) || candidate.length() < 2 || candidate.length() > 16) return null;
        if (!asked && COMMON.contains(candidate)) return null;
        // Use the player's own spelling.
        for (String token : raw.split("[^A-Za-z0-9_]+")) {
            if (token.equalsIgnoreCase(candidate)) {
                return token.equals(token.toLowerCase(Locale.ROOT)) ? Character.toUpperCase(token.charAt(0)) + token.substring(1) : token;
            }
        }
        return Character.toUpperCase(candidate.charAt(0)) + candidate.substring(1);
    }

    private static final String[] ANSWER_FILLERS = {"well ", "um ", "uh ", "hmm ", "honestly ", "i think ", "i guess ", "probably ", "maybe ",
            "definitely ", "i would say ", "i would keep ", "i would ", "id say ", "id keep ", "i'd say ", "i'd keep ", "i'd ",
            "my favorite is ", "my favourite is ", "my favorite ", "my favourite ", "it is ", "it's ", "its ", "i like ", "i love ",
            "i am building ", "i'm building ", "im building ", "building ", "i want to build ", "i'm making ", "i am making ", "im making ",
            "making ", "i come from ", "i'm from ", "i am from ", "im from ", "from ", "because ", "cause ", "cuz ", "so ", "just "};
    private static final String[] ANSWER_CUTS = {" with ", " for ", " because ", " and ", " but ", " so ", " near ", " which ", " that "};

    /** The heart of a free answer: "I think probably a castle with my friend!" becomes "a castle". */
    static String answerFrom(String raw) {
        String s = raw.trim();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String filler : ANSWER_FILLERS) {
                if (s.toLowerCase(Locale.ROOT).startsWith(filler)) {
                    s = s.substring(filler.length()).trim();
                    changed = true;
                }
            }
        }
        String lower = s.toLowerCase(Locale.ROOT);
        for (String cut : ANSWER_CUTS) {
            int at = lower.indexOf(cut);
            if (at > 0 && lower.substring(0, at).split(" ").length >= 2) {
                s = s.substring(0, at);
                lower = lower.substring(0, at);
            }
        }
        s = s.replaceAll("[.!?,;:]+$", "").trim();
        String[] parts = s.split(" +");
        if (parts.length > 6) s = String.join(" ", java.util.Arrays.copyOfRange(parts, 0, 6));
        return s;
    }

    /** "It's a green mob that blows up" becomes "a green mob that blows up". */
    static String definitionFrom(String raw, String word) {
        String s = raw.trim();
        String[] leads = {"a " + word + " is ", "an " + word + " is ", word + " is ", word + "s are ", "the " + word + " is ", "they are ",
                "theyre ", "they're ", "it is ", "its ", "it's ", "thats ", "that's ", "that is ", "basically ", "like ", "um ", "well ", "so "};
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String lead : leads) {
                if (s.toLowerCase(Locale.ROOT).startsWith(lead)) {
                    s = s.substring(lead.length()).trim();
                    changed = true;
                }
            }
        }
        s = s.replaceAll("[.!?,;:]+$", "").trim();
        String[] parts = s.split(" +");
        if (parts.length > 10) s = String.join(" ", java.util.Arrays.copyOfRange(parts, 0, 10));
        if (s.length() > 1 && Character.isUpperCase(s.charAt(0)) && Character.isLowerCase(s.charAt(1))) {
            s = Character.toLowerCase(s.charAt(0)) + s.substring(1);
        }
        return s;
    }

    /** Plain English and everyday Minecraft words. He never asks what these mean. */
    static final Set<String> COMMON = Set.copyOf(java.util.Arrays.asList((
            "a about above after again against all almost alone along already also although always am among an and another any anybody anyone "
            + "anything anyway anywhere are around as ask asked asking at away back bad be became because become been before behind being below "
            + "best better between big bit both bring brought but buy by call called came can cant care carry case cause change close come comes "
            + "coming could day days did do does doing done dont down during each early easy either else end enough even ever every everybody "
            + "everyone everything everywhere eye eyes fact far feel feeling feels felt few find fine first for forget forgot found friend friends from "
            + "front full fun gave get gets getting give given giving go goes going gone good got great group guess guy guys had half hand hands "
            + "happen happened happy hard has have having he head hear heard her here hers herself high him himself his hold home hope hour house how "
            + "however idea if important in inside instead into is it its itself job just keep kind knew know known last late later least left less "
            + "let life like liked likes line little live lived lives living long look looked looking looks lot lots love made make makes making man many "
            + "may maybe me mean means men might mind mine minute more morning most much must my myself name near need needs never new next nice night "
            + "nobody none nothing now number of off often oh okay old on once one only open or other others our out outside over own part people "
            + "person place play played player players playing please point pretty probably put quite rather read real really reason right room run said "
            + "same saw say saying says second see seem seen self set shall she should show side since small so some somebody someone something "
            + "sometimes somewhere soon sorry start started still stop story stuff such sure take taken talk talking tell than thank thanks that the "
            + "their them then there these they thing things think thinking this those though thought through time times to today together told "
            + "tomorrow too took top toward tried true try trying turn two under until up upon us use used using very wait want wanted wants was watch "
            + "way we well went were what whatever when where whether which while who whole whom whose why will wish with within without wonder word "
            + "words work world would wow yeah year years yes yesterday yet you young your yours yourself "
            + "anymore everyday kinda gonna wanna hello hey hi bye yo bro dude mate lol haha okay ok please thanks maybe really actually literally "
            + "basically seriously definitely totally exactly honestly game games minecraft server mod mods build building built mine mining base "
            + "block blocks ore ores item items stack stacks slab slabs stairs stair fence gate wall walls button plate pressure carpet pane ingot "
            + "nugget helmet chestplate leggings boots armor armour spawn egg eggs frame banner pattern smithing template music disc bucket potion "
            + "arrow arrows boat minecart rail rails redstone wool sign signs door doors trapdoor ladder stone stones wood wooden plank planks log logs "
            + "leaves leaf glass sand dirt grass water lava fire light dark tree trees flower flowers seed seeds farm crop crops wheat food meat "
            + "bread apple stick sticks string bone bones paper book books map compass clock chest bed beds torch torches table furnace crafting "
            + "craft crafted tool tools sword pickaxe pick axe shovel hoe bow shield sheep cow pig chicken horse dog cat wolf fish mob mobs monster "
            + "monsters zombie zombies skeleton skeletons spider spiders creeper creepers villager villagers village iron gold diamond coal emerald "
            + "black white red green blue yellow orange purple pink brown gray grey cyan lime magenta light polished smooth cracked mossy chiseled "
            + "stripped cut waxed raw cooked dead small large tall short hanging wall powered sticky big tiny huge alive kill killed killing die died "
            + "dying dead death attack attacked fight hit hurt help save safe danger dangerous scary scared afraid weird strange creepy cool nice "
            + "awesome amazing beautiful cute funny happy sad angry mad tired hungry bored lonely ready done busy free lost stuck sleep sleeping "
            + "slept walk walking run running jump jumping swim swimming fly flying follow following stay staying wait waiting leave leaving left "
            + "right north south east west up down here there home house castle tower bridge hole cave caves mountain mountains river ocean sea "
            + "lake beach forest desert jungle snow ice rain sun moon sky cloud clouds star stars weather night day evening afternoon week month"
    ).split(" ")));
}
