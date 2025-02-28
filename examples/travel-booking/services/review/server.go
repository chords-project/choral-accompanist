package review

import (
	"encoding/json"
	"fmt"
	"log"
	"log/slog"

	"go.mongodb.org/mongo-driver/bson"
	"go.mongodb.org/mongo-driver/mongo"
	"go.opentelemetry.io/contrib/instrumentation/google.golang.org/grpc/otelgrpc"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/trace"

	// "io/ioutil"
	"net"
	// "os"
	// "sort"
	"time"
	//"sync"

	"github.com/delimitrou/DeathStarBench/tree/master/hotelReservation/registry"
	pb "github.com/delimitrou/DeathStarBench/tree/master/hotelReservation/services/review/proto"
	"github.com/delimitrou/DeathStarBench/tree/master/hotelReservation/tls"
	"github.com/google/uuid"
	"golang.org/x/net/context"
	"google.golang.org/grpc"
	"google.golang.org/grpc/keepalive"

	// "strings"

	"github.com/bradfitz/gomemcache/memcache"
)

const name = "srv-review"

// Server implements the rate service
type Server struct {
	pb.UnimplementedReviewServer

	tracer trace.Tracer

	Port        int
	IpAddr      string
	MongoClient *mongo.Client
	Registry    *registry.Client
	MemcClient  *memcache.Client
	uuid        string
}

// Run starts the server
func (s *Server) Run() error {
	s.tracer = otel.Tracer("review")
	ctx, span := s.tracer.Start(context.Background(), "SetupReview")

	if s.Port == 0 {
		return fmt.Errorf("server port must be set")
	}

	s.uuid = uuid.New().String()

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

	pb.RegisterReviewServer(srv, s)

	lis, err := net.Listen("tcp", fmt.Sprintf(":%d", s.Port))
	if err != nil {
		slog.ErrorContext(ctx, "failed to start TCP server", slog.Any("error", err), slog.Int("port", s.Port))
		return err
	}

	err = s.Registry.Register(ctx, name, s.uuid, s.IpAddr, s.Port)
	if err != nil {
		return fmt.Errorf("failed register: %v", err)
	}
	slog.InfoContext(ctx, "Successfully registered in consul")

	span.End()

	return srv.Serve(lis)
}

// Shutdown cleans up any processes
func (s *Server) Shutdown() {
	s.Registry.Deregister(s.uuid)
}

type ReviewHelper struct {
	ReviewId    string    `bson:"reviewId"`
	HotelId     string    `bson:"hotelId"`
	Name        string    `bson:"name"`
	Rating      float32   `bson:"rating"`
	Description string    `bson:"description"`
	Image       *pb.Image `bson:"images"`
}

type ImageHelper struct {
	Url     string `bson:"url"`
	Default bool   `bson:"default"`
}

func (s *Server) GetReviews(ctx context.Context, req *pb.Request) (*pb.Result, error) {

	res := new(pb.Result)
	reviews := make([]*pb.ReviewComm, 0)

	hotelId := req.HotelId

	_, memSpan := s.tracer.Start(ctx, "memcached_get_review")
	memSpan.SetAttributes(attribute.String("span.kind", "client"))
	item, err := s.MemcClient.Get(hotelId)
	memSpan.End()
	if err != nil && err != memcache.ErrCacheMiss {
		log.Panicf("Tried to get hotelId [%v], but got memmcached error = %s", hotelId, err)
	} else {
		if err == memcache.ErrCacheMiss {
			mongoSpanCtx, mongoSpan := s.tracer.Start(ctx, "mongo_review")
			mongoSpan.SetAttributes(attribute.String("span.kind", "client"))

			//session := s.MongoSession.Copy()
			//defer session.Close()
			//c := session.DB("review-db").C("reviews")
			c := s.MongoClient.Database("review-db").Collection("reviews")

			curr, err := c.Find(mongoSpanCtx, bson.M{"hotelId": hotelId})
			if err != nil {
				slog.ErrorContext(mongoSpanCtx, "Failed get reviews: %v", err)
			}

			var reviewHelpers []ReviewHelper
			//err = c.Find(bson.M{"hotelId": hotelId}).All(&reviewHelpers)
			curr.All(mongoSpanCtx, &reviewHelpers)
			if err != nil {
				slog.ErrorContext(mongoSpanCtx, "Failed get hotels data: %v", err)
			}

			mongoSpan.End()

			for _, reviewHelper := range reviewHelpers {
				revComm := pb.ReviewComm{
					ReviewId:    reviewHelper.ReviewId,
					Name:        reviewHelper.Name,
					Rating:      reviewHelper.Rating,
					Description: reviewHelper.Description,
					Images:      reviewHelper.Image}
				reviews = append(reviews, &revComm)
			}

			reviewJson, err := json.Marshal(reviews)
			if err != nil {
				slog.ErrorContext(ctx, "Failed to marshal hotel [id: %v] with err: %v", hotelId, err)
			}
			memcStr := string(reviewJson)

			s.MemcClient.Set(&memcache.Item{Key: hotelId, Value: []byte(memcStr)})
		} else {
			reviewsStr := string(item.Value)
			slog.DebugContext(ctx, "memc hit with %v", reviewsStr)
			if err := json.Unmarshal([]byte(reviewsStr), &reviews); err != nil {
				log.Panicf("Failed to unmarshal reviews: %s", err)
			}
		}
	}

	//reviewsEmpty := make([]*pb.ReviewComm, 0)

	res.Reviews = reviews
	return res, nil
}
