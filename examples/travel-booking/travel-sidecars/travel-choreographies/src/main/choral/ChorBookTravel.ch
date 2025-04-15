package dev.chords.travel.choreographies;

import choral.channels.DiChannel;
import choral.channels.SymChannel;

import java.io.Serializable;
import java.util.Optional;

public class ChorBookTravel@(Client, Flight, Geo, Reservation) {
    private FlightService@Flight flightSvc;
    private GeoService@Geo geoSvc;
    private ReservationService@Reservation reservationSvc;

    private SymChannel@(Client, Flight)<Serializable> ch_clientFlight;
    private DiChannel@(Client, Geo)<Serializable> ch_clientGeo;
    private DiChannel@(Geo, Reservation)<Serializable> ch_geoReservation;
    private SymChannel@(Client, Reservation)<Serializable> ch_clientReservation;

    public ChorBookTravel(
        FlightService@Flight flightSvc,
        GeoService@Geo geoSvc,
        ReservationService@Reservation reservationSvc,

        SymChannel@(Client, Flight)<Serializable> ch_clientFlight,
        DiChannel@(Client, Geo)<Serializable> ch_clientGeo,
        DiChannel@(Geo, Reservation)<Serializable> ch_geoReservation,
        SymChannel@(Client, Reservation)<Serializable> ch_clientReservation
    ) {
        this.flightSvc = flightSvc;
        this.geoSvc = geoSvc;
        this.reservationSvc = reservationSvc;

        this.ch_clientFlight = ch_clientFlight;
        this.ch_clientGeo = ch_clientGeo;
        this.ch_geoReservation = ch_geoReservation;
        this.ch_clientReservation = ch_clientReservation;
    }

    public BookTravelResult@Client bookTravel(BookTravelRequest@Client req) {
        System@Client.out.println("Sending to flight"@Client);
        System@Flight.out.println("Receiving from client"@Flight);
        BookTravelRequest@Flight req_flight = ch_clientFlight.<BookTravelRequest>com(req);

        System@Client.out.println("Sending to reservation"@Client);
        System@Reservation.out.println("Receiving from client"@Reservation);
        BookTravelRequest@Reservation req_reservation = ch_clientReservation.<BookTravelRequest>com(req);

        System@Client.out.println("Sending to geo"@Client);
        System@Geo.out.println("Receiving from client"@Geo);
        Coordinate@Geo hotelNearestLoc = ch_clientGeo.<Coordinate>com(req.to);

        System@Flight.out.println("Calculate nearest airports"@Flight);
        Airport@Flight fromAirport = flightSvc.nearestAirport(req_flight.from);
        Airport@Flight toAirport = flightSvc.nearestAirport(req_flight.to);

        System@Flight.out.println("Flight service found airports: "@Flight + fromAirport.id + " and "@Flight + toAirport.id);

        Flight@Client outFlight_client = null@Client;
        Flight@Client homeFlight_client = null@Client;

        if (!fromAirport.id.equals(toAirport.id)) {
            ch_clientFlight.<Choice>select(Choice@Flight.FIRST);

            System@Client.out.println("Airports are not equal"@Client);
            System@Flight.out.println("Airports are not equal"@Flight);

            Flight@Flight outFlight = flightSvc.searchFlight(fromAirport.id, toAirport.id, req_flight.startDate).get(0@Flight);
            Flight@Flight homeFlight = flightSvc.searchFlight(toAirport.id, fromAirport.id, req_flight.endDate).get(0@Flight);

            flightSvc.bookFlight(outFlight.id);
            flightSvc.bookFlight(homeFlight.id);

            outFlight_client = ch_clientFlight.<Flight>com(outFlight);
            homeFlight_client = ch_clientFlight.<Flight>com(homeFlight);

            System@Client.out.println("Received booked flights"@Client);
        } else {
            ch_clientFlight.<Choice>select(Choice@Flight.SECOND);

            System@Client.out.println("Airports are equal"@Client);
            System@Flight.out.println("Airports are equal"@Flight);
        }

        String@Geo hotelID = geoSvc.nearbyHotelIDs(hotelNearestLoc).get(0@Geo);
        System@Geo.out.println("Got nearby hotel ID: "@Geo + hotelID);

        System@Geo.out.println("Sending hotel ID to Reservation"@Geo);
        System@Reservation.out.println("Receiving hotel ID from Geo"@Reservation);
        String@Reservation hotelID_reservation = ch_geoReservation.<String>com(hotelID);

        System@Reservation.out.println("Making reservation"@Reservation);
        reservationSvc.makeReservation(
            "Customer Name"@Reservation,
            hotelID_reservation,
            req_reservation.startDate,
            req_reservation.endDate
        );

        System@Reservation.out.println("Sending reservation to Client"@Reservation);
        System@Client.out.println("Receiving reservation from Reservation"@Client);
        String@Client hotelID_client = ch_clientReservation.<String>com(hotelID_reservation);

        return new BookTravelResult@Client(outFlight_client, homeFlight_client, hotelID_client);
    }
}
