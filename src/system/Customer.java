package system;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLOutput;
import java.util.Scanner;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DeliverCallback;
import system.utils.Utility;
import system.utils.RequestType;

public class Customer {
    ReentrantLock lock = new ReentrantLock();
    private final Condition responseReceivedCondition = lock.newCondition();
    private boolean receivedResponse = false;



    private final ObjectMapper mapper;
    private final Scanner scanner;
    private Channel channel;
    private Connection connection;
    private final String customerID;




    private static final String AGENT_CUSTOMER_EXCHANGE = "agentCustExchange";
    private static final String CUSTOMER_AGENT_EXCHANGE = "custAgentExchange";
    private static final String CUSTOMER_AGENT_QUEUE = "custAgentQueue";

    public Customer() {
        this.mapper = new ObjectMapper();
        this.scanner = new Scanner(System.in);
        this.customerID = UUID.randomUUID().toString().substring(0, 8);
        System.out.println("Welcome to Conference Rent system, Customer "+customerID+" !" );
    }

    public static void main(String[] args) throws IOException, TimeoutException, InterruptedException {
        Customer customer = new Customer();
        customer.start();
    }

    public void start() throws IOException, TimeoutException, InterruptedException {
        initRabbitMq();
        startListeningForResponses();
        displayMenu();
    }


    // Precondition: Agents have to be running first!!!
    private void initRabbitMq() throws IOException, TimeoutException {
        ConnectionFactory factory = new ConnectionFactory();
        connection = factory.newConnection();
        channel = connection.createChannel();

        // Declare the direct exchange where all messages from the CUSTOMER ----> AGENT will be sent
        channel.exchangeDeclare(CUSTOMER_AGENT_EXCHANGE, "direct");
        // Declare a queue for the exchange to pour messages into
        channel.queueDeclare(CUSTOMER_AGENT_QUEUE, false, false, false, null);
        channel.queueBind(CUSTOMER_AGENT_QUEUE, CUSTOMER_AGENT_EXCHANGE, "");

        // Declare a queue for each customer that will listen for responses from the agent
        // Name consists of the customerID and the word "Queue"
        channel.queueDeclare(customerID+"Queue", false, false, false, null);
        // Bind the queue to the exchange, the routing key is the customerID
        channel.queueBind(customerID+"Queue", AGENT_CUSTOMER_EXCHANGE, customerID);

    }

    private void startListeningForResponses() throws IOException {
        // Callback for receiving the list of buildings
        DeliverCallback deliverCallback = (consumerTag, delivery) -> {
            String responseReceived = new String(delivery.getBody(), StandardCharsets.UTF_8);
            String typeOfResponse = responseReceived.split(" ")[0];



            switch (typeOfResponse) {
                case "BUILDINGS_LIST": {
                    // remove the type of response from the message
                    responseReceived = responseReceived.substring(typeOfResponse.length() + 1);

                    Utility.printBuildingList(responseReceived);  // Print the list of buildings
                    break;
                }
                default: {
                    System.out.println("Received message from Agent:");
                    System.out.println(responseReceived);
                    break;
                }
            }

            // Signal the waiting thread that response has been received
            lock.lock();
            try {
                receivedResponse = true;
                responseReceivedCondition.signal();  // Wake up the thread waiting for the response
            } finally {
                lock.unlock();
            }
        };



        // Listen on the customer's queue for responses from the agent
        channel.basicConsume(customerID+"Queue", true, deliverCallback, consumerTag -> {});


    }

    private void requestListOfBuildings() throws IOException, InterruptedException {

        StringBuilder message = new StringBuilder();
        message.append(RequestType.GET_BUILDINGS_LIST);
        message.append(" ");
        message.append(customerID);
        String messageStr = message.toString();

        // Publish the request to CUSTOMER_AGENT_EXCHANGE for the agent to pick up
        // Routing key is "", a blank field
        channel.basicPublish(CUSTOMER_AGENT_EXCHANGE, "", null, messageStr.getBytes());
        receivedResponse = false;
        System.out.println("[x] Sent by Customer  " + customerID + "  to request the building list.");

        // Wait for the response
        lock.lock();
        try {
            while (!receivedResponse) {
                responseReceivedCondition.await();  // Wait until response is received
            }
        } finally {
            lock.unlock();
        }

        // Reset the flag for the next request
        receivedResponse = false;
    }

