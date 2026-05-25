# Accompanist

Accompanist is a framework for writing service-oriented applications with the
[Choral](https://www.choral-lang.org) programming language. 

This repository contains three sub-projects:

- `accompanist/`: Source code for the Accompanist framework itself.
- `examples/`: A collection of demo applications that use Accompanist.
- `benchmark/`: A collection of microbenchmarks that use Accompanist. 

## Installation

This project requires JDK 23 and Choral 0.1.12 ([Link to Install](https://www.choral-lang.org/install.html)). To build Accompanist:
```bash
cd accompanist && ./gradlew build
```
To run the demo applications, see the `README.md` files in the `examples/`
directory.