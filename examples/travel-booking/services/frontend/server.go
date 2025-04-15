package frontend

import (
	"context"
	"embed"
	"encoding/json"
	"fmt"
	"go.opentelemetry.io/contrib/instrumentation/google.golang.org/grpc/otelgrpc"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/keepalive"
	"io/fs"
	"log/slog"
	"net/http"
	"strconv"
	"time"

	"github.com/delimitrou/DeathStarBench/tree/master/hotelReservation/dialer"
	"github.com/delimitrou/DeathStarBench/tree/master/hotelReservation/registry"
	attractions "github.com/delimitrou/DeathStarBench/tree/master/hotelReservation/services/attractions/proto"
	flights "github.com/delimitrou/DeathStarBench/tree/master/hotelReservation/services/flights/proto"
	geo "github.com/delimitrou/DeathStarBench/tree/master/hotelReservation/services/geo/proto"
	profile "github.com/delimitrou/DeathStarBench/tree/master/hotelReservation/services/profile/proto"
	recommendation "github.com/delimitrou/DeathStarBench/tree/master/hotelReservation/services/recommendation/proto"
	reservation "github.com/delimitrou/DeathStarBench/tree/master/hotelReservation/services/reservation/proto"
	review "github.com/delimitrou/DeathStarBench/tree/master/hotelReservation/services/review/proto"
	search "github.com/delimitrou/DeathStarBench/tree/master/hotelReservation/services/search/proto"
	user "github.com/delimitrou/DeathStarBench/tree/master/hotelReservation/services/user/proto"
	"github.com/delimitrou/DeathStarBench/tree/master/hotelReservation/tls"
	_ "github.com/mbobakov/grpc-consul-resolver"
	"go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp"
	"go.opentelemetry.io/otel"
	"google.golang.org/grpc"
)

var (
	//go:embed static/*
	content embed.FS
)

// Server implements frontend service
type Server struct {
	flightClient         flights.FlightsClient
	geoClient            geo.GeoClient
	searchClient         search.SearchClient
	profileClient        profile.ProfileClient
	recommendationClient recommendation.RecommendationClient
	userClient           user.UserClient
	reviewClient         review.ReviewClient
	attractionsClient    attractions.AttractionsClient
	reservationClient    reservation.ReservationClient

	KnativeDns string
	IpAddr     string
	ConsulAddr string
	Port       int
	Registry   *registry.Client

	FlightGrpcAddress string
}

