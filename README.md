# Left Behind

A Minecraft horror mod for **Fabric, Minecraft 26.3**.

Original Steve is always around. He isn't a monster. He watches you, learns from you and remembers you.
Herobrine is always one step ahead of both of you, and he knows exactly which memories to wake up.

## What's in this first build

- **Original Steve**: a persistent companion who shows up about 2 minutes into a world and follows whoever he trusts most.
- **Persistent memory**: Steve's memory is saved with the world. That covers trust and anger per player, the habits he has learned, his memory stability and the number of breaches.
- **Learning**: if he watches you chop trees enough times, he starts doing it himself. Killing mobs impresses him at first. After his first memory breach, the same act starts to hurt him.
- **Two separate meters**:
  - *Memory stability* is damaged by Herobrine. When it's low, Steve sees things that aren't there.
  - *Anger* is personal and per player. It goes up when you hurt him and down when you feed him. It escalates through resentment (keeps his distance), provocation (takes your torches), retaliation (breaks your door), territorial ("This is my house.") and escalation (takes your bed: *"You don't live here anymore."*).
- **Herobrine**: appears near Steve, mostly at night. If Steve makes eye contact with him, a **memory breach** starts a **lost night**. Steve vanishes and goes to the home of the player who trusts him most. Lights go out, doors and windows break and crops get torn up. In the morning Steve is back, cheerful, with no memory of it. His axe is lying at the scene.
  - If *you* look straight at Herobrine, he disappears.
- Steve can be killed, and his death is permanent (for now, `/leftbehind summon` brings him back).
- All destruction respects the `mob_griefing` game rule.
- Dialogue appears in chat for now. Each line is a placeholder for a future voiceover.

## Testing commands (operators)

| Command | What it does |
|---|---|
| `/leftbehind status` | Shows Steve's memory, meters and what he did last night |
| `/leftbehind summon` | Brings Steve to you (and revives him) |
| `/leftbehind herobrine` | Makes Herobrine appear near Steve |
| `/leftbehind breach` | Triggers a memory breach right away |
| `/leftbehind anger <player> <0-100>` | Sets Steve's anger toward a player |
| `/leftbehind stability <0-100>` | Sets Steve's memory stability |
| `/leftbehind reset` | Makes Steve forget everything |

## Building

You need JDK 25.

```
./gradlew build
```

The mod jar ends up in `build/libs/left-behind-<version>.jar`. Put it in your `mods` folder along with Fabric API.
