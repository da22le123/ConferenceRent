package system;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

public class Room {
    @JsonProperty("roomId")
    private final String roomId;
    @JsonProperty("isBooked")
    private boolean isBooked;
    private boolean isPending;
    private String reservationId;


    public Room(@JsonProperty("roomId") String roomId, @JsonProperty("isBooked") boolean isBooked) {
        this.roomId = roomId;
        this.isBooked = isBooked;
        this.isPending = false;
    }
    public Room() {
        this.roomId = UUID.randomUUID().toString().substring(0, 8);
        this.isBooked = false;
        isPending = false;
    }

    public boolean isPending() {
        return isPending;
    }

    public String getReservationId() {
        return reservationId;
    }

    public void setReservationId(String reservationId) {
        this.reservationId = reservationId;
    }

    public void reserve() {
        isPending = true;
    }

    public String getRoomId() {
        return roomId;
    }

    public boolean isBooked() {
        return isBooked;
    }

    public void book() {
        if (isBooked)
            throw new IllegalStateException("Room is already booked");

        isBooked = true;
    }


    public void cancelBooking() {
        if (!isBooked)
            throw new IllegalStateException("Room is not booked");

        isBooked = false;
    }

    @Override
    public String toString() {
        return "Room{" +
                "roomId='" + roomId + '\'' +
                ", isBooked=" + isBooked +
                '}';
    }


}
