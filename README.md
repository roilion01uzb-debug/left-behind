# Left Behind

A Minecraft horror mod for **Fabric, Minecraft 26.3**. This is *Left Behind 1*.

Original Steve is always around. He isn't a monster. He watches you, learns from you, talks to you and remembers you.
Herobrine is always one step ahead of both of you, and he knows exactly which memories to wake up.

## Download

`downloads/left-behind-0.4.0.jar`. Put it in your `mods` folder together with Fabric API.

## What's in it

### A story that moves forward

The world goes through four acts, each announced with a title card: **The One Who Stayed**, **Familiar Things**,
**The Observer** and **What Is Left**. The story moves on when you have spent time with Steve, talked to him,
helped him remember and lived through his memory breaches. It doesn't move on by itself just because days pass.

- **Steve's Journal**: Steve hands you a book the first time you talk. Use it (or `/steve journal`) to read everything
  the world has given up so far: 18 memories, 8 torn pages and a set of survival notes.
- **Torn pages**: pieces of an old diary. They are found inside relics, dropped by ghosts and left where Herobrine stood.
- **Relics**: things nobody built start appearing near you. A tree with no leaves and a sign. A starting house with a chest.
  A brick pyramid. Something at 0, 0. Bring Steve near one and he remembers.
- **25 advancements** in their own "Left Behind" tab track all of it.

### A Steve who talks (new in 0.3)

Steve speaks English, and you talk to him the way you talk to anyone: **type in chat**. No menus, no buttons.

- **You can hear him (new in 0.4).** Around 700 of his lines are recorded with a natural voice and real emotion:
  calm, warm, curious, sad, hushed at night, scared, panicking and angry. He takes a breath before he says something
  frightening and sighs before something sad. The voice comes from where he stands, and his words type out at the
  pace he says them. Herobrine whispers in your head, in Steve's own voice gone wrong.
  The volume is the game's **Voice/Speech** slider. Lines built from what you tell him (your answers, words you teach him)
  are text only, and the recordings never say your name.

- **His words appear above his head**, typed out as he says them, and as a **subtitle just above your hotbar**
  when he is behind you or too far to read. Herobrine's whispers show up as grey subtitles.
- **Just type near him.** Within about 10 blocks he hears you if he can see you; once you are talking, 20 blocks is fine;
  say "Steve" and he hears you from 40. Slang and typos are fine ("u ok?", "wru doing", "helloooo").
- **He starts conversations himself.** He says hello when you come back, asks your name the first time you meet,
  and later asks you things: what you are afraid of, what you like to build, whether you dream, why you stay.
  He **remembers your answers** and brings them up again days later.
- **He learns words.** Mention something he has never heard of ("have you seen netherite?") and he asks what it is.
  Tell him, and from then on he knows it and remembers who taught him.
- **Ask him anything**: who he is, if he is real, about "him", about "the others", about this place, how he feels,
  what he remembers, what he thinks of you. Ask for a joke or a story. Over 60 subjects, and his answers change as the
  acts move on and as he trusts you more.
- **Ask him to do things**: "follow me", "stay here", "go where you want". He only follows people he trusts.
- **Hold something and right-click him** and he reacts to it: food, tools, flowers, music discs, a compass, bricks,
  a bed, TNT and about thirty other things. Gifts raise his trust. Right-clicking him with an empty hand gets his attention.
- **He comments on what you do**: mining, building, fighting, dying, sleeping, sneaking behind him, standing still too long,
  being on fire, the weather, the time of day. He keeps quiet about it while you are talking.
- Insult him and he remembers. Tell him to shut up and he will, for a while. Some things only "sorry" fixes.
- He still learns from you (chop enough trees and he starts chopping), still gets angry in stages if you hurt him,
  and defends players he trusts from monsters.
- Players without the mod on a server still get his words in chat.

### Breaches you can watch

When Herobrine holds Steve's eyes, a memory breaks. Steve no longer disappears.

1. He **runs**, a long way, faster than you can sprint.
2. He stops about fifty blocks off and **falls apart where you can see it**: he sits with his hands over his head,
   paces, stares at something that isn't there, hits at nothing, walls himself in with dirt.
3. If you walk up to him and he doesn't trust you, he shouts and runs again. If he trusts you (60 or more) and you walk
   up slowly and stay next to him, he calms down. That is one of the most important memories in the journal.
