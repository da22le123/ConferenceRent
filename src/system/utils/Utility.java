package system.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import system.Building;

import java.io.IOException;
import java.util.List;

public class Utility {
    // Method to convert a String to a Building object
    public static Building parseBuilding(String input) throws IOException {
        // Step 1: Preprocess the input string to make it valid JSON
        String jsonCompatibleInput = preprocessInput(input);

        // Step 2: Use Jackson to convert the preprocessed JSON string into a Building object
        ObjectMapper objectMapper = new ObjectMapper();
        return objectMapper.readValue(jsonCompatibleInput, Building.class);
    }

    public static void printBuildingList(String response) {
        // Remove the square brackets at the start and end
        response = response.replaceFirst("\\[", "").replaceFirst("\\]$", "");

        // Split the response string by "}, Building{" to separate each building
        String[] buildings = response.split("\\}, Building\\{");

        // Iterate over each building
        for (String building : buildings) {
            // Add back the removed characters for proper processing
            building = "Building{" + building.trim();

            // Extract the building ID
            String buildingID = building.substring(building.indexOf("buildingID='") + 12, building.indexOf("', rooms="));
            System.out.println("Building ID: " + buildingID);

            // Extract the rooms part of the string
            String roomsString = building.substring(building.indexOf("rooms=[") + 7, building.lastIndexOf("]"));
            String[] rooms = roomsString.split("Room\\{");

            // Print room details
            System.out.println("  Rooms:");
            for (String room : rooms) {
                room = room.trim();
                if (room.isEmpty()) continue; // Skip empty entries

                // Extract room ID and booking status
                String roomId = room.substring(room.indexOf("roomId='") + 8, room.indexOf("', isBooked="));
                String isBooked = room.substring(room.indexOf("isBooked=") + 9, room.indexOf("}"));

                // Print room details
                System.out.println("    - Room ID: " + roomId + ", Booked: " + isBooked);
            }

            System.out.println(); // Print an empty line between buildings
        }
    }

    public static boolean validateBuildingNRoomIDs(String buildingID, String roomID, List<String> buildings) {
        for (String s: buildings) {
            if (s.contains(buildingID)) {
                if (s.contains(roomID)) {
                    return true;
                }
            }
        }

        return false;
    }


    public static int getBuildingIndex(String message, List<String> buildings) {
        int index = -1;

        String buildingID = extractBuildingID(message);

        for (int i = 0; i < buildings.size(); i++) {
            if (buildings.get(i).contains(buildingID)) {
                index = i;
                break;
            }
        }

        return index;
    }

    public static String extractBuildingID(String message) {
        // Check if the string contains "buildingID='"
        if (message.contains("buildingID='")) {
            // Find the starting index of the buildingID
            int startIndex = message.indexOf("buildingID='") + 12;  // 12 is the length of "buildingID='"
            // Find the ending index (position of the next single quote after the ID)
            int endIndex = message.indexOf("'", startIndex);
            // Extract and return the buildingID
            return message.substring(startIndex, endIndex);
        } else {
            return null;  // Return null if the buildingID is not found
        }
    }

    // Preprocess the input string to make it valid JSON
    private static String preprocessInput(String input) {
        // Replace ' with " and adjust the format for JSON
        input = input.replace("'", "\""); // Convert single quotes to double quotes
        input = input.replace("Building{", "{\"buildingID\":");
        input = input.replace("Room{", "{\"roomId\":");
        input = input.replace("isBooked=", "\"isBooked\":");
        input = input.replace("rooms=", "\"rooms\":");
        input = input.replace("}, ", "},"); // Remove extra spaces after commas
        input = input.replace("]} ", "]}");  // Remove extra space at the end

        return input;
    }
}
