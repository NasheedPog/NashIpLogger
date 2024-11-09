package net.nasheedpog.iplogger;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

public class PlayerDatabase {
    private static final String DATA_FILE = "config/iplogger/IpLoggerData.json";
    private final Map<String, PlayerInfo> players = new HashMap<>();
    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public PlayerDatabase() {
        new File("config/iplogger").mkdirs(); // Ensure directory exists
    }

    // Load data from JSON
    public void loadFromJson() {
        try (FileReader reader = new FileReader(DATA_FILE)) {
            Type type = new TypeToken<Map<String, PlayerInfo>>(){}.getType();
            Map<String, PlayerInfo> data = new Gson().fromJson(reader, type);
            if (data != null) {
                players.putAll(data);
            }
        } catch (IOException e) {
            System.out.println("[IpLogger] No previous data found or error reading file.");
        }
    }

    // Save data to JSON
    public void saveToJson() {
        try (FileWriter writer = new FileWriter(DATA_FILE)) {
            new Gson().toJson(players, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Track a player's IP address
    public void trackPlayer(String username, String ipAddress) {
        PlayerInfo player = players.computeIfAbsent(username, k -> new PlayerInfo());
        if (player.addIpAddress(ipAddress)) {
            System.out.printf("[IpLogger] New IP logged for %s: %s%n", username, ipAddress);
            saveToJson(); // Save immediately after logging a new IP
        } else {
            System.out.printf("[IpLogger] Existing IP detected for %s: %s%n", username, ipAddress);
        }
    }

    // Utility method to get all usernames
    public List<String> getUsernames() {
        return new ArrayList<>(players.keySet());
    }

    // Utility method to get all unique IP addresses across all users
    public List<String> getAllIPs() {
        return players.values().stream()
                .flatMap(playerInfo -> playerInfo.getIpTimestamps().keySet().stream())
                .distinct()
                .collect(Collectors.toList());
    }

    // Utility method to get IP addresses for a specific user
    public Map<String, String> getIpAddressesForUser(String username) {
        PlayerInfo player = players.get(username);
        return player != null ? player.getIpTimestamps() : null;
    }

    // Utility method to find duplicate IPs across multiple users
    public Map<String, List<String>> getDuplicateIPs() {
        Map<String, List<String>> ipToUsers = new HashMap<>();

        for (Map.Entry<String, PlayerInfo> entry : players.entrySet()) {
            String username = entry.getKey();
            PlayerInfo info = entry.getValue();

            for (String ip : info.getIpTimestamps().keySet()) {
                ipToUsers.computeIfAbsent(ip, k -> new ArrayList<>()).add(username);
            }
        }

        // Filter out IPs with only one user to find duplicates
        return ipToUsers.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    // Utility method to get users for a specific IP address
    public List<String> getUsersForIp(String ipAddress) {
        return players.entrySet().stream()
                .filter(entry -> entry.getValue().getIpTimestamps().containsKey(ipAddress))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    // Utility method to remove an IP address from a user
    public boolean removeIpFromUser(String username, String ipAddress) {
        PlayerInfo player = players.get(username);
        if (player != null && player.getIpTimestamps().containsKey(ipAddress)) {
            player.getIpTimestamps().remove(ipAddress);
            saveToJson();
            return true;
        }
        return false;
    }

    // Utility method to get a timestamp for a user-IP pair
    public String getTimestampForUserIp(String username, String ipAddress) {
        PlayerInfo player = players.get(username);
        return player != null ? player.getIpTimestamps().get(ipAddress) : null;
    }

    // Inner PlayerInfo class
    private static class PlayerInfo {
        private final Map<String, String> ipTimestamps = new HashMap<>();

        public boolean addIpAddress(String ipAddress) {
            if (!ipTimestamps.containsKey(ipAddress)) {
                ipTimestamps.put(ipAddress, LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                return true;
            }
            return false;
        }

        public Map<String, String> getIpTimestamps() {
            return ipTimestamps;
        }
    }
}
