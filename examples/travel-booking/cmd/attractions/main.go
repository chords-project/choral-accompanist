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
	"github.com/delimitrou/DeathStarBench/tree/master/hotelReservation/services/attractions"
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
	}

	defer jsonFile.Close()

	byteValue, _ := ioutil.ReadAll(jsonFile)

	var result map[string]string
	json.Unmarshal([]byte(byteValue), &result)

	slog.Info("Read database URL", slog.String("url", result["AttractionsMongoAddress"]))
	slog.Info("Initializing DB connection...")
	mongo_session, mongoClose := initializeDatabase(result["AttractionsMongoAddress"])
	defer mongoClose()
	slog.Info("Successfull")

	// slog.Info("Read attractions memcashed address: %v", result["AttractionsMemcAddress"])
	// slog.Info("Initializing Memcashed client...")
	// memc_client := memcache.New(result["AttractionsMemcAddress"])
	// memc_client.Timeout = time.Second * 2
	// memc_client.MaxIdleConns = 512
	// memc_client := tune.NewMemCClient2(result["AttractionsMemcAddress"])
	// slog.Info("Successfull")

	serv_port, _ := strconv.Atoi(result["AttractionsPort"])
	serv_ip := result["AttractionsIP"]
	slog.Info("Read target port", slog.Int("port", serv_port))
	slog.Info("Read consul address", slog.String("address", result["consulAddress"]))
	slog.Info("Read jaeger address", slog.String("address", result["jaegerAddress"]))

	var (
		// port       = flag.Int("port", 8081, "The server port")
		otlpAddr         = flag.String("otlpaddr", result["otlpAddress"], "OTLP address")
		pyroscopeAddress = flag.String("pyroscopeAddress", result["pyroscopeAddress"], "Pyroscope address")
		consulAddr       = flag.String("consuladdr", result["consulAddress"], "Consul address")
	)
	flag.Parse()

	slog.Info("Initializing OTLP agent...",
		slog.String("service name", "attractions"), slog.String("host", *otlpAddr))
	otelShutdown, err := tracing.SetupOTelSDK("attractions", *otlpAddr, *pyroscopeAddress)
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

	srv := attractions.Server{
		// Port:     *port,
		Registry:    registry,
		Port:        serv_port,
		IpAddr:      serv_ip,
		MongoClient: mongo_session,
	}

	slog.Info("Starting server...")
	log.Fatal(srv.Run().Error())
}
