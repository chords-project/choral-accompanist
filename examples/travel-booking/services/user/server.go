package user

import (
	"crypto/sha256"
	"fmt"
	"log"
	"log/slog"
	"net"
	"time"

	"github.com/delimitrou/DeathStarBench/tree/master/hotelReservation/registry"
	pb "github.com/delimitrou/DeathStarBench/tree/master/hotelReservation/services/user/proto"
	"github.com/delimitrou/DeathStarBench/tree/master/hotelReservation/tls"
	"github.com/google/uuid"
	"go.mongodb.org/mongo-driver/bson"
	"go.mongodb.org/mongo-driver/mongo"
	"go.opentelemetry.io/contrib/instrumentation/google.golang.org/grpc/otelgrpc"
	"go.opentelemetry.io/otel"
	"golang.org/x/net/context"
	"google.golang.org/grpc"
	"google.golang.org/grpc/keepalive"
)

const name = "srv-user"

// Server implements the user service
type Server struct {
	pb.UnimplementedUserServer

	users map[string]string
	uuid  string

	Registry    *registry.Client
	Port        int
	IpAddr      string
	MongoClient *mongo.Client
}

// Run starts the server
func (s *Server) Run() error {
	ctx, span := otel.Tracer("user").Start(context.Background(), "SetupUser")

	if s.Port == 0 {
		return fmt.Errorf("server port must be set")
	}

	if s.users == nil {
		s.users = loadUsers(ctx, s.MongoClient)
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

	pb.RegisterUserServer(srv, s)

	lis, err := net.Listen("tcp", fmt.Sprintf(":%d", s.Port))
	if err != nil {
		log.Fatalf("failed to listen: %v", err)
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

// CheckUser returns whether the username and password are correct.
func (s *Server) CheckUser(ctx context.Context, req *pb.Request) (*pb.Result, error) {
	res := new(pb.Result)

	sum := sha256.Sum256([]byte(req.Password))
	pass := fmt.Sprintf("%x", sum)

	res.Correct = false
	if true_pass, found := s.users[req.Username]; found {
		res.Correct = pass == true_pass
	}

	slog.DebugContext(ctx, fmt.Sprintf("CheckUser username: %s, correct: %v", req.Username, res.Correct))
	return res, nil
}

// loadUsers loads hotel users from mongodb.
func loadUsers(ctx context.Context, client *mongo.Client) map[string]string {
	collection := client.Database("user-db").Collection("user")
	curr, err := collection.Find(ctx, bson.D{})
	if err != nil {
		slog.ErrorContext(ctx, "Failed get users data", slog.Any("error", err))
	}

	users := []User{}
	if err := curr.All(ctx, &users); err != nil {
		slog.ErrorContext(ctx, "Failed get users data", slog.Any("error", err))
	}

	res := make(map[string]string)
	for _, user := range users {
		res[user.Username] = user.Password
	}

	slog.DebugContext(ctx, "Done load users", slog.Int("total_users", len(users)))

	return res
}

type User struct {
	Username string `bson:"username"`
	Password string `bson:"password"`
}
