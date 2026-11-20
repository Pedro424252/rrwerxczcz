package com.example.aesirpara;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public class AesirParaScreen extends Screen {
    public AesirParaScreen() {
        super(Text.literal("Aesir Para"));
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = this.height / 2 - 30;

        this.addDrawableChild(ButtonWidget.builder(label(), b -> {
            AesirPara.enabled = !AesirPara.enabled;
            AesirPara.save();
            b.setMessage(label());
        }).dimensions(cx - 100, y, 200, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Kapat"), b -> this.close())
                .dimensions(cx - 100, y + 30, 200, 20).build());
    }

    private Text label() {
        return Text.literal("Aesir Para: " + (AesirPara.enabled ? "ACIK" : "KAPALI"));
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, this.height / 2 - 55, 0xFFFFFFFF);
        ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("Para gelmiyorsa sohbete yaz: /aesirpara debug"),
                this.width / 2, this.height / 2 + 40, 0xFFAAAAAA);
        ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("Gaks_v310 Taraf\u0131ndan Yap\u0131lm\u0131\u015Ft\u0131r"),
                this.width / 2, this.height / 2 + 60, 0xFFFFAA00);
    }
}
