package com.thegameoflife;

import net.fabricmc.api.ModInitializer;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class TheGameOfLIfeMod implements ModInitializer {
	public static final String MOD_ID = "thegameoflife";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {


		LOGGER.info("Hello Fabric world!");

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(Commands.literal("test_command").executes(context -> {
				context.getSource().sendSuccess(() -> Component.literal("Called /test_command."), false);
				LOGGER.info("Command triggered");

				// dispatcher из CommandRegistrationCallback
				// Выполняем команду /say Hello через Brigadier
				dispatcher.execute("tick freeze", context.getSource());


				return 1;
			}));
		});


	}
}

