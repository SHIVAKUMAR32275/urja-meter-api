# Urja Meter API

A REST API built with Java 17 and Spring Boot that provides programmatic access to meter information and energy readings from the Urja Meter Ops portal.

## Technologies

- Java 17
- Spring Boot 3.5.6
- Maven
- Jackson
- Spring Boot Actuator
- JUnit and Spring Boot Test

## Features

- Retrieve paginated meter records
- Search meters by query
- Retrieve details for a specific meter
- Retrieve energy readings for a meter
- Authenticate with the upstream portal
- Handle upstream errors and invalid requests
- Health endpoint for service status

## Prerequisites

- Java 17 or later
- Maven
- Valid access to the Urja Meter Ops portal

## Configuration

Configure the following environment variables before starting the application:

- `URJA_PORTAL_USERNAME`
- `URJA_PORTAL_PASSWORD`

Do not commit credentials, session cookies, or other secrets to version control.

## Run locally

From the project root, set your portal credentials in your terminal environment, then run:

```powershell
$env:URJA_PORTAL_USERNAME = "YOUR_USERNAME"
$env:URJA_PORTAL_PASSWORD = "YOUR_PASSWORD"

mvn spring-boot:run
```

The API runs at `http://localhost:8081`.

## API Endpoints

| Method | Endpoint | Description |
|---|---|---|
| GET | `/api/v1/health` | Check service health |
| GET | `/api/v1/meters?page=1` | Retrieve paginated meters |
| GET | `/api/v1/meters?q=J100005` | Search meters |
| GET | `/api/v1/meters/{meterId}` | Retrieve meter details |
| GET | `/api/v1/meters/{meterId}/energy` | Retrieve energy readings |

### Example requests

Retrieve meters:

```http
GET /api/v1/meters?page=1
```

Search for a meter:

```http
GET /api/v1/meters?q=J100005
```

Retrieve a specific meter:

```http
GET /api/v1/meters/J100005
```

Retrieve energy readings:

```http
GET /api/v1/meters/J100005/energy
```

Health check:

```http
GET /api/v1/health
```

## Build and test

Run the automated tests:

```powershell
mvn clean test
```

Build the executable JAR:

```powershell
mvn clean package
```

Run the packaged application after configuring the environment variables:

```powershell
java -jar target/urja-meter-api-0.0.1-SNAPSHOT.jar
```

## Error handling

The API returns appropriate HTTP error responses for invalid requests, missing meters, and upstream portal failures.

## Notes

- The API depends on the availability and authentication requirements of the upstream portal.
- The examples use a sample meter ID; actual results depend on the portal data.
