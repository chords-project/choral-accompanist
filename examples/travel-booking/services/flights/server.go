package flights

import (
	"context"
	"errors"
	"fmt"
	"log"
	"log/slog"
	"net"
	"time"

	pb "github.com/delimitrou/DeathStarBench/tree/master/hotelReservation/services/flights/proto"
	"github.com/delimitrou/DeathStarBench/tree/master/hotelReservation/tls"
	"github.com/google/uuid"
	"go.mongodb.org/mongo-driver/bson"
	"go.mongodb.org/mongo-driver/mongo"
	"go.opentelemetry.io/contrib/instrumentation/google.golang.org/grpc/otelgrpc"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/trace"
	"google.golang.org/grpc"
	"google.golang.org/grpc/keepalive"
)

const name = "srv-flights"

// Server implements the flights service
type Server struct {
	pb.UnimplementedFlightsServer

	uuid   string
	tracer trace.Tracer

	Port        int
	IpAddr      string
	MongoClient *mongo.Client
	//Registry    *registry.Client
}

// Run starts the server
func (s *Server) Run() error {
	s.tracer = otel.Tracer("flights")
	ctx, span := s.tracer.Start(context.Background(), "SetupFlights")

	if s.Port == 0 {
		return fmt.Errorf("server port must be set")
	}

	s.uuid = uuid.New().String()

	slog.InfoContext(ctx, fmt.Sprintf("Setup flights with s.IpAddr=%s, port=%d", s.IpAddr, s.Port))

	opts := []grpc.ServerOption{
		grpc.KeepaliveParams(keepalive.ServerParameters{
			Timeout: 120 * time.Second,
		}),
		grpc.KeepaliveEnforcementPolicy(keepalive.EnforcementPolicy{
			PermitWithoutStream: true,
		}),
		grpc.StatsHandler(otelgrpc.NewServerHandler()),
	}

	if tlsopt := tls.GetServerOpt(); tlsopt != nil {
		opts = append(opts, tlsopt)
	}

	srv := grpc.NewServer(opts...)

	pb.RegisterFlightsServer(srv, s)

	lis, err := net.Listen("tcp", fmt.Sprintf(":%d", s.Port))
	if err != nil {

		log.Fatalf("failed to configure listener: %v", err)
	}

	//err = s.Registry.Register(ctx, name, s.uuid, s.IpAddr, s.Port)
	//if err != nil {
	//	return fmt.Errorf("failed register: %v", err)
	//}
	//slog.InfoContext(ctx, "Successfully registered in consul")

	span.End()

	return srv.Serve(lis)
}

// Shutdown cleans up any processes
func (s *Server) Shutdown() {
	//s.Registry.Deregister(s.uuid)
}

type Airport struct {
	Id   string  `bson:"id"`
	Name string  `bson:"name"`
	Lat  float64 `bson:"lat"`
	Lon  float64 `bson:"lon"`
}

type Flight struct {
	Id            string `bson:"id"`
	FromAirport   string `bson:"fromAirport"`
	ToAirport     string `bson:"toAirport"`
	DepartureTime string `bson:"departureTime"`
	ArrivalTime   string `bson:"arrivalTime"`
}

type Booking struct {
	// The ID of the flight to book
	Id string `bson:"id"`
}

func (s *Server) NearestAirport(ctx context.Context, req *pb.AirportSearchRequest) (*pb.Airport, error) {
	slog.Debug("Get nearest airport", slog.Float64("lat", req.Lat), slog.Float64("lon", req.Lon))

	c := s.MongoClient.Database("flights-db").Collection("airports")

	cursor, err := c.Aggregate(ctx, mongo.Pipeline{
		bson.D{{
			Key: "$addFields",
			Value: bson.M{
				"dist": bson.M{
					"$add": bson.A{
						bson.M{"$abs": bson.M{"$subtract": bson.A{"$lat", req.Lat}}},
						bson.M{"$abs": bson.M{"$subtract": bson.A{"$lon", req.Lon}}},
					},
				},
			},
		}}, bson.D{{
			Key:   "$sort",
			Value: bson.M{"dist": 1},
		}}, bson.D{{
			Key:   "$limit",
			Value: 1,
		}},
	})

	if err != nil {
		slog.ErrorContext(ctx, "failed to get nearest airport", slog.Any("error", err))
		return nil, err
	}

	if !cursor.Next(ctx) {
		slog.ErrorContext(ctx, "found no airports")
		return nil, errors.New("found no airports")
	}

	var airport Airport
	if err := cursor.Decode(&airport); err != nil {
		slog.ErrorContext(ctx, "failed to decode nearest airport", slog.Any("error", err))
		return nil, err
	}

	return &pb.Airport{
		Id:   airport.Id,
		Name: airport.Name,
		Lat:  airport.Lat,
		Lon:  airport.Lon,
	}, nil
}

func (s *Server) GetAirport(ctx context.Context, req *pb.AirportRequest) (*pb.Airport, error) {

	slog.InfoContext(ctx, "get airport", slog.String("id", req.Id))

	c := s.MongoClient.Database("flights-db").Collection("airports")

	var airport Airport
	err := c.FindOne(ctx, bson.D{{"id", req.Id}}).Decode(&airport)
	if err != nil {
		return nil, fmt.Errorf("failed to get airport: %v", airport)
	}

	return &pb.Airport{
		Id:   airport.Id,
		Name: airport.Name,
		Lat:  airport.Lat,
		Lon:  airport.Lon,
	}, nil
}

func (s *Server) SearchFlights(ctx context.Context, req *pb.SearchRequest) (*pb.SearchResult, error) {

	slog.InfoContext(ctx, "searching for flights", slog.String("from_airport", req.FromAirport), slog.String("to_airport", req.ToAirport), slog.String("departure_date", req.DepartureDate))

	c := s.MongoClient.Database("flights-db").Collection("flights")

	cur, err := c.Find(ctx, bson.D{{"fromAirport", req.FromAirport}, {"toAirport", req.ToAirport}})
	if err != nil {
		slog.ErrorContext(ctx, "failed to get flights", slog.Any("error", err))
		return nil, fmt.Errorf("failed to get flights: %v", err)
	}

	var flights []Flight
	if err := cur.All(ctx, &flights); err != nil {
		slog.ErrorContext(ctx, "failed to get cursor for flights", slog.Any("error", err))
		return nil, fmt.Errorf("failed to read cursor for flights: %v", err)
	}

	pbFlights := []*pb.Flight{}
	for _, flight := range flights {
		pbFlights = append(pbFlights, &pb.Flight{
			Id:            flight.Id,
			FromAirport:   flight.FromAirport,
			ToAirport:     flight.ToAirport,
			DepartureTime: flight.DepartureTime,
			ArrivalTime:   flight.ArrivalTime,
		})
	}

	slog.DebugContext(ctx, fmt.Sprintf("successfully found %d flights", len(flights)))

	if len(pbFlights) == 0 {
		pbFlights = nil
	}

	return &pb.SearchResult{
		Flights: pbFlights,
	}, nil
}

func (s *Server) BookFlight(ctx context.Context, req *pb.BookingRequest) (*pb.Booking, error) {

	slog.InfoContext(ctx, "book flight", slog.String("id", req.Id))

	c := s.MongoClient.Database("flights-db").Collection("bookings")

	_, err := c.InsertOne(ctx, Booking{Id: req.Id})
	if err != nil {
		return nil, err
	}

	return &pb.Booking{Id: req.Id}, nil
}
