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
    private DiChannel@(Flight, Geo)<Serializable> ch_flightGeo;
    private DiChannel@(Geo, Reservation)<Serializable> ch_geoReservation;
    private SymChannel@(Client, Reservation)<Serializable> ch_clientReservation;

    public ChorBookTravel(
        FlightService@Flight flightSvc,
        GeoService@Geo geoSvc,
        ReservationService@Reservation reservationSvc,

        SymChannel@(Client, Flight)<Serializable> ch_clientFlight,
        DiChannel@(Flight, Geo)<Serializable> ch_flightGeo,
        DiChannel@(Geo, Reservation)<Serializable> ch_geoReservation,
        SymChannel@(Client, Reservation)<Serializable> ch_clientReservation
    ) {
        this.flightSvc = flightSvc;
        this.geoSvc = geoSvc;
        this.reservationSvc = reservationSvc;

        this.ch_clientFlight = ch_clientFlight;
        this.ch_flightGeo = ch_flightGeo;
        this.ch_geoReservation = ch_geoReservation;
        this.ch_clientReservation = ch_clientReservation;
    }

    public BookTravelResult@Client bookTravel(BookTravelRequest@Client req) {
        BookTravelRequest@Flight req_flight = ch_clientFlight.<BookTravelRequest>com(req);
        BookTravelRequest@Reservation req_reservation = ch_clientReservation.<BookTravelRequest>com(req);

        Airport@Flight fromAirport = flightSvc.nearestAirport(req_flight.from);
        Airport@Flight toAirport = flightSvc.nearestAirport(req_flight.to);

        System@Flight.out.println("Flight service found airports: "@Flight + fromAirport.id + " and "@Flight + toAirport.id);

        Flight@Client outFlight_client = null@Client;
        Flight@Client homeFlight_client = null@Client;

        if (!fromAirport.id.equals(toAirport.id)) {
            ch_clientFlight.<Choice>select(Choice@Flight.FIRST);

            Flight@Flight outFlight = flightSvc.searchFlight(fromAirport.id, toAirport.id, req_flight.startDate).get(0@Flight);
            Flight@Flight homeFlight = flightSvc.searchFlight(toAirport.id, fromAirport.id, req_flight.endDate).get(0@Flight);

            flightSvc.bookFlight(outFlight.id);
            flightSvc.bookFlight(homeFlight.id);

            outFlight_client = ch_clientFlight.<Flight>com(outFlight);
            homeFlight_client = ch_clientFlight.<Flight>com(homeFlight);
        } else {
            ch_clientFlight.<Choice>select(Choice@Flight.SECOND);
        }

        Coordinate@Geo hotelNearestLoc = ch_flightGeo.<Coordinate>com(req_flight.to);
        String@Geo hotelID = geoSvc.nearbyHotelIDs(hotelNearestLoc).get(0@Geo);

        String@Reservation hotelID_reservation = ch_geoReservation.
                <SerializableString>com(new SerializableString@Geo(hotelID)).string;

        reservationSvc.makeReservation(
            "Customer Name"@Reservation,
            hotelID_reservation,
            req_reservation.startDate,
            req_reservation.endDate
        );

        String@Client hotelID_client = ch_clientReservation.
                <SerializableString>com(new SerializableString@Reservation(hotelID_reservation)).string;

        return new BookTravelResult@Client(outFlight_client, homeFlight_client, hotelID_client);
    }
}
