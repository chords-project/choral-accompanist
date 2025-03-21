package main

import (
	"context"
	"encoding/json"
	"flag"
	"io"
	"log"
	"log/slog"
	"os"
	"strconv"

	"github.com/delimitrou/DeathStarBench/tree/master/hotelReservation/registry"
	"github.com/delimitrou/DeathStarBench/tree/master/hotelReservation/services/review"
	"github.com/delimitrou/DeathStarBench/tree/master/hotelReservation/tracing"
	"github.com/delimitrou/DeathStarBench/tree/master/hotelReservation/tune"
	// "github.com/bradfitz/gomemcache/memcache"
)

func main() {
	tune.Init()

	slog.Info("Reading config...")
	jsonFile, err := os.Open("config.json")
	if err != nil {
		slog.Error("Got error while reading config", slog.Any("error", err))
		return
	}

	defer jsonFile.Close()

	byteValue, err := io.ReadAll(jsonFile)
	if err != nil {
		slog.Error("Failed to read config file", slog.Any("error", err))
		return
	}

	var result map[string]string
	if err := json.Unmarshal([]byte(byteValue), &result); err != nil {
		slog.Error("Failed to decode config file json", slog.Any("error", err))
		return
	}

	slog.Info("Read database URL: %v", result["ReviewMongoAddress"])
	slog.Info("Initializing DB connection...")
	mongo_session, mongoClose := initializeDatabase(result["ReviewMongoAddress"])
	defer mongoClose()
	slog.Info("Successfull")

	slog.Info("Read review memcashed address: %v", result["ReviewMemcAddress"])
	slog.Info("Initializing Memcashed client...")
	// memc_client := memcache.New(result["ReviewMemcAddress"])
	// memc_client.Timeout = time.Second * 2
	// memc_client.MaxIdleConns = 512
	memc_client := tune.NewMemCClient2(result["ReviewMemcAddress"])
	slog.Info("Successfull")

	serv_port, _ := strconv.Atoi(result["ReviewPort"])
	serv_ip := result["ReviewIP"]
	slog.Info("Read target port: %v", serv_port)
	slog.Info("Read consul address: %v", result["consulAddress"])
	slog.Info("Read jaeger address: %v", result["jaegerAddress"])

	var (
		// port       = flag.Int("port", 8081, "The server port")
		otlpAddr         = flag.String("otlpaddr", result["otlpAddress"], "OTLP address")
		pyroscopeAddress = flag.String("pyroscopeAddress", result["pyroscopeAddress"], "Pyroscope address")
		consulAddr       = flag.String("consuladdr", result["consulAddress"], "Consul address")
	)
	flag.Parse()

	slog.Info("Initializing OTLP agent...",
		slog.String("service name", "review"), slog.String("host", *otlpAddr))
	otelShutdown, err := tracing.SetupOTelSDK("review", *otlpAddr, *pyroscopeAddress)
	if err != nil {
		slog.Error("Failed to initialize open telementry", slog.Any("error", err))
		return
	}
	defer otelShutdown(context.Background())

	slog.Info("Initializing consul agent...", slog.String("host", *consulAddr))
	registry, err := registry.NewClient(*consulAddr)
	if err != nil {
		log.Panicf("Got error while initializing consul agent", slog.Any("error", err))
	}
	slog.Info("Consul agent initialized")

	srv := review.Server{
		// Port:     *port,
		Registry:    registry,
		Port:        serv_port,
		IpAddr:      serv_ip,
		MongoClient: mongo_session,
		MemcClient:  memc_client,
	}

	slog.Info("Starting server...")
	log.Fatal(srv.Run().Error())
}
