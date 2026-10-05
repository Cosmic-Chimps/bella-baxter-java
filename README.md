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

var options = new BaxterClientOptions.Builder()
    .baxterUrl("https://baxter.example.com")
    .apiKey("bax-...")               // project + environment are discovered from the key
    .build();

try (var client = new BaxterClient(options)) {
    var secrets = client.getAllSecrets().getSecrets();
    System.out.println(secrets.get("DATABASE_URL"));
}
```

## ENV injection

Load secrets directly into system properties before starting your server:

```java
public static void main(String[] args) {
    var options = new BaxterClientOptions.Builder()
        .baxterUrl(System.getenv("BELLA_BAXTER_URL"))
        .apiKey(System.getenv("BELLA_BAXTER_API_KEY"))
        .build();

    try (var client = new BaxterClient(options)) {
        // Java cannot modify System.getenv(); expose the secrets as system properties instead.
        // Existing properties are NOT overwritten (local dev wins).
        client.getAllSecrets().getSecrets().forEach((k, v) -> {
            if (System.getProperty(k) == null) System.setProperty(k, v);
        });
    }

    // From here, System.getProperty("DATABASE_URL") works as expected
    SpringApplication.run(App.class, args);
}
```

## Options

`new BaxterClientOptions.Builder()` methods:

| Option | Default | Description |
|--------|---------|-------------|
| `baxterUrl(String)` | `https://api.bella-baxter.io` | Base URL of the Bella Baxter API |
| `apiKey(String)` | — | API key (starts with `bax-`). Obtain from WebApp → Project → API Keys. Mutually exclusive with `bearerToken` |
| `bearerToken(String)` | — | JWT access token (what `bella sdk run` injects in SSO mode) |
| `projectSlug(String)` / `environmentSlug(String)` | — | Required with `bearerToken`; discovered from the key via `/api/v1/keys/me` with `apiKey` |
| `timeoutSeconds(int)` | `10` | Per-request HTTP timeout |
| `pollingEnabled(boolean)` / `pollingInterval(Duration)` | `false` / `60s` | Background polling for `BellaPollingProvider` |
| `fallbackOnError(boolean)` | `true` | Keep the last good secrets when a poll fails |
| `privateKeyPem(String)` | `BELLA_BAXTER_PRIVATE_KEY` | Persistent device key (ZKE) presented as `X-E2E-Public-Key`; without one an ephemeral key is generated |
| `onWrappedDekReceived(BiConsumer)` | — | Called with the wrapped DEK / lease when the server returns one |

End-to-end encryption is always on: there is no option to enable or disable it. The client presents its key on
every read that carries secret values and refuses a plaintext, tampered or wrong-key answer with
`E2EEResponseException` (`apps/sdk/SDK_CONTRACT.md`).

## Samples

| Sample | Approach | Best for |
|--------|----------|---------|
| [01-dotenv-file](./samples/01-dotenv-file/) | CLI → `.env` file | Scripts, CI/CD |
| [02-process-inject](./samples/02-process-inject/) | `bella run --` | Zero Java deps |
| [03-spring-boot](./samples/03-spring-boot/) | SDK → Spring `EnvironmentPostProcessor` | Spring Boot apps |
| [04-quarkus](./samples/04-quarkus/) | SDK → Quarkus startup | Quarkus apps |

## License

Apache 2.0 — see [LICENSE](https://github.com/Cosmic-Chimps/bella-baxter-java/blob/main/LICENSE) for details.
