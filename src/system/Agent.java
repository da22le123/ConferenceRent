package system;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;
import system.utils.RequestType;
import system.utils.Utility;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

public class Agent {
    private final String agentID;
    private Channel channel;
    private Connection connection;
    ObjectMapper mapper;
    List<String> buildings;
    private String latestRequestCustomerId;


    private static final String BUILDING_FANOUT_EXCHANGE = "buildingsExchange";
    private static final String AGENTS_QUEUE = "agentsQueue";
    private static final String AGENTS_EXCHANGE = "agentsExchange";




    // the exchange where the messages from the building to the agents are sent
    private static final String BUILDING_AGENT_EXCHANGE = "buildAgentExchange";
    private static final String AGENT_BUILDING_EXCHANGE = "agentBuildExchange";
    private static final String AGENT_BUILDING_FANOUT_EXCHANGE = "agentBuildFanoutExchange";
    private static final String AGENT_BUILDING_INFO_QUEUE = "agentBuildQueue";
    private static final String CUSTOMER_AGENT_QUEUE = "custAgentQueue";
    private static final String AGENT_CUSTOMER_EXCHANGE = "agentCustExchange";

    // not static because each agent has its own queue connected to the building exchange
    private final String agentsBuildingQueue;

    public static void main(String[] args) throws IOException, TimeoutException {
        Agent agent = new Agent();
        agent.startListening();
    }

    public Agent() {
        this.buildings = new ArrayList<>();
        this.mapper = new ObjectMapper();
        this.agentID = UUID.randomUUID().toString().substring(0, 8);
        agentsBuildingQueue = "agent_" + agentID + "_queue";
    }

    private void startListening() throws IOException, TimeoutException {
        initRabbitMq();
        listenForInfoBuildingsUpdates();
        listenForCustomerMessages();
        listenForBuildingMessages();
    }

    private void initRabbitMq() throws IOException, TimeoutException {
        ConnectionFactory factory = new ConnectionFactory();
        connection = factory.newConnection();
        channel = connection.createChannel();

        // Declare a queue for each agent that will listen for building information
        // Bind it to the fanout exchange declared in building
        channel.exchangeDeclare(AGENT_BUILDING_FANOUT_EXCHANGE, "fanout");
        channel.queueDeclare(AGENT_BUILDING_INFO_QUEUE, false, false, false, null);
        channel.queueBind(AGENT_BUILDING_INFO_QUEUE, AGENT_BUILDING_FANOUT_EXCHANGE, "");

        // Declare a personal queue for each agent that will listen for responses building //todo
        channel.queueDeclare(agentID+"Queue", false, false, false, null);


        // Declare a shared queue that all agents will listen to for customer requests
        channel.queueDeclare(CUSTOMER_AGENT_QUEUE, false, false, false, null);

        // Declare direct exchange for customer responses
        channel.exchangeDeclare(AGENT_CUSTOMER_EXCHANGE, "direct");

        // Declare direct exchange for agent requests to the building
        channel.exchangeDeclare(AGENT_BUILDING_EXCHANGE, "direct");
    }


    //done
    private void listenForCustomerMessages() throws IOException {
        DeliverCallback deliverCallback = (consumerTag, delivery) -> {
            String message = new String(delivery.getBody(), "UTF-8");
            System.out.println(" [x] Received a customer request '" + message + "'");
            handleCustomerMessage(message);
        };

        // Start consuming messages from the agents queue
        channel.basicConsume(CUSTOMER_AGENT_QUEUE, true, deliverCallback, consumerTag -> { System.out.println("Customer request consumer was cancelled for some reason.");});
    }

    private void listenForBuildingMessages() throws IOException {
        DeliverCallback deliverCallback = (consumerTag, delivery) -> {
            String message = new String(delivery.getBody(), "UTF-8");
            System.out.println(" [x] Received a building request '" + message + "'");
            handleBuildingMessage(message);
        };

        // Listen on the agent's queue for responses from the building
        channel.basicConsume(agentID+"Queue", true, deliverCallback, consumerTag -> { System.out.println("Building request consumer was cancelled for some reason.");});
    }


