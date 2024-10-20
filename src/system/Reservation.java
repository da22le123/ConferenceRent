package system;

public class Reservation {
    String reservationId;
    String customerId;
    String roomId;
    String buildingId;

    public Reservation(String reservationId, String customerId, String roomId, String buildingId) {
        this.reservationId = reservationId;
        this.customerId = customerId;
        this.roomId = roomId;
        this.buildingId = buildingId;
    }

    public Reservation() {
    }

    public String getReservationId() {
        return reservationId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public String getRoomId() {
        return roomId;
    }

    public String getBuildingId() {
        return buildingId;
    }

    public void setReservationId(String reservationId) {
        this.reservationId = reservationId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    public void setRoomId(String roomId) {
        this.roomId = roomId;
    }

    public void setBuildingId(String buildingId) {
        this.buildingId = buildingId;
    }
}
