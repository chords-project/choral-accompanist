FROM grafana/grafana:latest

COPY ./definitions /var/lib/grafana/dashboards
COPY ./provisioning /etc/grafana/provisioning
COPY ./plugins/haohanyang-mongodb-datasource /var/lib/grafana/plugins/haohanyang-mongodb-datasource

ENV GF_FEATURE_TOGGLES_ENABLE="flameGraph traceqlSearch traceQLStreaming correlations metricsSummary traceqlEditor traceToMetrics traceToProfiles datatrails"
ENV GF_INSTALL_PLUGINS="grafana-lokiexplore-app,grafana-exploretraces-app,grafana-pyroscope-app"
ENV GF_PLUGINS_ALLOW_LOADING_UNSIGNED_PLUGINS="haohanyang-mongodb-datasource"
ENV GF_AUTH_ANONYMOUS_ENABLED="true"
ENV GF_AUTH_ANONYMOUS_ORG_ROLE="Admin"
ENV GF_AUTH_DISABLE_LOGIN_FORM="true"
ENV GF_USERS_DEFAULT_THEME="light"
