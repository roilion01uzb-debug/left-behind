package dev.leftbehind.client.render;

import java.util.List;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * What the renderer knows about Steve, Herobrine or one of the removed ones for one frame.
 * Deliberately not the game's own player render state: the game hands anything that uses that one
 * to the real player renderer, which draws it with the default skin and ignores everything here.
 */
public class PersonRenderState extends HumanoidRenderState {
    /** Steve only: one of the OriginalSteve.STATE_ values. */
    public int pose;
    /** Herobrine only: wearing Steve's face, so no white eyes. */
    public boolean masked;
    /** Ghosts only: which of the removed ones this is. */
    public int variant;
    /** Ghosts only: 0 while present, rising to 1 as it fades out. */
    public float fade;
    /** What they are saying right now, one row per line, or null. */
    public @Nullable List<Component> speech;
    public @Nullable Vec3 speechAttachment;
}