// Run the server
func (s *Server) Run() error {
	ctx, span := otel.Tracer("frontend").Start(context.Background(), "SetupFrontend")

	if s.Port == 0 {
		return fmt.Errorf("Server port must be set")
	}

	slog.InfoContext(ctx, "Loading static content...")
	staticContent, err := fs.Sub(content, "static")
	if err != nil {
		slog.ErrorContext(ctx, "failed to load static content", slog.Any("error", err))
		return err
	}

	slog.InfoContext(ctx, "Initializing gRPC clients...")

	if err := s.initFlightClient(ctx, s.FlightGrpcAddress /*"srv-flights"*/); err != nil {
		slog.ErrorContext(ctx, "failed to initialize FlightClient", slog.Any("error", err))
		return err
	}

	if err := s.initGeoClient(ctx, "srv-geo"); err != nil {
		slog.ErrorContext(ctx, "failed to initialize GeoClient", slog.Any("error", err))
		return err
	}

	if err := s.initSearchClient(ctx, "srv-search"); err != nil {
		slog.ErrorContext(ctx, "failed to initialize SearchClient", slog.Any("error", err))
		return err
	}

	if err := s.initProfileClient(ctx, "srv-profile"); err != nil {
		slog.ErrorContext(ctx, "failed to initialize ProfileClient", slog.Any("error", err))
		return err
	}

	if err := s.initRecommendationClient(ctx, "srv-recommendation"); err != nil {
		slog.ErrorContext(ctx, "failed to initialize RecommendationClient", slog.Any("error", err))
		return err
	}

	if err := s.initUserClient(ctx, "srv-user"); err != nil {
		slog.ErrorContext(ctx, "failed to initialize UserClient", slog.Any("error", err))
		return err
	}

	if err := s.initReservation(ctx, "srv-reservation"); err != nil {
		slog.ErrorContext(ctx, "failed to initialize Reservation", slog.Any("error", err))
		return err
	}

	if err := s.initReviewClient(ctx, "srv-review"); err != nil {
		slog.ErrorContext(ctx, "failed to initialize ReviewClient", slog.Any("error", err))
		return err
	}

	if err := s.initAttractionsClient(ctx, "srv-attractions"); err != nil {
		slog.ErrorContext(ctx, "failed to initialize AttractionsClient", slog.Any("error", err))
		return err
	}

	slog.InfoContext(ctx, "Successful")

	slog.DebugContext(ctx, "frontend before mux")
	// mux := tracing.NewServeMux(s.Tracer)

	mux := http.NewServeMux()

	// handleFunc is a replacement for mux.HandleFunc
	// which enriches the handler's HTTP instrumentation with the pattern as the http.route.
	handleFunc := func(pattern string, handlerFunc http.Handler) {
		slog.DebugContext(ctx, fmt.Sprintf("Injecting otelhttp into route: %s", pattern))
		// Configure the "http.route" for the HTTP instrumentation.
		handler := otelhttp.NewHandler(
			otelhttp.WithRouteTag(pattern, handlerFunc),
			"",
			otelhttp.WithSpanNameFormatter(func(operation string, r *http.Request) string {
				return "HTTP " + r.Method + " " + pattern
			}),
		)
		mux.Handle(pattern, handler)
	}

	handleFunc("/", http.FileServer(http.FS(staticContent)))
	handleFunc("/bookTravel", http.HandlerFunc(s.bookTravelHandler))
	handleFunc("/hotels", http.HandlerFunc(s.searchHandler))
	handleFunc("/recommendations", http.HandlerFunc(s.recommendHandler))
	handleFunc("/user", http.HandlerFunc(s.userHandler))
	handleFunc("/review", http.HandlerFunc(s.reviewHandler))
	handleFunc("/restaurants", http.HandlerFunc(s.restaurantHandler))
	handleFunc("/museums", http.HandlerFunc(s.museumHandler))
	handleFunc("/cinema", http.HandlerFunc(s.cinemaHandler))
	handleFunc("/reservation", http.HandlerFunc(s.reservationHandler))

	slog.DebugContext(ctx, "frontend starts serving")

	span.End() // setup frontend span

	tlsconfig := tls.GetHttpsOpt()
	srv := &http.Server{
		Addr:    fmt.Sprintf(":%d", s.Port),
		Handler: mux,
	}
	if tlsconfig != nil {
		slog.Info("Serving https")
		srv.TLSConfig = tlsconfig
		return srv.ListenAndServeTLS("x509/server_cert.pem", "x509/server_key.pem")
	} else {
		slog.Info("Serving http")
		return srv.ListenAndServe()
	}
}

func (s *Server) initFlightClient(ctx context.Context, url string) error {

	slog.InfoContext(ctx, "Initializing flight client", slog.String("url", url))

	dialopts := []grpc.DialOption{
		grpc.WithKeepaliveParams(keepalive.ClientParameters{
			Timeout:             120 * time.Second,
			PermitWithoutStream: true,
		}),
		grpc.WithTransportCredentials(insecure.NewCredentials()),
		grpc.WithStatsHandler(otelgrpc.NewClientHandler()),
	}

	conn, err := grpc.NewClient(url, dialopts...)
	if err != nil {
		return fmt.Errorf("failed to dial %s: %v", url, err)
	}

	s.flightClient = flights.NewFlightsClient(conn)

	return nil

	//slog.InfoContext(ctx, "initializing Flight client", slog.String("grpc_connection_name", name))
	//conn, err := s.getGprcConn(ctx, name)
	//if err != nil {
	//	return fmt.Errorf("error dialing %s: %v", name, err)
	//}
	//s.flightClient = flights.NewFlightsClient(conn)
	//return nil
}

func (s *Server) initGeoClient(ctx context.Context, name string) error {
	slog.InfoContext(ctx, "initializing Geo client", slog.String("grpc_connection_name", name))
	conn, err := s.getGprcConn(ctx, name)
	if err != nil {
		return fmt.Errorf("error dialing %s: %v", name, err)
	}
	s.geoClient = geo.NewGeoClient(conn)
	return nil
}

