package net.gdragflip;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.gdragflip.command.GdragCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GdragFlipMod implements ClientModInitializer {
    public static final String MOD_ID = "gdragflip";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register(GdragCommand::register);
        LOGGER.info("Golden Dragon Flip loaded");
    }
}
