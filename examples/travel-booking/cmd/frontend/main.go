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
	"github.com/delimitrou/DeathStarBench/tree/master/hotelReservation/services/frontend"
	"github.com/delimitrou/DeathStarBench/tree/master/hotelReservation/tracing"
	"github.com/delimitrou/DeathStarBench/tree/master/hotelReservation/tune"
)

func main() {
	tune.Init()

	slog.Info("Reading config...")
	jsonFile, err := os.Open("config.json")
	if err != nil {
		slog.Error("Got error while reading config", slog.Any("error", err))
	}
	defer jsonFile.Close()

	byteValue, _ := io.ReadAll(jsonFile)

	var result map[string]string
	if err := json.Unmarshal([]byte(byteValue), &result); err != nil {
		slog.Error("Failed to decode config file json", slog.Any("error", err))
		return
	}

	servPort, _ := strconv.Atoi(result["FrontendPort"])
	servIP := result["FrontendIP"]
	knativeDNS := result["KnativeDomainName"]

	var (
		// port       = flag.Int("port", 8081, "The server port")
		otlpAddr         = flag.String("otlpaddr", result["otlpAddress"], "OTLP address")
		pyroscopeAddress = flag.String("pyroscopeAddress", result["pyroscopeAddress"], "Pyroscope address")
		consulAddr       = flag.String("consuladdr", result["consulAddress"], "Consul address")
	)
	flag.Parse()

	slog.Info("Initializing OTLP agent...",
		slog.String("service name", "frontend"), slog.String("host", *otlpAddr))
	otelShutdown, err := tracing.SetupOTelSDK("frontend", *otlpAddr, *pyroscopeAddress)
	if err != nil {
		slog.Error("Failed to initialize open telementry", slog.Any("error", err))
		return
	}
	defer otelShutdown(context.Background())

	slog.Info("Initializing consul agent...", slog.String("host", *consulAddr))
	registry, err := registry.NewClient(*consulAddr)
	if err != nil {
		log.Panicf("Got error while initializing consul agent: %v", err)
	}
	slog.Info("Consul agent initialized")

	srv := &frontend.Server{
		KnativeDns: knativeDNS,
		Registry:   registry,
		IpAddr:     servIP,
		ConsulAddr: *consulAddr,
		Port:       servPort,
	}

	slog.Info("Starting server...")
	log.Fatal(srv.Run().Error())
}
