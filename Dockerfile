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

ENV JDK_JAVA_OPTIONS="-XX:MaxRAMPercentage=40 -XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=48m -XX:MaxMetaspaceSize=192m -Xss512k -XX:MaxDirectMemorySize=32m -XX:+ExitOnOutOfMemoryError" \
    MALLOC_ARENA_MAX=2

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
