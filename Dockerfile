FROM ubuntu:18.04 AS builder

RUN apt-get update && apt-get install -y libsqlite3-dev openjdk-11-jdk && rm -rf /var/lib/apt/lists/*

ADD . /app
WORKDIR /app

RUN ./gradlew :server:linkReleaseExecutableNative

FROM ubuntu:18.04

RUN apt-get update && apt-get install -y libsqlite3-0 && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=builder /app/server/build/bin/native/releaseExecutable/server.kexe /app/server

ENTRYPOINT ["/app/server"]