    private void makeBooking(String buildingID, String roomID) throws IOException, InterruptedException {
        // Bind the queue to the exchange, the routing key is the customerID
        channel.queueBind(customerID+"Queue", AGENT_CUSTOMER_EXCHANGE, customerID);

        StringBuilder message = new StringBuilder();
        message.append(RequestType.MAKE_BOOKING);
        message.append(" ");
        message.append(customerID);
        message.append(" ");
        message.append(buildingID);
        message.append(" ");
        message.append(roomID);
        String messageStr = message.toString();

        // Publish the request to CUSTOMER_AGENT_EXCHANGE for the agent to pick up
        // Routing key is "", a blank field
        channel.basicPublish(CUSTOMER_AGENT_EXCHANGE, "", null, messageStr.getBytes());
        System.out.println("[x] Sent by Customer  " + customerID + "  to request the booking.");

        // Wait for the response
        lock.lock();
        try {
            while (!receivedResponse) {
                responseReceivedCondition.await();  // Wait until response is received
            }
        } finally {
            lock.unlock();
        }

        // Reset the flag for the next request
        receivedResponse = false;
    }



    public void close() {
        try {
            channel.close();
            connection.close();
            scanner.close();
        } catch (IOException | TimeoutException e) {
            throw new RuntimeException(e);
        }
    }

    public void printCustomerMenuOptions() {
        System.out.println("List of actions:");
        System.out.println("1. Get list of buildings.");
        System.out.println("2. Make booking.");
        System.out.println("3. Confirm booking.");
        System.out.println("4. Cancel booking.");
        System.out.println("0. Exit");
        System.out.print("Enter your choice: ");
    }

    public void displayMenu() throws IOException, InterruptedException {
        int choice = -1;
        while (choice != 0) {
            printCustomerMenuOptions();
            if (scanner.hasNextInt()) {
                choice = scanner.nextInt();
                if (choice >= 0 && choice <= 4) {
                    switch (choice) {
                        case 1 -> requestListOfBuildings();
                        case 2 -> {
                            System.out.println("Please select a building to book a room in (Building ID): ");
                            String buildingID = scanner.next();
                            System.out.println("Please select a room to book (Room ID): ");
                            String roomID = scanner.next();
                            makeBooking(buildingID, roomID);
                        }
                        case 3 -> {
                            System.out.println("Please enter the reservation ID to confirm booking: ");
                            String reservationID = scanner.next();

                            System.out.println("Please enter the building ID: ");
                            String buildingId = scanner.next();

                            System.out.println("Please enter the room ID: ");
                            String roomId = scanner.next();

                            confirmBooking(reservationID, buildingId, roomId);
                        }
                        case 4 -> {
                            System.out.println("Please enter the reservation ID to cancel booking: ");
                            String reservationID = scanner.next();

                            System.out.println("Please enter the building ID: ");
                            String buildingId = scanner.next();

                            System.out.println("Please enter the room ID: ");
                            String roomId = scanner.next();

                            cancelBooking(reservationID, buildingId, roomId);
                        }
                        case 0 -> System.out.println("Goodbye!");
                    }
                } else {
                    System.out.println("Invalid choice. Please select option from the menu(0-4):");
                }
            } else {
                System.out.println("Invalid input. Please enter a valid number(0-4):");
                scanner.next();
            }
        }
        close();
    }

    private void cancelBooking(String reservationID, String buildingId, String roomId) throws IOException, InterruptedException {
        StringBuilder message = new StringBuilder();

        message.append(RequestType.CANCEL_BOOKING.toString());
        message.append(" ");
        message.append(this.customerID);
        message.append(" ");
        message.append(reservationID);
        message.append(" ");
        message.append(buildingId);
        message.append(" ");
        message.append(roomId);

        channel.basicPublish(CUSTOMER_AGENT_EXCHANGE, "", null, message.toString().getBytes());
        System.out.println("[x] Sent request by Customer  " + customerID + "  to cancel the booking.");

        // Wait for the response
        lock.lock();
        try {
            while (!receivedResponse) {
                responseReceivedCondition.await();  // Wait until response is received
            }
        } finally {
            lock.unlock();
        }

        // Reset the flag for the next request
        receivedResponse = false;
    }

    private void confirmBooking(String reservationID, String buildingId, String roomId) throws IOException, InterruptedException {
        StringBuilder message = new StringBuilder();

        message.append(RequestType.CONFIRM_BOOKING.toString());
        message.append(" ");
        message.append(this.customerID);
        message.append(" ");
        message.append(reservationID);
        message.append(" ");
        message.append(buildingId);
        message.append(" ");
        message.append(roomId);


        channel.basicPublish(CUSTOMER_AGENT_EXCHANGE, "", null, message.toString().getBytes());
        System.out.println("[x] Sent request by Customer  " + customerID + "  to confirm the booking.");

        // Wait for the response
        lock.lock();
        try {
            while (!receivedResponse) {
                responseReceivedCondition.await();  // Wait until response is received
            }
        } finally {
            lock.unlock();
        }

        // Reset the flag for the next request
        receivedResponse = false;
    }


}
