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
	"github.com/delimitrou/DeathStarBench/tree/master/hotelReservation/services/rate"
	"github.com/delimitrou/DeathStarBench/tree/master/hotelReservation/tracing"
	"github.com/delimitrou/DeathStarBench/tree/master/hotelReservation/tune"
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

	slog.Info("Initializing DB connection...")
	mongoClient, mongoClose := initializeDatabase(result["RateMongoAddress"])
	defer mongoClose()

	slog.Info("Read profile memcashed address: %v", result["RateMemcAddress"])
	slog.Info("Initializing Memcashed client...")
	memcClient := tune.NewMemCClient2(result["RateMemcAddress"])
	slog.Info("Success")

	servPort, _ := strconv.Atoi(result["RatePort"])
	servIP := result["RateIP"]

	var (
		// port       = flag.Int("port", 8081, "The server port")
		otlpAddr         = flag.String("otlpaddr", result["otlpAddress"], "OTLP address")
		pyroscopeAddress = flag.String("pyroscopeAddress", result["pyroscopeAddress"], "Pyroscope address")
		consulAddr       = flag.String("consuladdr", result["consulAddress"], "Consul address")
	)
	flag.Parse()

	slog.Info("Initializing OTLP agent...",
		slog.String("service name", "rate"), slog.String("host", *otlpAddr))
	otelShutdown, err := tracing.SetupOTelSDK("rate", *otlpAddr, *pyroscopeAddress)
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

	srv := &rate.Server{
		Registry:    registry,
		Port:        servPort,
		IpAddr:      servIP,
		MongoClient: mongoClient,
		MemcClient:  memcClient,
	}

	slog.Info("Starting server...")
	log.Fatal(srv.Run().Error())
}
