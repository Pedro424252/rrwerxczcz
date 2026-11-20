package com.example.aesirpara.mixin;

import com.example.aesirpara.AesirPara;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {
    // Oyuncunun kafasinin ustundeki isim etiketine para eklenir.
    // require = 0: hedef metot bu surumde bulunamazsa oyun cokmez, sadece para gorunmez.
    @Inject(method = "getDisplayName(Lnet/minecraft/entity/Entity;)Lnet/minecraft/text/Text;",
            at = @At("RETURN"), cancellable = true, require = 0)
    private void aesirpara$name(Entity entity, CallbackInfoReturnable<Text> cir) {
        Text t = AesirPara.decorate(entity, cir.getReturnValue());
        if (t != null) cir.setReturnValue(t);
    }
}
