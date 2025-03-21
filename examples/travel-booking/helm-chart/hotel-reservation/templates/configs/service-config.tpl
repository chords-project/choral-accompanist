{{- define "hotel-reservation.templates.service-config.json" }}
{
    "consulAddress": "consul-{{ include "hotel-reservation.service-url" . }}:8500",
    "jaegerAddress": "jaeger-{{ include "hotel-reservation.service-url" . }}:6831",
    "FrontendPort": "5000",
    "GeoPort": "8083",
    "GeoMongoAddress": "mongodb-geo-{{ include "hotel-reservation.service-url" . }}:27018",
    "ProfilePort": "8081",
    "ProfileMongoAddress": "mongodb-profile-{{ include "hotel-reservation.service-url" . }}:27019",
    "ProfileMemcAddress": {{ include "hotel-reservation.generateMemcAddr" (list . .Values.global.memcached.HACount "memcached-profile" 11213)}},
    "RatePort": "8084",
    "RateMongoAddress": "mongodb-rate-{{ include "hotel-reservation.service-url" . }}:27020",
    "RateMemcAddress": {{ include "hotel-reservation.generateMemcAddr" (list . .Values.global.memcached.HACount "memcached-rate" 11212)}},
    "RecommendPort": "8085",
    "RecommendMongoAddress": "mongodb-recommendation-{{ include "hotel-reservation.service-url" . }}:27021",
    "ReservePort": "8087",
    "ReserveMongoAddress": "mongodb-reservation-{{ include "hotel-reservation.service-url" . }}:27022",
    "ReserveMemcAddress": {{ include "hotel-reservation.generateMemcAddr" (list . .Values.global.memcached.HACount "memcached-reserve" 11214)}},
    "SearchPort": "8082",
    "UserPort": "8086",
    "UserMongoAddress": "mongodb-user-{{ include "hotel-reservation.service-url" . }}:27023",
    "ReviewPort": "8088",
    "ReviewMongoAddress": "mongodb-review-{{ include "hotel-reservation.service-url" . }}:27024",
    "ReviewMemcAddress": {{ include "hotel-reservation.generateMemcAddr" (list . .Values.global.memcached.HACount "memcached-review" 11215)}}
}
{{- end }}
