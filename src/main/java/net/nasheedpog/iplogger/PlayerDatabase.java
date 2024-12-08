package net.nasheedpog.iplogger;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import static net.nasheedpog.iplogger.IpLogger.debugMode;
import static net.nasheedpog.iplogger.IpLoggerCommands.geolocate;

public class PlayerDatabase {
    private static final String DATA_FILE = "config/iplogger/IpLoggerData.json";
    private final HashMap<String, List<IpEntry>> players = new HashMap<>();
    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public PlayerDatabase() {
        new File("config/iplogger").mkdirs(); // Ensure directory exists
    }

    // use the location getter from outside
    public String getLocation(String ipAddress) {
        for (Map.Entry<String, List<IpEntry>> entry : players.entrySet()) {
            for (IpEntry ipEntry : entry.getValue()) {
                if (ipEntry.getIp().equals(ipAddress)) {
                    return ipEntry.getLocation();
                }
            }
        }

        return null;
    }


    // Load data from JSON and check if migration to new format is needed
    public void loadFromJson() {
        try {
            if (Files.exists(Paths.get(DATA_FILE))) {
                // Read JSON file
                try (FileReader reader = new FileReader(DATA_FILE)) {
                    Type type = new TypeToken<Map<String, Object>>() {}.getType();
                    Map<String, Object> data = new Gson().fromJson(reader, type);

                    // Check if the format is old and needs migration
                    if (needsMigration(data)) {
                        //JSON is in old format, update to the new version
                        createBackup();
                        migrateToNewFormat(data);
                        System.out.println("[IpLogger] Old JSON format detected and updated to the new format.");
                    } else {
                        //Load using the new format
                        Type newFormatType = new TypeToken<Map<String, List<IpEntry>>>() {}.getType();
                        Map<String, List<IpEntry>> newData = new Gson().fromJson(new Gson().toJson(data), newFormatType);
                        players.putAll(newData);
                    }
                }
            } else {
                System.out.println("[IpLogger] No existing data file found.");
            }
        } catch (IOException e) {
            System.out.println("[IpLogger] Error loading JSON file.");
            e.printStackTrace();
        }

    }

    // Migrate old format to the new format and save
    private void migrateToNewFormat(Map<String, Object> oldData) {
        System.out.println("[IpLogger] Starting migration to new format...");

        oldData.forEach((username, ipsObject) -> {
            List<IpEntry> ipEntries = new ArrayList<>();
            System.out.println("[IpLogger] Migrating data for user: " + username);

            // Check for "ipTimestamps" field in ipsObject
            if (ipsObject instanceof Map) {
                Map<?, ?> outerMap = (Map<?, ?>) ipsObject;

                if (outerMap.containsKey("ipTimestamps")) {
                    Object timestampsObj = outerMap.get("ipTimestamps");

                    // Ensure "ipTimestamps" is a map of IPs to timestamps
                    if (timestampsObj instanceof Map) {
                        Map<?, ?> ipMap = (Map<?, ?>) timestampsObj;

                        ipMap.forEach((ip, timestamp) -> {
                            //System.out.println("[IpLogger] Processing IP: " + ip + " with timestamp: " + timestamp);
                            if (ip instanceof String && timestamp instanceof String) {
                                String location = geolocate((String) ip); // Get the geolocation for this ip
                                ipEntries.add(new IpEntry((String) ip, (String) timestamp, location )); // Store the ip+time+location as an IpEntry object in the ipEntries list
                            }
                        });
                    }
                }
            }

            // Only migrate the player's data from the old JSON to the new if they had any IP-data. Don't need to keep empty entries.
            // Only reason a player would be empty is if they were manually deleted by an admin with RemoveIpFromUser command.
            if (!ipEntries.isEmpty()) {
                ipEntries.sort(Comparator.comparing(IpEntry::getTimestamp)); // sort ipEntries to be in chronological order
                players.put(username, ipEntries);
                System.out.println("[IpLogger] Added " + ipEntries.size() + " IP entries for user " + username);
            } else {
                System.out.println("[IpLogger] No IP entries found for user " + username);
            }
        });

        // Save to JSON and confirm
        saveToJson();
        System.out.println("[IpLogger] Migration complete. Data saved to new JSON format.");
    }


