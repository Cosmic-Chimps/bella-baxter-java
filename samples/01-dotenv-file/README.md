# Sample 01: `.env` File Approach (Java)

**Pattern:** CLI writes secrets to a `.env` file → app reads it with `dotenv-java`.

Works with any Java application — Spring Boot, Quarkus, plain main(), etc.

---

## How it works

```
bella secrets get -o .env   →   .env file on disk   →   Dotenv.load()   →   dotenv.get("KEY")
```

## Setup

```bash
mvn package

# Authenticate with API key
bella login --api-key bax-xxxxxxxxxxxxxxxxxxxx

export BELLA_BAXTER_URL=http://localhost:5522   # your Bella Baxter instance

# Pull secrets to .env file, then run
bella secrets get --app java-01-dotenv-file -o .env
java -jar target/sample-01-dotenv-file-1.0.0.jar
```

> **Note:** The sample is built as a fat JAR (all dependencies bundled) via `maven-shade-plugin`.  
> Running `java -jar target/sample-01-dotenv-file-1.0.0.jar` requires no additional classpath setup.

## Works with any Java command

```bash
# Spring Boot jar
bella secrets get --app my-app -o .env && java -jar myapp.jar

# Spring Boot (Maven)
bella secrets get --app my-app -o .env && mvn spring-boot:run

# Quarkus (dev mode)
bella secrets get --app my-app -o .env && mvn quarkus:dev

# JUnit tests
bella secrets get --app my-app -o .env && mvn test

# Gradle
bella secrets get --app my-app -o .env && ./gradlew bootRun
```

## Spring Boot — load .env into System properties

```java
// In your main() before SpringApplication.run():
Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
dotenv.entries().forEach(e -> System.setProperty(e.getKey(), e.getValue()));
// Now @Value("${DATABASE_URL}") and application.properties ${DATABASE_URL} work
```

## Security notes

- Add `.env` to `.gitignore` — never commit secrets
- Prefer the `EnvironmentPostProcessor` approach (sample 03) for production Spring Boot apps

## Secret rotation

❌ **Not supported automatically.** The `.env` file is written once and read once at JVM startup via `Dotenv.load()`. Values in `System.env` are immutable after startup on standard JVMs.

**To pick up rotated secrets:** re-write the file and restart:

```bash
bella secrets get -o .env && java -jar target/myapp.jar
# or with Maven:
bella secrets get -o .env && mvn spring-boot:run
```

For automatic rotation without restarts, use the Spring Boot `EnvironmentPostProcessor` pattern (sample `03-spring-boot`) combined with Spring's `@RefreshScope` and Spring Cloud Config's refresh mechanism, or restart the JVM on rotation events.
