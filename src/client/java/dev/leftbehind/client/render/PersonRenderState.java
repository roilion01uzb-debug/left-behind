package dev.leftbehind.client.render;

import net.minecraft.client.renderer.entity.state.HumanoidRenderState;

/**
 * What the renderer knows about Steve, Herobrine or one of the removed ones for one frame.
 * Deliberately not the game's own player render state: the game hands anything that uses that one
 * to the real player renderer, which draws it with the default skin and ignores everything here.
 */
public class PersonRenderState extends HumanoidRenderState {
    /** Steve only: one of the OriginalSteve.STATE_ values. */
    public int pose;
    /** Ghosts only: which of the removed ones this is. */
    public int variant;
    /** Ghosts only: 0 while present, rising to 1 as it fades out. */
    public float fade;
}
