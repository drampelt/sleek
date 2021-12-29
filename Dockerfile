FROM openjdk:17 AS builder

ADD . /app
WORKDIR /app

RUN ./gradlew :server:linkReleaseExecutableNative

FROM debian:bullseye-slim

WORKDIR /app

COPY --from=builder /app/server/build/bin/native/releaseExecutable/server.kexe /app/server

ENTRYPOINT ["/app/server"]
