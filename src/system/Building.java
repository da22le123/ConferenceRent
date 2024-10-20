package system;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import system.utils.RequestType;
import system.utils.Utility;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

public class Building {
    private Channel channel;
    private Connection connection;
    private final ObjectMapper mapper;
    @JsonProperty("buildingID")
    private final String buildingID;
    @JsonProperty("rooms")
    private List<Room> rooms;
    private List<Reservation> reservations;


    // the exchange where the messages from the building to the agents are sent
    private static final String BUILDING_AGENT_EXCHANGE = "buildAgentExchange";
    // the exchange where the queue intended for requests from agents is bound to
    private static final String AGENT_BUILDING_EXCHANGE = "agentBuildExchange";
    // the exchange where the building information is sent to
    private static final String AGENT_BUILDING_FANOUT_EXCHANGE = "agentBuildFanoutExchange";

    public static void main(String[] args) throws IOException, TimeoutException {
        Building building = new Building();
        building.start();
    }

    public Building(@JsonProperty("buildingID") String buildingID, @JsonProperty("rooms") List<Room> rooms) {
        this.mapper = new ObjectMapper();
        this.buildingID = buildingID;
        this.rooms = rooms;
    }

    public Building() {
        this.mapper = new ObjectMapper();
        this.rooms = List.of(new Room(), new Room(), new Room());
        this.buildingID = UUID.randomUUID().toString().substring(0, 8);
        this.reservations = new ArrayList<>();
    }

    private void start() throws IOException, TimeoutException {
        initRabbitMq();
        startListeningForMessages();
        // send building info to the fanout exchange once building is created
        sendBuildingInformation();
    }

    private void sendBuildingInformation() throws IOException {
        String message = this.toString();

        channel.basicPublish(AGENT_BUILDING_FANOUT_EXCHANGE, "", null, message.getBytes());

        System.out.println("[x] Sent Building ID: " + buildingID + " with info: " + message);
    }

    // Precondition: Agents have to be running first!!!
    private void initRabbitMq() throws IOException, TimeoutException {
        ConnectionFactory factory = new ConnectionFactory();
        connection = factory.newConnection();
        channel = connection.createChannel();

        // Declare the fanout exchange to send building information
        channel.exchangeDeclare(AGENT_BUILDING_FANOUT_EXCHANGE, "fanout");

        // Declare the exchange where the building sends messages to the agents
        channel.exchangeDeclare(BUILDING_AGENT_EXCHANGE, "direct");

        // Declare the queue for the agents to pour requests into
        channel.queueDeclare(buildingID+"Queue", false, false, false, null);
        channel.queueBind(buildingID+"Queue", AGENT_BUILDING_EXCHANGE, buildingID);
    }


    private void startListeningForMessages() throws IOException {
        // Callback for receiving the list of buildings
        DeliverCallback deliverCallback = (consumerTag, delivery) -> {
            String messageReceived = new String(delivery.getBody(), StandardCharsets.UTF_8);
            handleAgentMessage(messageReceived);
        };

        // Listen on the customer's queue for responses
        channel.basicConsume(buildingID+"Queue", true, deliverCallback, consumerTag -> {});
    }

