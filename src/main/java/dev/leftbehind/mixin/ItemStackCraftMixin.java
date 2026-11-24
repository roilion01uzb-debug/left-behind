package dev.leftbehind.mixin;

import dev.leftbehind.story.Observation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tells Steve when a player makes something (crafting table, furnace, stonecutter, smithing), so he can learn it. */
@Mixin(ItemStack.class)
abstract class ItemStackCraftMixin {
    @Inject(method = "onCraftedBy", at = @At("TAIL"))
    private void leftbehind$crafted(Player player, int craftCount, CallbackInfo ci) {
        if (player instanceof ServerPlayer serverPlayer && !(player.containerMenu instanceof MerchantMenu)) {
            Observation.crafted(serverPlayer, (ItemStack) (Object) this, craftCount);
        }
    }
}
