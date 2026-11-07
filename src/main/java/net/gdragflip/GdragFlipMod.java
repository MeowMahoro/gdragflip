package net.gdragflip;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.gdragflip.auto.AutoScanner;
import net.gdragflip.command.GdragCommand;
import net.gdragflip.config.GdragConfig;
import net.gdragflip.cofl.ExpCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GdragFlipMod implements ClientModInitializer {
    public static final String MOD_ID = "gdragflip";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        GdragConfig.load();
        ExpCache.load();
        ClientCommandRegistrationCallback.EVENT.register(GdragCommand::register);
        AutoScanner.register();
        LOGGER.info("Golden Dragon Flip loaded");
    }
}