func (s *Server) initSearchClient(ctx context.Context, name string) error {
	slog.InfoContext(ctx, "initializing Search client", slog.String("grpc_connection_name", name))
	conn, err := s.getGprcConn(ctx, name)
	if err != nil {
		return fmt.Errorf("error dialing %s: %v", name, err)
	}
	s.searchClient = search.NewSearchClient(conn)
	return nil
}

func (s *Server) initReviewClient(ctx context.Context, name string) error {
	slog.InfoContext(ctx, "initializing Review client", slog.String("grpc_connection_name", name))
	conn, err := s.getGprcConn(ctx, name, dialer.WithBalancer(s.Registry.Client))
	if err != nil {
		return fmt.Errorf("error dialing %s: %v", name, err)
	}
	s.reviewClient = review.NewReviewClient(conn)
	return nil
}

func (s *Server) initAttractionsClient(ctx context.Context, name string) error {
	slog.InfoContext(ctx, "initializing Attractions client", slog.String("grpc_connection_name", name))
	conn, err := s.getGprcConn(ctx, name, dialer.WithBalancer(s.Registry.Client))
	if err != nil {
		return fmt.Errorf("error dialing %s: %v", name, err)
	}
	s.attractionsClient = attractions.NewAttractionsClient(conn)
	return nil
}

func (s *Server) initProfileClient(ctx context.Context, name string) error {
	slog.InfoContext(ctx, "initializing Profile client", slog.String("grpc_connection_name", name))
	conn, err := s.getGprcConn(ctx, name)
	if err != nil {
		return fmt.Errorf("error dialing %s: %v", name, err)
	}
	s.profileClient = profile.NewProfileClient(conn)
	return nil
}

func (s *Server) initRecommendationClient(ctx context.Context, name string) error {
	slog.InfoContext(ctx, "initializing Recommendation client", slog.String("grpc_connection_name", name))
	conn, err := s.getGprcConn(ctx, name)
	if err != nil {
		return fmt.Errorf("error dialing %s: %v", name, err)
	}
	s.recommendationClient = recommendation.NewRecommendationClient(conn)
	return nil
}

func (s *Server) initUserClient(ctx context.Context, name string) error {
	slog.InfoContext(ctx, "initializing User client", slog.String("grpc_connection_name", name))
	conn, err := s.getGprcConn(ctx, name)
	if err != nil {
		return fmt.Errorf("error dialing %s: %v", name, err)
	}
	s.userClient = user.NewUserClient(conn)
	return nil
}

func (s *Server) initReservation(ctx context.Context, name string) error {
	slog.InfoContext(ctx, "initializing Reservation client", slog.String("grpc_connection_name", name))
	conn, err := s.getGprcConn(ctx, name)
	if err != nil {
		return fmt.Errorf("error dialing %s: %v", name, err)
	}
	s.reservationClient = reservation.NewReservationClient(conn)
	return nil
}

func (s *Server) getGprcConn(ctx context.Context, name string, dialOptions ...dialer.DialOption) (*grpc.ClientConn, error) {
	var address string
	if s.KnativeDns != "" {
		address = fmt.Sprintf("consul://%s/%s.%s", s.ConsulAddr, name, s.KnativeDns)
	} else {
		address = fmt.Sprintf("consul://%s/%s", s.ConsulAddr, name)
	}

	slog.InfoContext(ctx, fmt.Sprintf("get gRPC conn to %s", address), slog.String("name", name), slog.String("KnativeDns", s.KnativeDns), slog.String("address", address), slog.Any("dial_options", dialOptions))
	return dialer.Dial(address, dialOptions...)
}

