package dev.chords.travel.choreographies;

import java.util.ArrayList;
import java.io.Serializable;
import choral.channels.DiChannel;
import choral.channels.SymChannel;

import choral.reactive.SessionContext;
import choral.reactive.ReactiveChannel;

public class ChorSearchHotels@(Client, Search, Reservation, Profile) {
    private SearchService@Search searchSvc;
    private ReservationService@Reservation reservationSvc;
    private ProfileService@Profile profileSvc;

    private DiChannel@(Client, Search)<Serializable> ch_clientSearch;
    private DiChannel@(Search, Reservation)<Serializable> ch_searchReservation;
    private DiChannel@(Reservation, Profile)<Serializable> ch_reservationProfile;
    private DiChannel@(Profile, Client)<Serializable> ch_profileClient;

    public ChorSearchHotels(
        SessionContext@Client clientCtx,
        SessionContext@Search searchCtx,
        SessionContext@Reservation reservationCtx,
        SessionContext@Profile profileCtx,

        SearchService@Search searchSvc,
        ReservationService@Reservation reservationSvc,
        ProfileService@Profile profileSvc
    ) {
        this.searchSvc = searchSvc;
        this.reservationSvc = reservationSvc;
        this.profileSvc = profileSvc;

        this.ch_clientSearch = ReactiveChannel@(Client, Search).connect(
            clientCtx, searchCtx,
            "CLIENT"@Search, "CHORAL_SEARCH"@Client
        );

        this.ch_searchReservation = ReactiveChannel@(Search, Reservation).connect(
                searchCtx, reservationCtx,
                "SEARCH"@Reservation, "CHORAL_RESERVATION"@Search
        );

        this.ch_reservationProfile = ReactiveChannel@(Reservation, Profile).connect(
                reservationCtx, profileCtx,
                "RESERVATION"@Profile, "CHORAL_PROFILE"@Reservation
        );

        this.ch_profileClient = ReactiveChannel@(Profile, Client).connect(
                profileCtx, clientCtx,
                "PROFILE"@Client, "CHORAL_CLIENT"@Profile
        );
    }

    public ArrayList@Client<Hotel> search(SearchHotelsRequest@Client req) {
        SearchHotelsRequest@Search req_search = ch_clientSearch.<SearchHotelsRequest>com(req);
        SearchHotelsRequest@Reservation req_res = ch_searchReservation.<SearchHotelsRequest>com(req_search);

        // search for best hotels
        SerializableList@Search<String> hotelIDs = searchSvc.nearby(
                req_search.lat, req_search.lon, req_search.inDate, req_search.outDate
        );

        SerializableList@Reservation<String> hotelIDs_res = ch_searchReservation.<SerializableList<String>>com(hotelIDs);

        SerializableList@Reservation<String> availableHotels = reservationSvc.checkAvailability(
                ""@Reservation, hotelIDs_res, req_res.inDate, req_res.outDate, 1@Reservation
        );

        SerializableList@Profile<String> availableHotels_profile = ch_reservationProfile.<SerializableList<String>>com(availableHotels);
        SerializableList@Profile<Hotel> hotels = profileSvc.getProfiles(availableHotels_profile, "en"@Profile);

        SerializableList@Client<Hotel> hotels_client = ch_profileClient.<SerializableList<Hotel>>com(hotels);
        return hotels_client.list;
    }
}
