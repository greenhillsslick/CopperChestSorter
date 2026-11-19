package es.arenamods.copperchestsorter.mixin;

import es.arenamods.copperchestsorter.client.SignFilterSuggestions;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Screen.class)
public abstract class ScreenSignFilterMixin {
    @Shadow public int width;
    @Shadow public int height;

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void copperchestsorter$renderSignFilterChoices(GuiGraphicsExtractor graphics, int mouseX,
                                                            int mouseY, float partialTick, CallbackInfo ci) {
        SignFilterSuggestions.render(this, graphics, width, height, Minecraft.getInstance().font);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void copperchestsorter$selectSignFilter(double mouseX, double mouseY, int button,
                                                     CallbackInfoReturnable<Boolean> cir) {
        if (SignFilterSuggestions.handleClick(this, mouseX, mouseY, button, width, height)) {
            cir.setReturnValue(true);
        }
    }
}
