# Sample 02: Process Inject (bella run) — Java

**Pattern:** `bella run -- java -jar app.jar` — secrets injected as env vars, no file written.

## Setup

```bash
mvn package

# Authenticate with API key
bella login --api-key bax-xxxxxxxxxxxxxxxxxxxx

export BELLA_BAXTER_URL=http://localhost:5522   # your Bella Baxter instance

# Run with secrets injected as environment variables
bella run --app java-02-process-inject -- java -jar target/sample-02-process-inject-1.0.0.jar
```

## Works with any Java command

```bash
# Spring Boot jar
bella run --app my-app -- java -jar myapp.jar

# Maven Spring Boot
bella run --app my-app -- mvn spring-boot:run

# Quarkus dev mode
bella run --app my-app -- mvn quarkus:dev

# Gradle
bella run --app my-app -- ./gradlew bootRun

# Integration tests
bella run --app my-app -- mvn verify

# Database migrations (Flyway / Liquibase)
bella run --app my-app -- mvn flyway:migrate
```

## Accessing secrets in Spring Boot

When launched via `bella run`, all secrets are in `System.getenv()`:

```java
// Spring Boot reads them via @Value or Environment
@Value("${DATABASE_URL}")   // works if also set as System property
String databaseUrl;

// Or via Environment
@Autowired Environment env;
String dbUrl = env.getProperty("DATABASE_URL");

// Or directly
String dbUrl = System.getenv("DATABASE_URL");
```

For full `@Value` support (Spring's `${}` syntax), combine with the `EnvironmentPostProcessor` approach (sample 03).

## Secret rotation

❌ **Not supported automatically.** Environment variables injected by `bella run` are set once at JVM spawn and are immutable for the lifetime of the process — the JVM reads them at startup, they cannot be changed from outside.

**To pick up rotated secrets:** restart via `bella run`:

```bash
bella run -p my-project -e production -- java -jar target/myapp.jar
```

For containerised workloads, let your orchestrator (Kubernetes, ECS) restart the container — `bella run` will re-fetch fresh secrets at each spawn.
