# Songly — backend

REST API (`/api/v1`) aplikacji Songly: posty i znajomi, „teraz gram” (Spotify albo status ręczny), dopasowania po gatunkach i społeczności. Klient: [Songly-mobile](https://github.com/dolek122/Songly-mobile).

Java 21, Spring Boot 4, PostgreSQL 16 (Flyway), Redis 7, magazyn S3 (lokalnie MinIO).

## Uruchomienie

Wymagania: JDK 21 i Docker.

```
docker compose up -d
./mvnw spring-boot:run
```

API: http://localhost:8080, Swagger UI: http://localhost:8080/swagger-ui.html

## Testy

```
./mvnw verify
```

Testy integracyjne wymagają działającego Dockera. Formatowanie poprawia `./mvnw spotless:apply`.

## Kontrakt API

`docs/api/openapi.json` jest generowany z kodu i sprawdzany w CI. Po zmianie API:

```
./mvnw test -Dtest=OpenApiSpecExportTest -Dopenapi.export=true
```

## Konfiguracja i wdrożenie

Zmienne środowiskowe: `.env.example`. Lokalnie nic nie trzeba ustawiać.

Wdrożenie na Render według `render.yaml`. Sekrety (`sync: false`) ustawia się w panelu Rendera.