    // Create a backup of the current JSON file
    private void createBackup() throws IOException {
        String backupFilename = String.format("config/iplogger/backup_%s_IpLoggerData.json",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH.mm.ss")));
        Files.copy(Paths.get(DATA_FILE), Paths.get(backupFilename));
    }

    // Check if data format is old
    private boolean needsMigration(Map<String, Object> data) {
        // Old format uses Map for IP entries, while the new format uses a list of objects (IpEntry)
        return data.values().stream().anyMatch(value -> value instanceof Map);
    }

    // Save data to JSON
    public void saveToJson() {
        try (FileWriter writer = new FileWriter(DATA_FILE)) {
            new Gson().toJson(players, writer);
        } catch (IOException e) {
            System.out.println("[IpLogger] Error saving to JSON.");
            e.printStackTrace();
        }
    }

    // Track a player's IP address
    public void trackPlayer(String username, String ipAddress, String location) {
        //computeIfAbsent will first see if username exists in players. If it does, then do nothing (by default)
        //If the username doesn't exist, then make a new ArrayList (which we will use to store that user's ip entries)
        //If the username does exist, then just get me the arraylist (a list of IpEntry objects) for that person.
        List<IpEntry> ipEntries = players.computeIfAbsent(username, k -> new ArrayList<>());

        String timestamp = LocalDateTime.now().format(formatter);
        IpEntry newEntry = new IpEntry(ipAddress, timestamp, location);

        //If the IP doesn't exist in the user's arraylist already, then add the newEntry.
        if (ipEntries.stream().noneMatch(entry -> entry.getIp().equals(ipAddress))) {
            ipEntries.add(newEntry);
            ipEntries.sort(Comparator.comparing(IpEntry::getTimestamp)); // Sort the list chronologically
            players.put(username,ipEntries); // put the updated ipentries back into the players hashmap
            saveToJson();
            System.out.printf("[IpLogger] New IP logged for %s: %s (%s)%n", username, ipAddress, location);
        } else {
            System.out.printf("[IpLogger] Existing IP detected for %s: %s (%s)%n", username, ipAddress, location);
        }
    }

    // Utility method to get all usernames
    public Set<String> getUsernames() {
        return players.keySet();
    }

    // Utility method to get all unique IP addresses across all users
    public Set<String> getAllIPs() {
        Set<String> allIPs = new HashSet<>();
        players.values().forEach(ipEntries ->
                ipEntries.forEach(entry -> allIPs.add(entry.getIp()))
        );
        return allIPs;
    }

    // Utility method to get all IP addresses for a specific user
    public Set<String> getIpAddressesForUser(String username) {
        List<IpEntry> ipEntries = players.get(username);
        if (ipEntries == null) {
            return null;
        }

        Set<String> ipSet = new HashSet<>();
        ipEntries.forEach(ipEntry -> ipSet.add(ipEntry.getIp()));
        return ipSet;
    }

    // Utility method to find duplicate IPs across multiple users
    public Map<String, List<String>> getDuplicateIPs() {
        //Key = IP address, Value = list of usernames
        Map<String, List<String>> ipToUsers = new HashMap<>();

        players.forEach((username, ipEntries) -> {
            for (IpEntry ipEntry : ipEntries) {
                ipToUsers
                        .computeIfAbsent(ipEntry.getIp(), k -> new ArrayList<>() )
                        .add(username);
            }
        });

        // Filter out IPs with only one user to find duplicates
        return ipToUsers.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    // Utility method to get users for a specific IP address
    public List<String> getUsersForIp(String ipAddress) {
        List<String> users = new ArrayList<>();

        players.forEach((username,ipEntries) -> {
            for(IpEntry ipEntry : ipEntries) {
                if (ipEntry.getIp().equals(ipAddress)){
                    users.add(username);
                }
            }
        });

        return users;
    }

    // Utility method to remove an IP address from a user
    public boolean removeIpFromUser(String username, String ipAddress) {
        List<IpEntry> ipEntries = players.get(username);

        if (ipEntries == null) {
            return false; // user not found
        }

        // Remove the entry if it exists. The removeIf also returns a boolean if successful automatically! :)
        boolean removed = ipEntries.removeIf(entry -> entry.getIp().equals(ipAddress));

        if (removed) {
            players.put(username,ipEntries); // update the database with the new list where the ip is removed
            // If the user's entries are now empty, then remove the entire user (don't need to keep empty entries)
            if (ipEntries.isEmpty()){
                players.remove(username);
            }
        }
        saveToJson();
        return removed;
    }

    // Retrieves the timestamp for a specific user-IP combination
    public String getTimestampForUserIp(String username, String ipAddress) {
        List<IpEntry> ipEntries = players.get(username);

        if (ipEntries == null) {
            return null; //user not found
        }

        for (IpEntry ipEntry : ipEntries) {
            if (ipEntry.getIp().equals(ipAddress)){
                return ipEntry.getTimestamp();
            }
        }
        return null; // user exists, but not the ip
    }

    // Adds or updates an IP entry with a given timestamp if it's the first or earliest occurrence
    public void addOrUpdateIpEntry(String username, String ipAddress, String timestamp) {
        List<IpEntry> ipEntries = players.get(username);

        if (ipEntries == null) {
            // if ipEntries is null, then the player doesn't exist from before. Make a new entry in the database.
            // calling the trackPlayer function will just make a new player&ipEntry, but the timestamp will be set to now().
            // Continuing on afterwards, this timestamp will be caught as not the most recent, and be updated to "timestamp"
            if (debugMode){
                System.out.println("[IpLogger_debug]: Player "+username+" didn't exist from before -> Adding to database");
            }
            trackPlayer(username,ipAddress,geolocate(ipAddress)); // make new player in database (timestamp = now())
            ipEntries = players.get(username); // refresh content of ipEntries, which now contains info that we made in the command directly above
        }

        String existingTimestamp = null;
        for (IpEntry ipEntry : ipEntries) {
            if (ipEntry.getIp().equals(ipAddress)) {
                existingTimestamp = ipEntry.getTimestamp();
                // update timestamp if new input time is earlier than previous time
                if (debugMode) {
                    System.out.println("[IpLogger_debug]: Updating entry for " + username + ". Old timestamp was " + existingTimestamp + ", new timestamp is " + timestamp + ".");
                }
                if (LocalDateTime.parse(existingTimestamp, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).isAfter(LocalDateTime.parse(timestamp, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))){
                    ipEntry.setTimestamp(timestamp);
                }
            }
        }

        // If existingTimestamp is still null, then the ip didn't exist for that player, so add a new one
        if (existingTimestamp == null ) {
            if (debugMode){
                System.out.println("[IpLogger_debug]: The ip "+ipAddress+" didn't exist for "+username+", so adding it to the user.");
            }
            ipEntries.add(new IpEntry(ipAddress, timestamp, geolocate(ipAddress)));
        }
        ipEntries.sort(Comparator.comparing(IpEntry::getTimestamp)); // sort ipEntries to be in chronological order
        players.put(username, ipEntries);
        saveToJson();

        // The database should now have been updated with new entry-info, or new info added. Adding a check (for debug purposes) to see if it was successfully added.
        if (debugMode){
            ipEntries = players.get(username);
            existingTimestamp = null;
            for (IpEntry ipEntry : ipEntries) {
                if (ipEntry.getIp().equals(ipAddress)) {
                    existingTimestamp = ipEntry.getTimestamp();
                    if (LocalDateTime.parse(existingTimestamp, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).isEqual(LocalDateTime.parse(timestamp, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))){
                        System.out.println("[IpLogger_debug]: Update successful");
                    }
                }
            }
            if (existingTimestamp==null){
                System.out.println("[IpLogger_debug]: Update failed (timestamp is still null!)");
            }
        }
    }

    public List<IpEntry> getEntries(String username) {
        return players.get(username);
    }

    // Inner class to store IP data
    public static class IpEntry {
        private String ip;
        private String timestamp;
        private String location;

        public IpEntry(String ip, String timestamp, String location) {
            this.ip = ip;
            this.timestamp = timestamp;
            this.location = location;
        }

        public String getIp() { return ip; }
        public void setIp(String ip) { this.ip = ip; }
        public String getTimestamp() { return timestamp; }
        public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
        public String getLocation() { return location; }
        public void setLocation(String location) { this.location = location; }
    }


}
