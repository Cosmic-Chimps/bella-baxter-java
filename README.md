# Bella Baxter Java SDK

Java client for [Bella Baxter](https://github.com/cosmic-chimps/bella-baxter) — load secrets into your Java application at startup with optional end-to-end encryption.

[![Maven Central](https://img.shields.io/maven-central/v/io.bella-baxter/bella-baxter-sdk)](https://central.sonatype.com/artifact/io.bella-baxter/bella-baxter-sdk)

## Features

- **Simple API** — one `BaxterClient.create()` call, then `getAllSecrets()` or `injectEnv()`
- **End-to-end encryption** — optional E2EE using ECDH-P256-HKDF-SHA256-AES256GCM; the server never sees plaintext secrets in transit
- **ENV injection** — `injectEnv()` respects existing values (local dev overrides work)
- **Webhook signature verification** — `WebhookSignatureVerifier` validates Bella webhook payloads
- **Spring Boot & Quarkus integration** — samples included for both frameworks

## Installation

**Maven:**
```xml
<dependency>
    <groupId>io.bella-baxter</groupId>
    <artifactId>bella-baxter-sdk</artifactId>
    <version>VERSION</version>
</dependency>
```

**Gradle:**
```gradle
implementation 'io.bella-baxter:bella-baxter-sdk:VERSION'
```

## Quick start

```java
import io.bellabaxter.BaxterClient;
import io.bellabaxter.BaxterClientOptions;

var options = new BaxterClientOptions();
options.setBaxterURL("https://baxter.example.com");
options.setApiKey("bax-...");

try (var client = BaxterClient.create(options)) {
    var secrets = client.getAllSecrets("my-project", "production");
    System.out.println(secrets.get("DATABASE_URL"));
}
```

## ENV injection

Load secrets directly into system properties before starting your server:

```java
public static void main(String[] args) {
    var options = new BaxterClientOptions();
    options.setBaxterURL(System.getenv("BELLA_BAXTER_URL"));
    options.setApiKey(System.getenv("BELLA_API_KEY"));

    try (var client = BaxterClient.create(options)) {
        // Inject into System.getenv — existing values are NOT overwritten (local dev wins)
        client.injectEnv("my-project", "production");
    }

    // From here, System.getenv("DATABASE_URL") works as expected
    SpringApplication.run(App.class, args);
}
```

## Options

| Option | Default | Description |
|--------|---------|-------------|
| `baxterURL` | `https://api.bella-baxter.io` | Base URL of the Bella Baxter API |
| `apiKey` | — | API key (starts with `bax-`). Obtain from WebApp → Project → API Keys |
| `timeout` | `10s` | Per-request HTTP timeout |
| `enableE2EE` | `false` | Enable end-to-end encryption for secrets responses |

## Samples

| Sample | Approach | Best for |
|--------|----------|---------|
| [01-dotenv-file](./samples/01-dotenv-file/) | CLI → `.env` file | Scripts, CI/CD |
| [02-process-inject](./samples/02-process-inject/) | `bella run --` | Zero Java deps |
| [03-spring-boot](./samples/03-spring-boot/) | SDK → Spring `EnvironmentPostProcessor` | Spring Boot apps |
| [04-quarkus](./samples/04-quarkus/) | SDK → Quarkus startup | Quarkus apps |

## License

Apache 2.0 — see [LICENSE](https://github.com/Cosmic-Chimps/bella-baxter-java/blob/main/LICENSE) for details.
