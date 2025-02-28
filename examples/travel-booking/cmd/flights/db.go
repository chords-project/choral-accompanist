package main

import (
	"context"
	"fmt"
	"log"
	"log/slog"

	"github.com/delimitrou/DeathStarBench/tree/master/hotelReservation/services/flights"
	"go.mongodb.org/mongo-driver/bson"
	"go.mongodb.org/mongo-driver/mongo"
	"go.mongodb.org/mongo-driver/mongo/options"
)

func initializeDatabase(url string) (*mongo.Client, func()) {
	slog.Info("Generating test data...")

	newAirports := []interface{}{
		flights.Airport{
			Id:   "1",
			Name: "San Francisco International Airport",
			Lat:  37.6292556,
			Lon:  -122.3648404,
		},
		flights.Airport{
			Id:   "2",
			Name: "Copenhagen Airport",
			Lat:  55.6277025,
			Lon:  12.6344101,
		},
	}

	newFlights := []interface{}{
		flights.Flight{
			Id:            "1",
			FromAirport:   "2",
			ToAirport:     "1",
			DepartureTime: "2025-03-01 12:00",
			ArrivalTime:   "2025-03-01 19:30",
		},
	}

	uri := fmt.Sprintf("mongodb://%s", url)
	slog.Info(fmt.Sprintf("Attempting connection to %v", uri))

	opts := options.Client().ApplyURI(uri)
	client, err := mongo.Connect(context.TODO(), opts)
	if err != nil {
		log.Panic(err.Error())
	}
	slog.Info("Successfully connected to MongoDB")

	airportsCollection := client.Database("flights-db").Collection("airports")

	airportCount, err := airportsCollection.CountDocuments(context.TODO(), bson.D{})
	if err != nil {
		log.Panic(err.Error())
	}

	if airportCount == 0 {
		_, err = airportsCollection.InsertMany(context.TODO(), newAirports)
		if err != nil {
			log.Fatal(err.Error())
		}
		slog.Info("Successfully inserted test data into flights DB (collection airports)")
	}

	flightsCollection := client.Database("flights-db").Collection("flights")

	flightCount, err := flightsCollection.CountDocuments(context.TODO(), bson.D{})
	if err != nil {
		log.Panic(err.Error())
	}

	if flightCount == 0 {
		_, err = flightsCollection.InsertMany(context.TODO(), newFlights)
		if err != nil {
			log.Fatal(err.Error())
		}
		slog.Info("Successfully inserted test data into flights DB (collection flights)")
	}

	return client, func() {
		if err := client.Disconnect(context.TODO()); err != nil {
			log.Fatal(err.Error())
		}
	}
}