func (s *Server) bookTravelHandler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Access-Control-Allow-Origin", "*")
	ctx := r.Context()

	slog.DebugContext(ctx, "starts searchHandler")

	sFromLat, sFromLon := r.URL.Query().Get("fromLat"), r.URL.Query().Get("fromLon")
	sToLat, sToLon := r.URL.Query().Get("toLat"), r.URL.Query().Get("toLon")

	startDate, endDate := r.URL.Query().Get("startDate"), r.URL.Query().Get("endDate")

	if sFromLat == "" || sFromLon == "" || sToLat == "" || sToLon == "" || startDate == "" || endDate == "" {
		message := "Please specify all required params: fromLat, fromLon, toLat, toLon, startDate, endDate"
		slog.WarnContext(ctx, "Http request failed", slog.String("response_message", message))
		http.Error(w, message, http.StatusBadRequest)
		return
	}

	fromLat, err := strconv.ParseFloat(sFromLat, 64)
	if err != nil {
		slog.WarnContext(ctx, "Http request failed", slog.String("response_message", err.Error()))
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	fromLon, err := strconv.ParseFloat(sFromLon, 64)
	if err != nil {
		slog.WarnContext(ctx, "Http request failed", slog.String("response_message", err.Error()))
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	toLat, err := strconv.ParseFloat(sToLat, 64)
	if err != nil {
		slog.WarnContext(ctx, "Http request failed", slog.String("response_message", err.Error()))
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	toLon, err := strconv.ParseFloat(sToLon, 64)
	if err != nil {
		slog.WarnContext(ctx, "Http request failed", slog.String("response_message", err.Error()))
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	fromAirport, err := s.flightClient.NearestAirport(ctx,
		&flights.AirportSearchRequest{Lat: fromLat, Lon: fromLon})
	if err != nil {
		slog.WarnContext(ctx, "Http request failed", slog.String("response_message", err.Error()))
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	toAirport, err := s.flightClient.NearestAirport(ctx,
		&flights.AirportSearchRequest{Lat: toLat, Lon: toLon})
	if err != nil {
		slog.WarnContext(ctx, "Http request failed", slog.String("response_message", err.Error()))
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	slog.DebugContext(ctx, fmt.Sprintf("Found airports: %s and %s", fromAirport.Id, toAirport.Id))

	var outFlight *flights.Flight
	var homeFlight *flights.Flight

	if fromAirport.Id != toAirport.Id {
		outFlightSearch, err := s.flightClient.SearchFlights(ctx, &flights.SearchRequest{FromAirport: fromAirport.Id, ToAirport: toAirport.Id})
		if err != nil {
			slog.WarnContext(ctx, "Http request failed", slog.String("response_message", err.Error()))
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}

		outFlight = outFlightSearch.GetFlights()[0]

		homeFlightSearch, err := s.flightClient.SearchFlights(ctx, &flights.SearchRequest{FromAirport: toAirport.Id, ToAirport: fromAirport.Id})
		if err != nil {
			slog.WarnContext(ctx, "Http request failed", slog.String("response_message", err.Error()))
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}

		homeFlight = homeFlightSearch.GetFlights()[0]

		s.flightClient.BookFlight(ctx, &flights.BookingRequest{Id: outFlight.Id})
		s.flightClient.BookFlight(ctx, &flights.BookingRequest{Id: homeFlight.Id})
	}

	nearbyHotels, err := s.geoClient.Nearby(ctx, &geo.Request{Lat: float32(toLat), Lon: float32(toLon)})
	if err != nil {
		slog.WarnContext(ctx, "Http request failed", slog.String("response_message", err.Error()))
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	hotelId := nearbyHotels.HotelIds[0]

	reservation, err := s.reservationClient.MakeReservation(ctx, &reservation.Request{
		CustomerName: "Customer Name",
		HotelId:      []string{hotelId},
		InDate:       startDate,
		OutDate:      endDate,
		RoomNumber:   1,
	})
	if err != nil {
		slog.WarnContext(ctx, "Http request failed", slog.String("response_message", err.Error()))
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	json.NewEncoder(w).Encode(map[string]any{
		"outFlight":  outFlight,
		"homeFlight": homeFlight,
		"hotelID":    reservation.HotelId[0],
	})
}

func (s *Server) searchHandler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Access-Control-Allow-Origin", "*")
	ctx := r.Context()

	slog.DebugContext(ctx, "starts searchHandler")

	// in/out dates from query params
	inDate, outDate := r.URL.Query().Get("inDate"), r.URL.Query().Get("outDate")
	if inDate == "" || outDate == "" {
		slog.WarnContext(ctx, "Http request failed", slog.String("response_message", "Please specify inDate/outDate params"))
		http.Error(w, "Please specify inDate/outDate params", http.StatusBadRequest)
		return
	}

	// lan/lon from query params
	sLat, sLon := r.URL.Query().Get("lat"), r.URL.Query().Get("lon")
	if sLat == "" || sLon == "" {
		slog.WarnContext(ctx, "Http request failed", slog.String("response_message", "Please specify location params"))
		http.Error(w, "Please specify location params", http.StatusBadRequest)
		return
	}

	Lat, _ := strconv.ParseFloat(sLat, 32)
	lat := float32(Lat)
	Lon, _ := strconv.ParseFloat(sLon, 32)
	lon := float32(Lon)

	slog.DebugContext(ctx, "starts searchHandler querying downstream")

	slog.DebugContext(ctx, fmt.Sprintf("SEARCH [lat: %v, lon: %v, inDate: %v, outDate: %v", lat, lon, inDate, outDate))
	// search for best hotels
	searchResp, err := s.searchClient.Nearby(ctx, &search.NearbyRequest{
		Lat:     lat,
		Lon:     lon,
		InDate:  inDate,
		OutDate: outDate,
	})
	if err != nil {
		slog.WarnContext(ctx, "Http request failed", slog.String("response_message", err.Error()))
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	slog.DebugContext(ctx, "SearchHandler gets searchResp")
	//for _, hid := range searchResp.HotelIds {
	//	slog.DebugContext(ctx, "Search Handler hotelId = %s", hid)
	//}

	// grab locale from query params or default to en
	locale := r.URL.Query().Get("locale")
	if locale == "" {
		locale = "en"
	}

	reservationResp, err := s.reservationClient.CheckAvailability(ctx, &reservation.Request{
		CustomerName: "",
		HotelId:      searchResp.HotelIds,
		InDate:       inDate,
		OutDate:      outDate,
		RoomNumber:   1,
	})
	if err != nil {
		slog.ErrorContext(ctx, "SearchHandler CheckAvailability failed", slog.Any("error", err))
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	slog.DebugContext(ctx, "searchHandler gets reserveResp")
	slog.DebugContext(ctx, "searchHandler gets reserveResp.HotelId = %s", reservationResp.HotelId)

	// hotel profiles
	profileResp, err := s.profileClient.GetProfiles(ctx, &profile.Request{
		HotelIds: reservationResp.HotelId,
		Locale:   locale,
	})
	if err != nil {
		slog.ErrorContext(ctx, "SearchHandler GetProfiles failed", slog.Any("error", err))
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	slog.DebugContext(ctx, "searchHandler gets profileResp")

	json.NewEncoder(w).Encode(geoJSONResponse(profileResp.Hotels))
}

func (s *Server) recommendHandler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Access-Control-Allow-Origin", "*")
	ctx := r.Context()

	sLat, sLon := r.URL.Query().Get("lat"), r.URL.Query().Get("lon")
	if sLat == "" || sLon == "" {
		slog.WarnContext(ctx, "Http request failed", slog.String("response_message", "Please specify location params"))
		http.Error(w, "Please specify location params", http.StatusBadRequest)
		return
	}
	Lat, _ := strconv.ParseFloat(sLat, 64)
	lat := float64(Lat)
	Lon, _ := strconv.ParseFloat(sLon, 64)
	lon := float64(Lon)

	require := r.URL.Query().Get("require")
	if require != "dis" && require != "rate" && require != "price" {
		slog.WarnContext(ctx, "Http request failed", slog.String("response_message", "Please specify require params"))
		http.Error(w, "Please specify require params", http.StatusBadRequest)
		return
	}

	// recommend hotels
	recResp, err := s.recommendationClient.GetRecommendations(ctx, &recommendation.Request{
		Require: require,
		Lat:     float64(lat),
		Lon:     float64(lon),
	})
	if err != nil {
		slog.WarnContext(ctx, "Http request failed", slog.String("response_message", err.Error()))
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	// grab locale from query params or default to en
	locale := r.URL.Query().Get("locale")
	if locale == "" {
		locale = "en"
	}

	// hotel profiles
	profileResp, err := s.profileClient.GetProfiles(ctx, &profile.Request{
		HotelIds: recResp.HotelIds,
		Locale:   locale,
	})
	if err != nil {
		slog.WarnContext(ctx, "Http request failed", slog.String("response_message", err.Error()))
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	json.NewEncoder(w).Encode(geoJSONResponse(profileResp.Hotels))
}

func (s *Server) reviewHandler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Access-Control-Allow-Origin", "*")
	ctx := r.Context()

	username, password := r.URL.Query().Get("username"), r.URL.Query().Get("password")
	if username == "" || password == "" {
		slog.WarnContext(ctx, "Http request failed", slog.String("response_message", "Please specify username and password"))
		http.Error(w, "Please specify username and password", http.StatusBadRequest)
		return
	}

	// Check username and password
	recResp, err := s.userClient.CheckUser(ctx, &user.Request{
		Username: username,
		Password: password,
	})
	if err != nil {
		slog.WarnContext(ctx, "Http request failed", slog.String("response_message", err.Error()))
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	str := "Logged-in successfully!"
	if !recResp.Correct {
		slog.WarnContext(ctx, "Http request failed", slog.String("response_message", "Failed. Please check your username and password."))
		http.Error(w, "Failed. Please check your username and password.", http.StatusForbidden)
		return
	}

	hotelId := r.URL.Query().Get("hotelId")
	if hotelId == "" {
		slog.WarnContext(ctx, "Http request failed", slog.String("response_message", "Please specify hotelId params"))
		http.Error(w, "Please specify hotelId params", http.StatusBadRequest)
		return
	}

	revInput := review.Request{HotelId: hotelId}

	slog.DebugContext(ctx, "Review handler before get reviews")

	revResp, err := s.reviewClient.GetReviews(ctx, &revInput)
	if err != nil {
		slog.ErrorContext(ctx, "Http request failed", slog.String("response_message", "Failed to get reviews"), slog.Any("error", err))
		http.Error(w, fmt.Sprintf("Failed to get reviews: %v", err), http.StatusBadRequest)
		return
	}

	slog.DebugContext(ctx, "Review handler before crash", slog.Any("review_response", revResp), slog.Any("review_response_error", err))

	str = "Have reviews = " + strconv.Itoa(len(revResp.Reviews))
	if len(revResp.Reviews) == 0 {
		str = "Failed. No Reviews. "
	}

	slog.DebugContext(ctx, "Review handler after crash")

	res := map[string]interface{}{
		"message": str,
	}

	json.NewEncoder(w).Encode(res)
}

func (s *Server) restaurantHandler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Access-Control-Allow-Origin", "*")
	ctx := r.Context()

	username, password := r.URL.Query().Get("username"), r.URL.Query().Get("password")
	if username == "" || password == "" {
		slog.WarnContext(ctx, "Http request failed", slog.String("response_message", "Please specify username and password"))
		http.Error(w, "Please specify username and password", http.StatusBadRequest)
		return
	}

	// Check username and password
	recResp, err := s.userClient.CheckUser(ctx, &user.Request{
		Username: username,
		Password: password,
	})
	if err != nil {
		slog.WarnContext(ctx, "Http request failed", slog.String("response_message", err.Error()))
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	if !recResp.Correct {
		slog.WarnContext(ctx, "Http request failed", slog.String("response_message", "Failed. Please check your username and password."))
		http.Error(w, "Failed. Please check your username and password.", http.StatusBadRequest)
		return
	}

	hotelId := r.URL.Query().Get("hotelId")
	if hotelId == "" {
		slog.WarnContext(ctx, "Http request failed", slog.String("response_message", "Please specify hotelId params"))
		http.Error(w, "Please specify hotelId params", http.StatusBadRequest)
		return
	}

	revInput := attractions.Request{HotelId: hotelId}

	revResp, err := s.attractionsClient.NearbyRest(ctx, &revInput)
	if err != nil {
		slog.WarnContext(ctx, "Http request failed", slog.String("response_message", err.Error()))
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	str := "Have restaurants = " + strconv.Itoa(len(revResp.AttractionIds))
	if len(revResp.AttractionIds) == 0 {
		str = "Failed. No Restaurants. "
	}

	res := map[string]interface{}{
		"message": str,
	}

	json.NewEncoder(w).Encode(res)
}

func (s *Server) museumHandler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Access-Control-Allow-Origin", "*")
	ctx := r.Context()

	username, password := r.URL.Query().Get("username"), r.URL.Query().Get("password")
	if username == "" || password == "" {
		slog.WarnContext(ctx, "Http request failed", slog.String("response_message", "Please specify username and password"))
		http.Error(w, "Please specify username and password", http.StatusBadRequest)
		return
	}

	// Check username and password
	recResp, err := s.userClient.CheckUser(ctx, &user.Request{
		Username: username,
		Password: password,
	})
	if err != nil {
		slog.WarnContext(ctx, "Http request failed", slog.String("response_message", err.Error()))
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	if !recResp.Correct {
		slog.WarnContext(ctx, "Http request failed", slog.String("response_message", "Failed. Please check your username and password."))
		http.Error(w, "Failed. Please check your username and password.", http.StatusForbidden)
		return
	}

	hotelId := r.URL.Query().Get("hotelId")
	if hotelId == "" {
		slog.WarnContext(ctx, "Http request failed", slog.String("response_message", "Please specify hotelId params"))
		http.Error(w, "Please specify hotelId params", http.StatusBadRequest)
		return
	}

	revInput := attractions.Request{HotelId: hotelId}

	revResp, err := s.attractionsClient.NearbyMus(ctx, &revInput)
	if err != nil {
		slog.WarnContext(ctx, "Http request failed", slog.String("response_message", err.Error()))
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	str := "Have museums = " + strconv.Itoa(len(revResp.AttractionIds))
	if len(revResp.AttractionIds) == 0 {
		str = "Failed. No Museums. "
	}

	if err != nil {
		slog.WarnContext(ctx, "Http request failed", slog.String("response_message", err.Error()))
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	res := map[string]interface{}{
		"message": str,
	}

	json.NewEncoder(w).Encode(res)
}

func (s *Server) cinemaHandler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Access-Control-Allow-Origin", "*")
	ctx := r.Context()

	username, password := r.URL.Query().Get("username"), r.URL.Query().Get("password")
	if username == "" || password == "" {
		slog.WarnContext(ctx, "Http request failed", slog.String("response_message", "Please specify username and password"))
		http.Error(w, "Please specify username and password", http.StatusBadRequest)
		return
	}

	// Check username and password
	recResp, err := s.userClient.CheckUser(ctx, &user.Request{
		Username: username,
		Password: password,
	})
	if err != nil {
		slog.WarnContext(ctx, "Http request failed", slog.String("response_message", err.Error()))
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	if !recResp.Correct {
		slog.WarnContext(ctx, "Http request failed", slog.String("response_message", "Failed. Please check your username and password."))
		http.Error(w, "Failed. Please check your username and password.", http.StatusForbidden)
		return
	}

	hotelId := r.URL.Query().Get("hotelId")
	if hotelId == "" {
		slog.WarnContext(ctx, "Http request failed", slog.String("response_message", "Please specify hotelId params"))
		http.Error(w, "Please specify hotelId params", http.StatusBadRequest)
		return
	}

	revInput := attractions.Request{HotelId: hotelId}

	revResp, err := s.attractionsClient.NearbyCinema(ctx, &revInput)
	if err != nil {
		slog.WarnContext(ctx, "Http request failed", slog.String("response_message", err.Error()))
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	str := "Have cinemas = " + strconv.Itoa(len(revResp.AttractionIds))
	if len(revResp.AttractionIds) == 0 {
		str = "Failed. No Cinemas. "
	}

	res := map[string]interface{}{
		"message": str,
	}

	json.NewEncoder(w).Encode(res)
}

func (s *Server) userHandler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Access-Control-Allow-Origin", "*")
	ctx := r.Context()

	username, password := r.URL.Query().Get("username"), r.URL.Query().Get("password")
	if username == "" || password == "" {
		slog.WarnContext(ctx, "Http request failed", slog.String("response_message", "Please specify username and password"))
		http.Error(w, "Please specify username and password", http.StatusBadRequest)
		return
	}

	// Check username and password
	recResp, err := s.userClient.CheckUser(ctx, &user.Request{
		Username: username,
		Password: password,
	})
	if err != nil {
		slog.WarnContext(ctx, "Http request failed", slog.String("response_message", err.Error()))
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	if !recResp.Correct {
		slog.WarnContext(ctx, "Http request failed", slog.String("response_message", "Failed. Please check your username and password."))
		http.Error(w, "Failed. Please check your username and password.", http.StatusForbidden)
		return
	}

	res := map[string]interface{}{
		"message": "Login successfully!",
	}

	json.NewEncoder(w).Encode(res)
}

func (s *Server) reservationHandler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Access-Control-Allow-Origin", "*")
	ctx := r.Context()

	inDate, outDate := r.URL.Query().Get("inDate"), r.URL.Query().Get("outDate")
	if inDate == "" || outDate == "" {
		slog.WarnContext(ctx, "Http request failed", slog.String("response_message", "Please specify inDate/outDate params"))
		http.Error(w, "Please specify inDate/outDate params", http.StatusBadRequest)
		return
	}

	if !checkDateFormat(inDate) || !checkDateFormat(outDate) {
		slog.WarnContext(ctx, "Http request failed", slog.String("response_message", "Please check inDate/outDate format (YYYY-MM-DD)"))
		http.Error(w, "Please check inDate/outDate format (YYYY-MM-DD)", http.StatusBadRequest)
		return
	}

	hotelId := r.URL.Query().Get("hotelId")
	if hotelId == "" {
		slog.WarnContext(ctx, "Http request failed", slog.String("response_message", "Please specify hotelId params"))
		http.Error(w, "Please specify hotelId params", http.StatusBadRequest)
		return
	}

	customerName := r.URL.Query().Get("customerName")
	if customerName == "" {
		slog.WarnContext(ctx, "Http request failed", slog.String("response_message", "Please specify customerName params"))
		http.Error(w, "Please specify customerName params", http.StatusBadRequest)
		return
	}

	username, password := r.URL.Query().Get("username"), r.URL.Query().Get("password")
	if username == "" || password == "" {
		slog.WarnContext(ctx, "Http request failed", slog.String("response_message", "Please specify username and password"))
		http.Error(w, "Please specify username and password", http.StatusBadRequest)
		return
	}

	numberOfRoom := 0
	num := r.URL.Query().Get("number")
	if num != "" {
		numberOfRoom, _ = strconv.Atoi(num)
	}

	// Check username and password
	recResp, err := s.userClient.CheckUser(ctx, &user.Request{
		Username: username,
		Password: password,
	})
	if err != nil {
		slog.WarnContext(ctx, "Http request failed", slog.String("response_message", err.Error()))
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	if !recResp.Correct {
		slog.WarnContext(ctx, "Http request failed", slog.String("response_message", "Failed. Please check your username and password."))
		http.Error(w, "Failed. Please check your username and password.", http.StatusBadRequest)
		return
	}

	// Make reservation
	resResp, err := s.reservationClient.MakeReservation(ctx, &reservation.Request{
		CustomerName: customerName,
		HotelId:      []string{hotelId},
		InDate:       inDate,
		OutDate:      outDate,
		RoomNumber:   int32(numberOfRoom),
	})
	if err != nil {
		slog.WarnContext(ctx, "Http request failed", slog.String("response_message", err.Error()))
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	str := "Reserve successfully!"
	if len(resResp.HotelId) == 0 {
		str = "Failed. Already reserved. "
	}

	res := map[string]interface{}{
		"message": str,
	}

	json.NewEncoder(w).Encode(res)
}

// return a geoJSON response that allows google map to plot points directly on map
// https://developers.google.com/maps/documentation/javascript/datalayer#sample_geojson
func geoJSONResponse(hs []*profile.Hotel) map[string]interface{} {
	fs := []interface{}{}

	for _, h := range hs {
		fs = append(fs, map[string]interface{}{
			"type": "Feature",
			"id":   h.Id,
			"properties": map[string]string{
				"name":         h.Name,
				"phone_number": h.PhoneNumber,
			},
			"geometry": map[string]interface{}{
				"type": "Point",
				"coordinates": []float32{
					h.Address.Lon,
					h.Address.Lat,
				},
			},
		})
	}

	return map[string]interface{}{
		"type":     "FeatureCollection",
		"features": fs,
	}
}

func checkDateFormat(date string) bool {
	if len(date) != 10 {
		return false
	}
	for i := 0; i < 10; i++ {
		if i == 4 || i == 7 {
			if date[i] != '-' {
				return false
			}
		} else {
			if date[i] < '0' || date[i] > '9' {
				return false
			}
		}
	}
	return true
}
