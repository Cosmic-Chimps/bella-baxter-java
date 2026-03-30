package io.bellabaxter.spring;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.context.refresh.ContextRefresher;
import org.springframework.context.annotation.Bean;

/**
 * Wires {@link ContextRefresher} into {@link BellaSecretsPropertySource} when
 * {@code spring-cloud-context} is on the classpath.
 *
 * <p>This enables {@code @RefreshScope} beans to be re-created automatically whenever
 * Bella Baxter secrets change.
 *
 * <p>This configuration is a <em>separate</em> auto-configuration class from
 * {@link BellaPollingAutoConfiguration} so that it is only loaded when
 * {@code ContextRefresher} is available — preventing a {@link ClassNotFoundException}
 * at startup when spring-cloud-context is absent.
 */
@AutoConfiguration(after = BellaPollingAutoConfiguration.class)
@ConditionalOnClass(ContextRefresher.class)
@ConditionalOnProperty(prefix = "bellabaxter.polling", name = "enabled", havingValue = "true")
@ConditionalOnBean(BellaSecretsPropertySource.class)
public class BellaRefreshScopeConfiguration {

    @Bean
    public Object bellaRefreshScopeWiring(
            BellaSecretsPropertySource propertySource,
            ContextRefresher contextRefresher) {

        propertySource.setOnChangeCallback(() -> {
            try {
                contextRefresher.refresh();
            } catch (Exception e) {
                // log but don't crash the polling thread
                System.getLogger(BellaRefreshScopeConfiguration.class.getName())
                        .log(System.Logger.Level.WARNING,
                                "bella-poller: ContextRefresher.refresh() failed", e);
            }
        });

        return "bellaRefreshScopeWiringMarker"; // marker bean, name just needs to be unique
    }
}
