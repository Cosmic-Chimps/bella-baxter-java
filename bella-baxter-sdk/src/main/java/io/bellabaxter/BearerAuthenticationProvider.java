package io.bellabaxter;

import com.microsoft.kiota.RequestInformation;
import com.microsoft.kiota.authentication.AuthenticationProvider;

import java.util.Map;

/**
 * Kiota {@link AuthenticationProvider} that authenticates requests with an OAuth2 Bearer token.
 *
 * <p>Used by {@link BaxterClient} when constructed with a JWT access token
 * (e.g. injected by {@code bella sdk run} in OAuth2 mode via {@code BELLA_BAXTER_ACCESS_TOKEN}).
 *
 * <p>Unlike HMAC API keys, Bearer tokens expire — polling is not supported in this mode.
 */
public final class BearerAuthenticationProvider implements AuthenticationProvider {

    private final String accessToken;

    /**
     * @param accessToken the raw OAuth2 JWT access token
     */
    public BearerAuthenticationProvider(String accessToken) {
        if (accessToken == null || accessToken.isBlank())
            throw new IllegalArgumentException("accessToken must not be blank");
        this.accessToken = accessToken;
    }

    @Override
    public void authenticateRequest(RequestInformation request,
                                    Map<String, Object> additionalAuthenticationContext) {
        request.headers.add("Authorization", "Bearer " + accessToken);
    }
}
