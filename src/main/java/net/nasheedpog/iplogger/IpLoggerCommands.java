package net.nasheedpog.iplogger;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.CommandSource;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Style;
import net.minecraft.util.Formatting;

import java.util.*;
import java.util.stream.Collectors;

public class IpLoggerCommands {

    public static void registerCommands(IpLogger modInstance, PlayerDatabase database) {
        System.out.println("[IpLogger] Registering commands...");
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> register(dispatcher, database));
    }

    private static void register(CommandDispatcher<ServerCommandSource> dispatcher, PlayerDatabase database) {
        dispatcher.register(CommandManager.literal("iplogger")
                .requires(source -> source.hasPermissionLevel(4)) // Only admins can use these commands
                .then(CommandManager.literal("getIPs")
                        .then(CommandManager.argument("username", StringArgumentType.word())
                                .suggests((context, builder) -> CommandSource.suggestMatching(database.getUsernames(), builder))
                                .executes(context -> getIPsCommand(context, database))
                        )
                )
                .then(CommandManager.literal("getDuplicateIPs")
                        .executes(context -> getDuplicateIPsCommand(context, database))
                )
                .then(CommandManager.literal("getUsers")
                        .then(CommandManager.argument("ipAddress", StringArgumentType.word())
                                .suggests((context, builder) -> CommandSource.suggestMatching(database.getAllIPs(), builder))
                                .executes(context -> getUsersCommand(context, database))
                        )
                )
                .then(CommandManager.literal("removeIpFromUser")
                        .then(CommandManager.argument("username", StringArgumentType.word())
                                .suggests((context, builder) -> CommandSource.suggestMatching(database.getUsernames(), builder))
                                .then(CommandManager.argument("ipAddress", StringArgumentType.word())
                                        .suggests((context, builder) -> CommandSource.suggestMatching(database.getIpAddressesForUser(StringArgumentType.getString(context, "username")).keySet(), builder))
                                        .executes(context -> removeIpFromUserCommand(context, database))
                                )
                        )
                )
        );
    }

    private static int getIPsCommand(CommandContext<ServerCommandSource> context, PlayerDatabase database) {
        String username = StringArgumentType.getString(context, "username");
        Map<String, String> ipTimestamps = database.getIpAddressesForUser(username);

        if (ipTimestamps == null) {
            context.getSource().sendFeedback(() -> Text.literal("[IpLogger] User not found.")
                    .setStyle(Style.EMPTY.withColor(Formatting.AQUA)), false);
            return 1;
        }

        context.getSource().sendFeedback(() -> Text.literal("[IpLogger] IP addresses first seen for ")
                .setStyle(Style.EMPTY.withColor(Formatting.AQUA))
                .append(Text.literal(username).setStyle(Style.EMPTY.withColor(Formatting.YELLOW))), false);
        ipTimestamps.forEach((ip, timestamp) -> {
            context.getSource().sendFeedback(() -> Text.literal("- ")
                    .append(Text.literal(ip).setStyle(Style.EMPTY.withColor(Formatting.BLUE)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, ip))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Click to copy IP")))))
                    .append(Text.literal(" (First seen: " + timestamp + ")")), false);
        });
        return 1;
    }

    private static int getDuplicateIPsCommand(CommandContext<ServerCommandSource> context, PlayerDatabase database) {
        Map<String, List<String>> duplicateIps = database.getDuplicateIPs();

        if (duplicateIps.isEmpty()) {
            context.getSource().sendFeedback(() -> Text.literal("[IpLogger] No duplicate IPs found.")
                    .setStyle(Style.EMPTY.withColor(Formatting.AQUA)), false);
            return 1;
        }

        context.getSource().sendFeedback(() -> Text.literal("[IpLogger] Duplicate IPs:")
                .setStyle(Style.EMPTY.withColor(Formatting.AQUA)), false);

        duplicateIps.forEach((ip, users) -> {
            Text ipText = Text.literal("- ").append(Text.literal(ip)
                    .setStyle(Style.EMPTY.withColor(Formatting.BLUE)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, ip))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Click to copy IP")))));

            List<Text> userTextComponents = new ArrayList<>();
            for (String user : users) {
                Text userText = Text.literal(user)
                        .setStyle(Style.EMPTY.withColor(Formatting.YELLOW)
                                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, user))
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("First seen: " + database.getTimestampForUserIp(user, ip)))));
                userTextComponents.add(userText);
                userTextComponents.add(Text.literal(", "));
            }

            if (!userTextComponents.isEmpty()) {
                userTextComponents.remove(userTextComponents.size() - 1);
            }

            Text combinedUsersText = Text.empty();
            for (Text userComponent : userTextComponents) {
                combinedUsersText = combinedUsersText.copy().append(userComponent);
            }

            Text combinedText = ipText.copy().append(combinedUsersText);
            context.getSource().sendFeedback(() -> combinedText, false);
        });
        return 1;
    }

    private static int getUsersCommand(CommandContext<ServerCommandSource> context, PlayerDatabase database) {
        String ipAddress = StringArgumentType.getString(context, "ipAddress");
        List<String> users = database.getUsersForIp(ipAddress);

        if (users.isEmpty()) {
            context.getSource().sendFeedback(() -> Text.literal("[IpLogger] No users found for IP: ")
                    .setStyle(Style.EMPTY.withColor(Formatting.AQUA))
                    .append(Text.literal(ipAddress).setStyle(Style.EMPTY.withColor(Formatting.BLUE))), false);
            return 1;
        }

        context.getSource().sendFeedback(() -> Text.literal("[IpLogger] Users for IP ")
                .setStyle(Style.EMPTY.withColor(Formatting.AQUA))
                .append(Text.literal(ipAddress).setStyle(Style.EMPTY.withColor(Formatting.BLUE)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, ipAddress))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Click to copy IP"))))), false);

        users.forEach(user -> {
            String timestamp = database.getTimestampForUserIp(user, ipAddress);
            Text userText = Text.literal(user)
                    .setStyle(Style.EMPTY.withColor(Formatting.YELLOW)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, user))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Click to copy username"))));

            context.getSource().sendFeedback(() -> Text.literal("- ")
                    .append(userText)
                    .append(Text.literal(" (First seen: " + timestamp + ")")), false);
        });
        return 1;
    }


    private static int removeIpFromUserCommand(CommandContext<ServerCommandSource> context, PlayerDatabase database) {
        String username = StringArgumentType.getString(context, "username");
        String ipAddress = StringArgumentType.getString(context, "ipAddress");

        boolean removed = database.removeIpFromUser(username, ipAddress);
        if (removed) {
            context.getSource().sendFeedback(() -> Text.literal("[IpLogger] Removed IP ")
                    .setStyle(Style.EMPTY.withColor(Formatting.AQUA))
                    .append(Text.literal(ipAddress).setStyle(Style.EMPTY.withColor(Formatting.BLUE)))
                    .append(Text.literal(" from user "))
                    .append(Text.literal(username).setStyle(Style.EMPTY.withColor(Formatting.YELLOW))), false);
        } else {
            context.getSource().sendFeedback(() -> Text.literal("[IpLogger] IP not found or not associated with user.")
                    .setStyle(Style.EMPTY.withColor(Formatting.AQUA)), false);
        }
        return 1;
    }
}
