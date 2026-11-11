# One image for every JVM process in the project: shard host, Postgres writers and outbox relay, order API,
# mock broker, replayer.
# docker-compose picks the process with `command`.
#
# Built inside the container, so a clean machine needs only Docker. Tests are not run here (CI runs them).
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
# Fetch the Gradle distribution in its own layer, so editing source does not download it again.
RUN ./gradlew --no-daemon --version
COPY engine engine
COPY host host
COPY writers writers
COPY replayer replayer
COPY api api
COPY loadtest loadtest
COPY broker-mock broker-mock
COPY report report
RUN ./gradlew --no-daemon :host:installDist :writers:installDist :replayer:installDist :broker-mock:installDist :api:bootJar -x test

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 risk && mkdir -p /snapshots && chown risk /snapshots
COPY --from=build /src/host/build/install/host /opt/host
COPY --from=build /src/writers/build/install/writers /opt/writers
COPY --from=build /src/replayer/build/install/replayer /opt/replayer
COPY --from=build /src/broker-mock/build/install/broker-mock /opt/broker-mock
COPY --from=build /src/api/build/libs/api-0.1.0-SNAPSHOT.jar /opt/api/api.jar
USER risk
WORKDIR /opt
