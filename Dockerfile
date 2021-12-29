FROM eclipse-temurin:17-jdk-focal AS builder

RUN apt-get update && apt-get install -y sqlite3 && rm -rf /var/lib/apt/lists/*

ADD . /app
WORKDIR /app

RUN ./gradlew :server:linkReleaseExecutableNative

FROM debian:bullseye-slim

RUN apt-get update && apt-get install -y sqlite3 && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=builder /app/server/build/bin/native/releaseExecutable/server.kexe /app/server

ENTRYPOINT ["/app/server"]
