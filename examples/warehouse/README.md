# Warehouse SAGA Example

## Run using docker compose

To run this example, first build the docker images by running `./gradlew jibDockerBuild`.
This will build and install the docker images `accompanist-warehouse`, `accompanist-payment`, and
`accompanist-loyalty`.

Now run the docker compose file by running `docker compose -f compose.accompanist.yml up`.
This will start all containers with their respective databases.

To trigger the choreography, run `curl localhost:5000/orderFulfillment`.

## Run in Kubernetes

Run `skaffold run` to build all images and apply them to the current kubernetes environment of `kubectl`.
