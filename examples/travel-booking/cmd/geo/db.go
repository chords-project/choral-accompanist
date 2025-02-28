package main

import (
	"context"
	"fmt"
	"log"
	"log/slog"
	"strconv"

	"go.mongodb.org/mongo-driver/bson"
	"go.mongodb.org/mongo-driver/mongo"
	"go.mongodb.org/mongo-driver/mongo/options"
)

type point struct {
	Pid  string  `bson:"hotelId"`
	Plat float64 `bson:"lat"`
	Plon float64 `bson:"lon"`
}

func initializeDatabase(url string) (*mongo.Client, func()) {
	slog.Info("Generating test data...")

	newPoints := []interface{}{
		point{"1", 37.7867, -122.4112},
		point{"2", 37.7854, -122.4005},
		point{"3", 37.7854, -122.4071},
		point{"4", 37.7936, -122.3930},
		point{"5", 37.7831, -122.4181},
		point{"6", 37.7863, -122.4015},
	}

	for i := 7; i <= 80; i++ {
		hotelID := strconv.Itoa(i)
		lat := 37.7835 + float64(i)/500.0*3
		lon := -122.41 + float64(i)/500.0*4

		newPoints = append(newPoints, point{hotelID, lat, lon})
	}

	uri := fmt.Sprintf("mongodb://%s", url)
	slog.Info(fmt.Sprintf("Attempting connection to %v", uri))

	opts := options.Client().ApplyURI(uri)
	client, err := mongo.Connect(context.TODO(), opts)
	if err != nil {
		log.Panic(err.Error())
	}
	slog.Info("Successfully connected to MongoDB")

	collection := client.Database("geo-db").Collection("geo")

	count, err := collection.CountDocuments(context.TODO(), bson.D{})
	if err != nil {
		log.Panic(err.Error())
	}

	if count == 0 {
		_, err = collection.InsertMany(context.TODO(), newPoints)
		if err != nil {
			log.Fatal(err.Error())
		}
		slog.Info("Successfully inserted test data into geo DB")
	}

	return client, func() {
		if err := client.Disconnect(context.TODO()); err != nil {
			log.Fatal(err.Error())
		}
	}
}