4. After enough breaches he doesn't calm down at night. He goes to the home of the player he trusts most
   and takes it apart. In the morning he remembers nothing.

### Herobrine

- He has the **original Herobrine look**: Steve's own skin with blank white eyes that glow in the dark.
  (In 0.1 the game was drawing him as a plain player. That is fixed.)
- **He senses you.** Looking at him, standing near him and running near him all fill a hidden meter.
  Sneaking halves it. Your screen darkens at the edges and your heart gets louder as it fills.
- In the first two acts he only watches, and leaves when you notice him too much.
- From Act III, when the meter is full, **he hunts you**. He walks at first. Then he doesn't. He will follow you until you are
  dead, through walls if he has to. The death message is "was removed".
- The only way out is Steve: if a Steve who trusts you is close by, he steps in front of you. It costs him a breach.
- You can right-click Herobrine and type **one** question in chat. Don't ask a second one. Don't hit him.

### The removed ones

Three figures from a much older version of the world: **Rana**, **Black Steve** and **Beast Boy**.
They are see-through, they never move their legs, and they fade when you get close. They never hurt you.
Sometimes they leave something behind.

### Effects and sound

- Screen effects: dread vignette with a heartbeat pulse, static, a flash of white eyes, blackouts, camera shake.
- An ambience director that gets worse with each act: low drones, howling wind, knocking behind you when you are indoors,
  footsteps in the grass, cave sounds, and later whispers and breathing right behind your shoulder, bursts of static and eyes in the dark.
- 25 original sounds, all with subtitles. They were made for this mod with `tools/make_sounds.py`,
  so there is nothing borrowed in the jar.

## Settings (operators)

| Command | What it does |
|---|---|
| `/leftbehind settings hunt false` | Herobrine never hunts players |
| `/leftbehind settings creative_kill false` | A hunt can't kill players in creative mode |
| `/leftbehind settings griefing false` | Steve and the story never change blocks |

Block changes also respect the `mob_griefing` game rule.

## Testing commands (operators)

| Command | What it does |
|---|---|
| `/leftbehind status` | Act, Steve's mood and memory, each player's trust, anger and how sensed they are |
| `/leftbehind summon` | Brings Steve to you (and revives him) |
| `/leftbehind act <1-4>` | Jumps to an act |
| `/leftbehind herobrine` | Herobrine appears near Steve |
| `/leftbehind herobrine watch` | Herobrine appears and watches you |
| `/leftbehind herobrine hunt` | Herobrine hunts you right now |
| `/leftbehind breach` | Triggers a memory breach |
| `/leftbehind calm` | Ends Steve's episode |
| `/leftbehind ghost [0-2]` | Spawns one of the removed ones near you |
| `/leftbehind relic <tree\|house\|pyramid\|zero\|pillar>` | Places a relic near you |
| `/leftbehind journal all` | Unlocks every journal entry |
| `/leftbehind trust <player> <0-100>` | Sets Steve's trust in a player |
| `/leftbehind anger <player> <0-100>` | Sets Steve's anger toward a player |
| `/leftbehind sense <player> <0-100>` | Sets how much Herobrine senses a player |
| `/leftbehind stability <0-100>` | Sets Steve's memory stability |
| `/leftbehind reset` | Makes the world forget everything |

Everyone can use `/steve journal`, and `/steve say <text>` talks to Steve without chat (for servers where chat is off).

## Building

You need JDK 25.

```
./gradlew build
```

The mod jar ends up in `build/libs/left-behind-<version>.jar`.

`./gradlew runClientGameTest` starts a real game, walks through the main features and saves screenshots to
`build/run/clientGameTest/screenshots`.

The sounds, skins and data files are generated by the scripts in `tools/` (Python with numpy, scipy, soundfile, Pillow, and ffmpeg).
Steve's voice is recorded by `tools/make_voice.py` with the [Kokoro-82M](https://github.com/hexgrad/kokoro) voice model
(Apache-2.0), run locally; the script explains how to get the model. Run it again after changing what Steve says.

## What comes next

Alex. Cutscenes with voiceovers. The endings (True, Secret, It's all a dream). And the question the last torn page asks:
what was here before the seed?
