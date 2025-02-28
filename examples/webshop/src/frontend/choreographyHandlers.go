package main

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"strconv"

	pb "github.com/GoogleCloudPlatform/microservices-demo/src/frontend/genproto"
	"github.com/GoogleCloudPlatform/microservices-demo/src/frontend/money"
	"github.com/GoogleCloudPlatform/microservices-demo/src/frontend/validator"
	"github.com/pkg/errors"
	"github.com/sirupsen/logrus"
)

func (fe *frontendServer) placeOrderWithChoreographyHandler(w http.ResponseWriter, r *http.Request) {
	log := r.Context().Value(ctxKeyLog{}).(logrus.FieldLogger)
	log.Debug("placing order")

	var (
		email         = r.FormValue("email")
		streetAddress = r.FormValue("street_address")
		zipCode, _    = strconv.ParseInt(r.FormValue("zip_code"), 10, 32)
		city          = r.FormValue("city")
		state         = r.FormValue("state")
		country       = r.FormValue("country")
		ccNumber      = r.FormValue("credit_card_number")
		ccMonth, _    = strconv.ParseInt(r.FormValue("credit_card_expiration_month"), 10, 32)
		ccYear, _     = strconv.ParseInt(r.FormValue("credit_card_expiration_year"), 10, 32)
		ccCVV, _      = strconv.ParseInt(r.FormValue("credit_card_cvv"), 10, 32)
	)

	payload := validator.PlaceOrderPayload{
		Email:         email,
		StreetAddress: streetAddress,
		ZipCode:       zipCode,
		City:          city,
		State:         state,
		Country:       country,
		CcNumber:      ccNumber,
		CcMonth:       ccMonth,
		CcYear:        ccYear,
		CcCVV:         ccCVV,
	}
	if err := payload.Validate(); err != nil {
		renderHTTPError(log, r, w, validator.ValidationErrorResponse(err), http.StatusUnprocessableEntity)
		return
	}

	placeOrderReq := pb.PlaceOrderRequest{
		Email: payload.Email,
		CreditCard: &pb.CreditCardInfo{
			CreditCardNumber:          payload.CcNumber,
			CreditCardExpirationMonth: int32(payload.CcMonth),
			CreditCardExpirationYear:  int32(payload.CcYear),
			CreditCardCvv:             int32(payload.CcCVV)},
		UserId:       sessionID(r),
		UserCurrency: currentCurrency(r),
		Address: &pb.Address{
			StreetAddress: payload.StreetAddress,
			City:          payload.City,
			State:         payload.State,
			ZipCode:       int32(payload.ZipCode),
			Country:       payload.Country},
	}

	reqJson, err := json.Marshal(&placeOrderReq)
	if err != nil {
		renderHTTPError(log, r, w, err, http.StatusInternalServerError)
		return
	}

	log.WithField("request", string(reqJson)).Info("Order place choreography request")

	req, err := http.NewRequest("POST", "http://localhost:8081/checkout", bytes.NewBuffer(reqJson))
	if err != nil {
		renderHTTPError(log, r, w, err, http.StatusInternalServerError)
		return
	}

	req.Header.Set("Content-Type", "application/json")

	client := &http.Client{}
	resp, err := client.Do(req)
	if err != nil {
		renderHTTPError(log, r, w, err, http.StatusInternalServerError)
		return
	}
	body, _ := io.ReadAll(resp.Body)
	resp.Body.Close()

	if resp.StatusCode != 200 {
		renderHTTPError(log, r, w, errors.New(resp.Status+": "+string(body)), http.StatusInternalServerError)
		return
	}

	log.WithField("response", string(body)).Info("Order place choreography response")

	var order pb.PlaceOrderResponse
	if err := json.Unmarshal(body, &order); err != nil {
		renderHTTPError(log, r, w, err, http.StatusInternalServerError)
		return
	}

	log.WithField("order", order.GetOrder().GetOrderId()).Info("order placed")

	order.GetOrder().GetItems()
	recommendations, _ := fe.getRecommendations(r.Context(), sessionID(r), nil)

	totalPaid := *order.GetOrder().GetShippingCost()
	for _, v := range order.GetOrder().GetItems() {
		multPrice := money.MultiplySlow(*v.GetCost(), uint32(v.GetItem().GetQuantity()))
		totalPaid = money.Must(money.Sum(totalPaid, multPrice))
	}

	currencies, err := fe.getCurrencies(r.Context())
	if err != nil {
		renderHTTPError(log, r, w, errors.Wrap(err, "could not retrieve currencies"), http.StatusInternalServerError)
		return
	}

	if err := templates.ExecuteTemplate(w, "order", injectCommonTemplateData(r, map[string]interface{}{
		"show_currency":   false,
		"currencies":      currencies,
		"order":           order.GetOrder(),
		"total_paid":      &totalPaid,
		"recommendations": recommendations,
	})); err != nil {
		log.Println(err)
	}
}
