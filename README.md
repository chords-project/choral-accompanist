# Accompanist

Accompanist is a framework for writing service-oriented applications with the
[Choral](https://www.choral-lang.org) programming language.

This repository contains three subprojects:

- `accompanist/`: Source code for the Accompanist framework itself.
- `examples/`: A collection of demo applications that use Accompanist.
- `benchmark/`: A collection of micro-benchmarks that use Accompanist.

## Installation

To build Accompanist:

```bash
cd accompanist && ./gradlew build
```

To run the demo applications, see the `README.md` files in the `examples/`
directory.

## Tests

From the `accompanist` directory:

```sh
./gradlew test
```

This runs the ordinary tests.

SQL integration tests are skipped unless `ACCOMPANIST_TEST_POSTGRES_URL` is set.

To run everything using a disposable PostgreSQL container:

```sh
docker run -d --rm --name accompanist-test-db \
  -e POSTGRES_PASSWORD=test -p 127.0.0.1:55439:5432 postgres:17
until docker exec accompanist-test-db pg_isready -U postgres; do sleep 1; done

ACCOMPANIST_TEST_POSTGRES_URL='jdbc:postgresql://127.0.0.1:55439/postgres?user=postgres&password=test' \
  ./gradlew test --rerun-tasks

docker stop accompanist-test-db
```
