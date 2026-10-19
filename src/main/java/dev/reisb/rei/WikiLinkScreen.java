package dev.reisb.rei;

import java.net.URI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.Util;

// REI excludes screens in .rei. from its recipe return history.
public final class WikiLinkScreen extends ConfirmLinkScreen {
    public WikiLinkScreen(Screen parent, URI link) {
        super(confirmed -> {
            try {
                if (confirmed) Util.getPlatform().openUri(link);
            } finally {
                Minecraft.getInstance().gui.setScreen(parent);
            }
        }, link.toString(), true);
    }
}
