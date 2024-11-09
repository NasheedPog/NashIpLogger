package net.nasheedpog.iplogger;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.nasheedpog.iplogger.IpLoggerCommands;

public class IpLogger implements ModInitializer {
	private static final PlayerDatabase playerDatabase = new PlayerDatabase();

	@Override
	public void onInitialize() {
		System.out.println("IpLogger Mod is initializing!");

		// Load player data from JSON
		playerDatabase.loadFromJson();

		// Register commands
		IpLoggerCommands.registerCommands(this, playerDatabase);

		// Register event listener for player joins
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			String username = handler.getPlayer().getName().getString();
			String ipAddress = getIpAddress(handler);
			playerDatabase.trackPlayer(username, ipAddress);
		});

		// Register server stop event to save data
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> playerDatabase.saveToJson());
	}

	// Method to safely extract the IP address
	private String getIpAddress(ServerPlayNetworkHandler handler) {
		String rawAddress = handler.getConnectionAddress().toString();
		// Remove any leading "\" and everything after the ":"
		return rawAddress.replaceFirst("^/", "").split(":")[0];
	}


}
