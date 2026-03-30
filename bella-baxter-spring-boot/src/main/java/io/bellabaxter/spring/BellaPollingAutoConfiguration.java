package io.bellabaxter.spring;

import io.bellabaxter.BaxterClient;
import io.bellabaxter.BaxterClientOptions;
import io.bellabaxter.BellaPollingProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Spring Boot auto-configuration for Bella Baxter.
 *
 * <p>Activated when {@code bellabaxter.api-key} is set in application.properties (or equivalent).
 *
 * <p>When {@code bellabaxter.polling.enabled=true}, creates a {@link BellaPollingProvider}
 * and registers a {@link BellaSecretsPropertySource} that auto-refreshes the Spring
 * {@link ConfigurableEnvironment} when secrets change.
 *
 * <p>If {@code spring-cloud-context} is on the classpath, a {@code ContextRefresher} bean
 * is injected to trigger {@code @RefreshScope} bean re-creation on change.
 *
 * <h3>Minimal configuration ({@code application.properties})</h3>
 * <pre>
 * bellabaxter.url=https://baxter.example.com
 * bellabaxter.api-key=bax-...
 * </pre>
 *
 * <h3>With polling</h3>
 * <pre>
 * bellabaxter.url=https://baxter.example.com
 * bellabaxter.api-key=bax-...
 * bellabaxter.polling.enabled=true
 * bellabaxter.polling.interval-seconds=30
 * bellabaxter.polling.fallback-on-error=true
 * </pre>
 *
 * <h3>Usage in a Spring bean</h3>
 * <pre>{@code
 * @RefreshScope   // re-created when secrets change (requires spring-cloud-context)
 * @Service
 * public class MyService {
 *
 *     @Value("${DATABASE_URL}")
 *     private String databaseUrl;
 * }
 * }</pre>
 */
@AutoConfiguration
@EnableConfigurationProperties(BellaProperties.class)
@ConditionalOnProperty(prefix = "bellabaxter", name = "api-key")
public class BellaPollingAutoConfiguration {

    private final BellaProperties props;
    private final ConfigurableEnvironment environment;

    public BellaPollingAutoConfiguration(BellaProperties props, ConfigurableEnvironment environment) {
        this.props       = props;
        this.environment = environment;
    }

    @Bean
    @ConditionalOnMissingBean
    public BaxterClient baxterClient() {
        BaxterClientOptions options = new BaxterClientOptions.Builder()
                .baxterUrl(props.getUrl())
                .apiKey(props.getApiKey())
                .timeoutSeconds(props.getTimeoutSeconds())
                .pollingEnabled(props.getPolling().isEnabled())
                .pollingInterval(props.getPolling().getInterval())
                .fallbackOnError(props.getPolling().isFallbackOnError())
                .build();
        return new BaxterClient(options);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "bellabaxter.polling", name = "enabled", havingValue = "true")
    public BellaPollingProvider bellaPollingProvider(BaxterClient client) {
        BellaPollingProvider poller = new BellaPollingProvider(client, new BaxterClientOptions.Builder()
                .baxterUrl(props.getUrl())
                .apiKey(props.getApiKey())
                .pollingEnabled(true)
                .pollingInterval(props.getPolling().getInterval())
                .fallbackOnError(props.getPolling().isFallbackOnError())
                .build());

        BellaSecretsPropertySource propertySource = new BellaSecretsPropertySource(poller);
        registerRefreshCallback(propertySource);
        environment.getPropertySources().addFirst(propertySource);

        poller.start();
        return poller;
    }

    @Bean
    @ConditionalOnMissingBean(BellaSecretsPropertySource.class)
    @ConditionalOnProperty(prefix = "bellabaxter.polling", name = "enabled", havingValue = "false", matchIfMissing = true)
    public BellaSecretsPropertySource bellaSecretsPropertySource(BaxterClient client) {
        BellaSecretsPropertySource propertySource = new BellaSecretsPropertySource(client);
        environment.getPropertySources().addFirst(propertySource);
        return propertySource;
    }

    /**
     * Wire in {@code ContextRefresher} if spring-cloud-context is on the classpath.
     * Reflective call to avoid a hard compile-time dependency.
     */
    private void registerRefreshCallback(BellaSecretsPropertySource propertySource) {
        try {
            Class<?> refresherClass = Class.forName(
                    "org.springframework.cloud.context.refresh.ContextRefresher");
            // ContextRefresher bean will be injected by Spring if present; use ApplicationContext lookup
            propertySource.setOnChangeCallback(() -> {
                // no-op here; actual wiring done in BellaRefreshScopeAdapter (conditionally loaded)
            });
        } catch (ClassNotFoundException ignored) {
            // spring-cloud-context not on classpath — @RefreshScope not supported, no-op
        }
    }
}
