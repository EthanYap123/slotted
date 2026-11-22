# The API as a container image for Render's free plan (512 MB, 0.1 CPU). Build from api/:
#   docker build -t slotted-api .
# Tests and quality checks run in CI before an image is built, so they are skipped here.

FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /build

# Without unzip the Maven wrapper fetches Maven as .tar.gz, whose checksum differs from the .zip
# checksum pinned in .mvn/wrapper/maven-wrapper.properties, and refuses to run.
RUN apt-get update && apt-get install -y --no-install-recommends unzip && rm -rf /var/lib/apt/lists/*

# Dependencies first, so they are cached until pom.xml changes.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN ./mvnw -B -ntp -q dependency:go-offline

COPY config config
COPY src src
RUN ./mvnw -B -ntp -q package -DskipTests -Dspotless.check.skip=true -Dcheckstyle.skip=true -Djacoco.skip=true \
    && java -Djarmode=tools -jar target/slotted-api.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:21-jre-noble
WORKDIR /app

RUN useradd --system --uid 10001 --no-create-home slotted

# One layer per Boot layer: dependencies change rarely, the application on every commit.
COPY --from=build /build/extracted/dependencies/ ./
COPY --from=build /build/extracted/spring-boot-loader/ ./
COPY --from=build /build/extracted/snapshot-dependencies/ ./
COPY --from=build /build/extracted/application/ ./

USER slotted

# Sized for a 512 MB container (docs/COSTS.md):
# - heap capped at 45% of memory, leaving room for metaspace, threads and code cache
# - Serial GC, the lightest collector for one small CPU
# - small thread stacks and code cache; stop at the first JIT tier so startup is quicker
# - exit on running out of memory, so the host restarts a clean instance
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=45 -XX:+UseSerialGC -Xss512k \
-XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=48m -XX:TieredStopAtLevel=1 \
-XX:+ExitOnOutOfMemoryError" \
    TZ=UTC \
    PORT=8080

EXPOSE 8080
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
