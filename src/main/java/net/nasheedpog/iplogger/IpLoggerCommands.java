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
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.*;
import java.nio.file.*;
import java.util.zip.GZIPInputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
                )            .then(CommandManager.literal("buildFromPastLogs")
                        .executes(context -> buildFromPastLogsCommand(context, database))
                )
                .then(CommandManager.literal("geolocate")
                        .then(CommandManager.argument("ipAddress", StringArgumentType.word())
                                .executes(context -> geolocateCommand(context))
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

    private static int buildFromPastLogsCommand(CommandContext<ServerCommandSource> context, PlayerDatabase database) {
        try {
            Path logsPath = Paths.get("logs");

            if (!Files.exists(logsPath)) {
                context.getSource().sendFeedback(() -> Text.literal("[IpLogger] No logs directory found.").setStyle(Style.EMPTY.withColor(Formatting.RED)), false);
                return 1;
            }

            List<Path> logFiles = Files.walk(logsPath)
                    .filter(path -> path.toString().endsWith(".log.gz"))
                    .sorted()
                    .collect(Collectors.toList());

            System.out.println("[IpLogger] Found " + logFiles.size() + " log files to process.");

            for (Path logFile : logFiles) {
                //System.out.println("[IpLogger] Processing file: " + logFile);

                // Extract the date from the filename, e.g., "2024-08-23" from "2024-08-23-1.log.gz"
                String logDate = logFile.getFileName().toString().substring(0, 10);

                try (GZIPInputStream gzipIn = new GZIPInputStream(new FileInputStream(logFile.toFile()));
                     BufferedReader reader = new BufferedReader(new InputStreamReader(gzipIn))) {

                    String line;
                    while ((line = reader.readLine()) != null) {
                        //System.out.println("[IpLogger] Reading line: " + line);
                        processLogLine(line, database, logDate);  // Pass log date to construct full timestamp
                    }

                } catch (IOException e) {
                    System.out.println("[IpLogger] Error reading log file: " + logFile);
                    e.printStackTrace();
                }
            }

            database.saveToJson();
            context.getSource().sendFeedback(() -> Text.literal("[IpLogger] Completed building data from past logs.").setStyle(Style.EMPTY.withColor(Formatting.AQUA)), false);
            System.out.println("[IpLogger] JSON database updated and saved.");

        } catch (IOException e) {
            context.getSource().sendFeedback(() -> Text.literal("[IpLogger] Error accessing logs directory.").setStyle(Style.EMPTY.withColor(Formatting.RED)), false);
            e.printStackTrace();
        }

        return 1;
    }


    private static void processLogLine(String line, PlayerDatabase database, String logDate) {
        // Update the regex to match the new log format
        String loginPattern = "\\[(?<time>\\d{2}:\\d{2}:\\d{2})\\] \\[Server thread/INFO\\]: (?<username>\\S+)\\[/((?<ipAddress>\\d+\\.\\d+\\.\\d+\\.\\d+):\\d+)\\] logged in with entity id";
        Pattern pattern = Pattern.compile(loginPattern);
        Matcher matcher = pattern.matcher(line);

        if (matcher.find()) {
            // Construct the full timestamp from log date and extracted time
            String time = matcher.group("time");
            String timestampStr = logDate + " " + time;
            String username = matcher.group("username");
            String ipAddress = matcher.group("ipAddress");

            //System.out.println("[IpLogger] Found login entry - Username: " + username + ", IP: " + ipAddress + ", Timestamp: " + timestampStr);

            // Check for duplicates and only add if it's a new or earlier occurrence
            String existingTimestamp = database.getTimestampForUserIp(username, ipAddress);
            if (existingTimestamp == null || LocalDateTime.parse(existingTimestamp, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).isAfter(LocalDateTime.parse(timestampStr, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))) {
                database.addOrUpdateIpEntry(username, ipAddress, timestampStr);
                //System.out.println("[IpLogger] Updated entry for " + username + " with IP " + ipAddress + " at " + timestampStr);
            } else {
                //System.out.println("[IpLogger] Skipped duplicate or later entry for " + username + " with IP " + ipAddress);
            }
        }
    }

    private static int geolocateCommand(CommandContext<ServerCommandSource> context) {
        String ipAddress = StringArgumentType.getString(context, "ipAddress");

        try {
            // Query the new IP location API
            String apiUrl = "https://api.iplocation.net/?ip=" + ipAddress;
            HttpURLConnection connection = (HttpURLConnection) new URL(apiUrl).openConnection();
            connection.setRequestMethod("GET");

            if (connection.getResponseCode() == 200) { // OK
                InputStreamReader reader = new InputStreamReader(connection.getInputStream());
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();

                // Check the response_code to ensure successful lookup
                if (json.has("response_code") && json.get("response_code").getAsString().equals("200")) {
                    // Extract country name
                    String country = json.has("country_name") ? json.get("country_name").getAsString() : "Unknown country";

                    context.getSource().sendFeedback(() -> Text.literal("[IpLogger] Location for IP " + ipAddress + ": " + country)
                            .setStyle(Style.EMPTY.withColor(Formatting.AQUA)), false);
                } else {
                    // Handle failed lookups with response_message
                    String message = json.has("response_message") ? json.get("response_message").getAsString() : "Unknown error";
                    context.getSource().sendFeedback(() -> Text.literal("[IpLogger] Failed to locate IP: " + message)
                            .setStyle(Style.EMPTY.withColor(Formatting.RED)), false);
                }
            } else {
                context.getSource().sendFeedback(() -> Text.literal("[IpLogger] Failed to connect to IP geolocation service.")
                        .setStyle(Style.EMPTY.withColor(Formatting.RED)), false);
            }
        } catch (Exception e) {
            context.getSource().sendFeedback(() -> Text.literal("[IpLogger] Error occurred while fetching location.")
                    .setStyle(Style.EMPTY.withColor(Formatting.RED)), false);
            e.printStackTrace();
        }

        return 1;
    }

}