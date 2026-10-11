package com.selene.onestepflight.mobile;

import jakarta.persistence.*;

@Entity
@Table(name = "mobile_flight_watch")
public class FlightWatch {
    @Id @Column(length = 256) public String id;
    public String flightNumber;
    public String flightDate;
    public String departureAirport;
    public String arrivalAirport;
    public long dueAt;
    public long leaseUntil;
    public String leaseOwner;
    public int failures;
    public long snapshotVersion;
    @Column(columnDefinition = "text") public String facts;
    @Version public long rowVersion;
}