    private void handleAgentMessage(String messageReceived) throws IOException {
        String typeOfResponse = messageReceived.split(" ")[0];
        RequestType requestType = RequestType.valueOf(messageReceived.split(" ")[0]);

        // remove the type of response from the message
        messageReceived = messageReceived.substring(typeOfResponse.length() + 1);
        switch (requestType) {
            case MAKE_BOOKING -> {
                String roomID = messageReceived.split(" ")[0]; // now roomId is [0] because we have already omitted the response type
                String customerID = messageReceived.split(" ")[1];
                String agentID = messageReceived.split(" ")[2];

                System.out.println("Received a request to book room with ID: " + roomID + " from customer: " + customerID + " thru agent: " + agentID);

                // find the room with the given ID
                Room room = rooms.stream().filter(r -> r.getRoomId().equals(roomID)).findFirst().orElse(null);




                // send back a response to the same agent that has sent the request
                if (room == null) {
                    // respond the agent that the room does not exist
                    sendDirectTo(BUILDING_AGENT_EXCHANGE, agentID, "INVALID_BOOKING_DETAILS can't book a room with ID: " + roomID + ", it is does not exist");
                } else if (reservationsContainNotConfirmedBooking(roomID)) {
                    // respond the agent that the room is already reserved
                    sendDirectTo(BUILDING_AGENT_EXCHANGE, agentID, "INVALID_BOOKING_DETAILS can't book a room with ID: " + roomID + ", it is already reserved, though not confirmed");
                }
                else if (room.isBooked()) {
                    // respond the agent that the room is already booked
                    sendDirectTo(BUILDING_AGENT_EXCHANGE, agentID, "INVALID_BOOKING_DETAILS can't book a room with ID: " + roomID + ", it is already booked");
                }
                else {
                    // Add a reservation to the list
                    String reservationId = UUID.randomUUID().toString().substring(0, 8);
                    reservations.add(new Reservation(reservationId, customerID, roomID, agentID));
                    // respond the agent that the booking request was successful
                    sendDirectTo(BUILDING_AGENT_EXCHANGE, agentID, "BOOKING_MADE reservation for room with ID: " + roomID + " was registered, awaiting booking confirmation with RESERVATION_ID " + reservationId);
                }

            }
            case CONFIRM_BOOKING -> {

                String reservationID = messageReceived.split(" ")[0];
                String agentID = messageReceived.split(" ")[1];
                String roomID = messageReceived.split(" ")[2];

                System.out.println("Received a request to confirm booking with reservation ID: " + reservationID + " from agent: " + agentID);

                Room room = rooms.stream().filter(r -> r.getRoomId().equals(roomID)).findFirst().orElse(null);

                if (room == null) {
                    // respond the agent that the room does not exist
                    sendDirectTo(BUILDING_AGENT_EXCHANGE, agentID, "INVALID_CONFIRMATION_DETAILS can't confirm a reservation with ID: " + reservationID + ", the room does not exist");
                    return;
                } else if (room.isBooked()) {
                    // respond to the agent that the room is already booked
                    sendDirectTo(BUILDING_AGENT_EXCHANGE, agentID, "INVALID_CONFIRMATION_DETAILS can't confirm a reservation with ID: " + reservationID + ", the room is already booked");
                    return;
                }

                // find the reservation with the given ID
                Reservation reservation = reservations.stream().filter(r -> r.getReservationId().equals(reservationID)).findFirst().orElse(null);


                if (reservation == null) {
                    // respond the agent that the reservation id is incorrect
                    sendDirectTo(BUILDING_AGENT_EXCHANGE, agentID, "INVALID_CONFIRMATION_DETAILS can't confirm a reservation with ID: " + reservationID + ", it doesn't exist");
                } else {
                    // find the room with the given ID
                    Room roomToBook = rooms.stream().filter(r -> r.getRoomId().equals(reservation.getRoomId())).findFirst().orElse(null);
                    roomToBook.book();
                    // respond the agent that the room was booked successfully
                    sendDirectTo(BUILDING_AGENT_EXCHANGE, agentID, "BOOKING_CONFIRMED booking with reservation ID: " + reservationID + " was confirmed successfully");
                    // Update the building information with the new booking status
                    sendBuildingInformation();
                }

            }
            case CANCEL_BOOKING -> {
                String reservationID = messageReceived.split(" ")[0];
                String agentID = messageReceived.split(" ")[1];
                String roomID = messageReceived.split(" ")[2];

                System.out.println("Received a request to confirm booking with reservation ID: " + reservationID + " from agent: " + agentID);

                Room room = rooms.stream().filter(r -> r.getRoomId().equals(roomID)).findFirst().orElse(null);

                if (room == null) {
                    // respond the agent that the room does not exist
                    sendDirectTo(BUILDING_AGENT_EXCHANGE, agentID, "INVALID_CANCELLATION_DETAILS can't confirm a reservation with ID: " + reservationID + ", the room does not exist");
                    return;
                } else if (!room.isBooked()) {
                    // respond to the agent that the room is already booked
                    sendDirectTo(BUILDING_AGENT_EXCHANGE, agentID, "INVALID_CANCELLATION_DETAILS can't confirm a reservation with ID: " + reservationID + ", the room is not booked");
                    return;
                }

                // find the reservation with the given ID
                Reservation reservation = reservations.stream().filter(r -> r.getReservationId().equals(reservationID)).findFirst().orElse(null);

                if (reservation == null) {
                    // respond the agent that the reservation id is incorrect
                    sendDirectTo(BUILDING_AGENT_EXCHANGE, agentID, "INVALID_CANCELLATION_DETAILS can't cancel a reservation with ID: " + reservationID + ", it doesn't exist");
                } else {
                    // find the room with the given ID
                    Room roomToCancel = rooms.stream().filter(r -> r.getRoomId().equals(reservation.getRoomId())).findFirst().orElse(null);
                    roomToCancel.cancelBooking();
                    // remove the reservation from the list
                    reservations.remove(reservation);
                    // respond the agent that the room was booked successfully
                    sendDirectTo(BUILDING_AGENT_EXCHANGE, agentID, "BOOKING_CANCELLED booking with ID: " + reservationID + " was cancelled successfully");
                    // Update the building information with the new booking status
                    sendBuildingInformation();
                }

            }
            default -> {
            }
        }
    }

    private void sendDirectTo(String exchange, String routingKey, String messageToSend) throws IOException {
        channel.basicPublish(exchange, routingKey, null, messageToSend.getBytes());
    }

    public String getBuildingID() {
        return buildingID;
    }

    public List<Room> getRooms() {
        return rooms;
    }

    private boolean reservationsContainNotConfirmedBooking(String roomId) {
        return reservations.stream().anyMatch(r -> r.getRoomId().equals(roomId) && rooms.stream().anyMatch(room -> room.getRoomId().equals(roomId) && !room.isBooked()));
    }

    @Override
    public String toString() {
        return "Building{" +
                "buildingID='" + buildingID + '\'' +
                ", rooms=" + rooms +
                '}';
    }
}
