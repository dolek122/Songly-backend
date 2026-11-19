FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B dependency:go-offline

COPY src ./src
RUN ./mvnw -B clean package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

# Pamięć pod kontener 512 MB (Render free, ADR 0051). Bez flag JVM brała 25% na
# stertę (128 MB, zajęte w 94%), a resztę zjadały metaspace (~145 MB), kod JIT,
# stosy wątków i bufory: RSS 526 MB pod obciążeniem, czyli ponad limit. Z tymi
# flagami: 410 MB po 6 minutach obciążenia, ze stertą 205 MB.
#  - TieredStopAtLevel=1: sam kompilator C1. Z C2 RSS dochodził do 492 MB. C1
#    kosztuje ~23% przepustowości, ale na 0,1 CPU procesora i tak brakuje wcześniej.
#  - MaxMetaspaceSize: wyciek klas kończy się błędem, a nie zabiciem kontenera.
#  - ExitOnOutOfMemoryError: po OOM proces kończy się od razu i Render go wznawia,
#    zamiast zostawić aplikację z częścią martwych wątków.
#  - MALLOC_ARENA_MAX=2: glibc zakłada arenę malloc na wątek, poza kontrolą JVM.
# Na większym planie nadpisz JDK_JAVA_OPTIONS zmienną środowiskową w Renderze.
ENV JDK_JAVA_OPTIONS="-XX:MaxRAMPercentage=40 -XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=48m -XX:MaxMetaspaceSize=192m -Xss512k -XX:MaxDirectMemorySize=32m -XX:+ExitOnOutOfMemoryError" \
    MALLOC_ARENA_MAX=2

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