    private void listenForInfoBuildingsUpdates() throws IOException {
        System.out.println("Agent " + agentID + " is listening for building updates on queue: " + AGENT_BUILDING_INFO_QUEUE);

        // Callback for when newly created building sends its information or
        // updated building sends information containing the updated data
        DeliverCallback deliverCallback = (consumerTag, delivery) -> {
            String message = new String(delivery.getBody(), "UTF-8");
            int indexOfBuilding = Utility.getBuildingIndex(message, buildings);

            // If the building is not in the list, add it, otherwise update it
            if (indexOfBuilding == -1)
                buildings.add(message);
            else
                buildings.set(indexOfBuilding, message);


            System.out.println(" [x] Received a building update '" + buildings.get(buildings.size()-1) + "'");
        };

        // Start consuming messages from the agents queue
        System.out.println("Agent " + agentID + " is now listening for building updates on queue: " + AGENT_BUILDING_INFO_QUEUE);
        channel.basicConsume(AGENT_BUILDING_INFO_QUEUE, true, deliverCallback, consumerTag -> { System.out.println("Building update consumer was cancelled for some reason.");});
    }

    private void sendDirectTo(String exchange, String routingKey, String messageToSend) throws IOException {
        channel.basicPublish(exchange, routingKey, null, messageToSend.getBytes());

        String entity = exchange.equals(AGENT_CUSTOMER_EXCHANGE) ? "Customer" : "Building";

        System.out.println(" [x] Sent provided message to " + entity + " with ID: " + routingKey);
    }


    private void handleCustomerMessage(String message) throws IOException {
        String[] messageParts = message.split(" ");

        RequestType requestType = RequestType.valueOf(messageParts[0]);

        // The id of the customer that has made a request serves as the routing key for the response
        latestRequestCustomerId = messageParts[1];


        if (requestType == RequestType.GET_BUILDINGS_LIST) {
            try {
                sendDirectTo(AGENT_CUSTOMER_EXCHANGE, latestRequestCustomerId, "BUILDINGS_LIST " + buildings.toString());
            } catch (IOException e) {
                e.printStackTrace();
            }
            // other request types
        } else {// Bind the queue that will listen to the building requests to the exchange, the routing key is the agentID
            // It is bound here because agent cannot bind this queue before the BUILDING_AGENT_EXCHANGE is declared
            // And its only declared when the building is created
            // If this request was received, then the building definitely exists
            channel.queueBind(agentID + "Queue", BUILDING_AGENT_EXCHANGE, agentID);

            switch (requestType) {
                case MAKE_BOOKING -> {
                    String buildingID = messageParts[2];
                    String roomID = messageParts[3];

                    // If the building and room IDs are valid, send a request to the building
                    if (Utility.validateBuildingNRoomIDs(buildingID, roomID, buildings)) {
                        // Send a request to the building to make a booking
                        sendDirectTo(AGENT_BUILDING_EXCHANGE, buildingID, "MAKE_BOOKING " + roomID + " " + latestRequestCustomerId + " " + agentID);
                    } else {
                        sendDirectTo(AGENT_CUSTOMER_EXCHANGE, latestRequestCustomerId, "INVALID_BOOKING_DETAILS Booking failed, invalid building or room ID");
                    }
                }
                case CONFIRM_BOOKING -> {
                    String reservationID = messageParts[2];
                    String agentID = this.agentID;
                    String buildingID = messageParts[3];
                    String roomID = messageParts[4];

                    sendDirectTo(AGENT_BUILDING_EXCHANGE, buildingID, "CONFIRM_BOOKING " + reservationID + " " + agentID + " " + roomID);
                }
                case CANCEL_BOOKING -> {
                    String reservationID = messageParts[2];
                    String agentID = this.agentID;
                    String buildingID = messageParts[3];
                    String roomID = messageParts[4];

                    sendDirectTo(AGENT_BUILDING_EXCHANGE, buildingID, "CANCEL_BOOKING " + reservationID + " " + agentID + " " + roomID);
                }
            }
        }
    }


    //todo make a nice controller with switch-case based on the response from the building
    private void handleBuildingMessage(String message) throws IOException {
        String[] messageParts = message.split(" ");

        RequestType requestType = RequestType.valueOf(messageParts[0]);

        String messageReceived = message.substring(requestType.toString().length() + 1); // Get the message without the response type
        sendDirectTo(AGENT_CUSTOMER_EXCHANGE, latestRequestCustomerId, messageReceived);

    }

}
