# Warehouse SAGA Example

## Run using docker compose

To run this example, first build the docker images by running `gradle jibDockerBuild`.
This will build and install the docker containers `accompanist-warehouse`, `accompanist-payment`, and
`accompanist-loyalty`.

Now run the docker compose file by running `docker compose -f compose.accompanist.yml up`.
This will start all containers with their respective databases.
