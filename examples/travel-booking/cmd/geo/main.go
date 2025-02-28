package main

import (
	"context"
	"encoding/json"
	"flag"
	"io/ioutil"
	"log"
	"log/slog"
	"os"
	"strconv"

	"github.com/delimitrou/DeathStarBench/tree/master/hotelReservation/registry"
	"github.com/delimitrou/DeathStarBench/tree/master/hotelReservation/services/geo"
	"github.com/delimitrou/DeathStarBench/tree/master/hotelReservation/tracing"
	"github.com/delimitrou/DeathStarBench/tree/master/hotelReservation/tune"
)

func main() {
	tune.Init()

	slog.Info("Reading config...")
	jsonFile, err := os.Open("config.json")
	if err != nil {
		slog.Error("Got error while reading config: %v", err)
	}

	defer jsonFile.Close()

	byteValue, _ := ioutil.ReadAll(jsonFile)

	var result map[string]string
	json.Unmarshal([]byte(byteValue), &result)

	slog.Info("Initializing DB connection...")
	mongoClient, mongoClose := initializeDatabase(result["GeoMongoAddress"])
	defer mongoClose()

	servPort, _ := strconv.Atoi(result["GeoPort"])
	servIP := result["GeoIP"]

	var (
		// port       = flag.Int("port", 8081, "The server port")
		otlpAddr         = flag.String("otlpaddr", result["otlpAddress"], "OTLP address")
		pyroscopeAddress = flag.String("pyroscopeAddress", result["pyroscopeAddress"], "Pyroscope address")
		consulAddr       = flag.String("consuladdr", result["consulAddress"], "Consul address")
	)
	flag.Parse()

	slog.Info("Initializing OTLP agent...",
		slog.String("service name", "geo"), slog.String("host", *otlpAddr))
	otelShutdown, err := tracing.SetupOTelSDK("geo", *otlpAddr, *pyroscopeAddress)
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

	srv := &geo.Server{
		Port:        servPort,
		IpAddr:      servIP,
		Registry:    registry,
		MongoClient: mongoClient,
	}

	slog.Info("Starting server...")
	log.Fatal(srv.Run().Error())
}
