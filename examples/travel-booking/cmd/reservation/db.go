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

type Reservation struct {
	HotelId      string `bson:"hotelId"`
	CustomerName string `bson:"customerName"`
	InDate       string `bson:"inDate"`
	OutDate      string `bson:"outDate"`
	Number       int    `bson:"number"`
}

type Number struct {
	HotelId string `bson:"hotelId"`
	Number  int    `bson:"numberOfRoom"`
}

func initializeDatabase(url string) (*mongo.Client, func()) {
	slog.Info("Generating test data...")

	newReservations := []interface{}{
		Reservation{"4", "Alice", "2015-04-09", "2015-04-10", 1},
	}

	newNumbers := []interface{}{
		Number{"1", 200},
		Number{"2", 200},
		Number{"3", 200},
		Number{"4", 200},
		Number{"5", 200},
		Number{"6", 200},
	}

	for i := 7; i <= 80; i++ {
		hotelID := strconv.Itoa(i)

		roomNumber := 200
		if i%3 == 1 {
			roomNumber = 300
		} else if i%3 == 2 {
			roomNumber = 250
		}

		newNumbers = append(newNumbers, Number{hotelID, roomNumber})
	}

	uri := fmt.Sprintf("mongodb://%s", url)
	slog.Info(fmt.Sprintf("Attempting connection to %v", uri))

	opts := options.Client().ApplyURI(uri)
	client, err := mongo.Connect(context.TODO(), opts)
	if err != nil {
		log.Panic(err.Error())
	}
	slog.Info("Successfully connected to MongoDB")

	database := client.Database("reservation-db")
	resCollection := database.Collection("reservation")
	numCollection := database.Collection("number")

	resCount, err := resCollection.CountDocuments(context.TODO(), bson.D{})
	if err != nil {
		log.Panic(err.Error())
	}

	if resCount == 0 {
		_, err = resCollection.InsertMany(context.TODO(), newReservations)
		if err != nil {
			log.Fatal(err.Error())
		}
		slog.Info("Successfully inserted test data into reservation DB (reservation collection)")
	}

	numCount, err := numCollection.CountDocuments(context.TODO(), bson.D{})
	if err != nil {
		log.Panic(err.Error())
	}

	if numCount == 0 {
		_, err = numCollection.InsertMany(context.TODO(), newNumbers)
		if err != nil {
			log.Fatal(err.Error())
		}
		slog.Info("Successfully inserted test data into reservation DB (number collection)")
	}

	return client, func() {
		if err := client.Disconnect(context.TODO()); err != nil {
			log.Fatal(err.Error())
		}
	}
}
