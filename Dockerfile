FROM alpine:3.15 AS builder

RUN apk add --no-cache openjdk17

ADD . /app
WORKDIR /app

RUN ./gradlew :server:linkReleaseExecutableNative

FROM alpine:3.15

WORKDIR /app

COPY --from=builder /app/server/build/bin/native/releaseExecutable/server.kexe /app/server

ENTRYPOINT ["/app/server"]
