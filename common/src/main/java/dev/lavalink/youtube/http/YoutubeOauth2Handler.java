package dev.lavalink.youtube.http;

import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;
import com.grack.nanojson.JsonWriter;
import com.sedmelluq.discord.lavaplayer.tools.DataFormatTools;
import com.sedmelluq.discord.lavaplayer.tools.ExceptionTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import dev.lavalink.youtube.clients.skeleton.Client;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import dev.lavalink.youtube.db.MongoTokenProvider;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class YoutubeOauth2Handler {
    private static final Logger log = LoggerFactory.getLogger(YoutubeOauth2Handler.class);
    private static int fetchErrorLogCount = 0;

    // no, i haven't leaked anything of mine
    // this (i presume) can be found within youtube's page source
    // ¯\_(ツ)_/¯
    private static final String CLIENT_ID = "861556708454-d6dlm3lh05idd8npek18k6be8ba3oc68.apps.googleusercontent.com";
    private static final String CLIENT_SECRET = "SboVhoG9s0rNafixCSGGKXAT";
    private static final String SCOPES = "http://gdata.youtube.com https://www.googleapis.com/auth/youtube";
    private static final String OAUTH_FETCH_CONTEXT_ATTRIBUTE = "yt-oauth";
    public static final String OAUTH_INJECT_CONTEXT_ATTRIBUTE = "yt-oauth-token";

    private final HttpInterfaceManager httpInterfaceManager;

    private boolean enabled;
    private String refreshToken;

    private String tokenType;
    private String accessToken;
    private long tokenExpires;

    public YoutubeOauth2Handler(HttpInterfaceManager httpInterfaceManager) {
        this.httpInterfaceManager = httpInterfaceManager;
    }

    public void setRefreshToken(@Nullable String refreshToken, boolean skipInitialization) {
         List<String> tokens = MongoTokenProvider.getActiveTokens();
        Collections.shuffle(tokens);  
    for (String token : tokens) {
        this.refreshToken = token;
        this.tokenExpires = System.currentTimeMillis();
        this.accessToken = null;
        try {
    refreshAccessToken(true);
    MongoTokenProvider.updateLastUsed(token);
    enabled = true;
    return; // Success
} catch (RuntimeException ex) {
    if (ex.getMessage() != null && ex.getMessage().contains("401")) {
        MongoTokenProvider.markBanned(token);
        log.warn("Token banned, trying next one...");
    } else {
        log.error("Failed to refresh token: ", ex);
    }
}
    }

    enabled = false; // No working token
    log.error("No valid refresh token found in MongoDB.");
    }

    public boolean shouldRefreshAccessToken() {
        return enabled && !DataFormatTools.isNullOrEmpty(refreshToken) && (DataFormatTools.isNullOrEmpty(accessToken) || System.currentTimeMillis() >= tokenExpires);
    }

    @Nullable
    public String getRefreshToken() {
        return refreshToken;
    }

    public boolean isOauthFetchContext(HttpClientContext context) {
        return context.getAttribute(OAUTH_FETCH_CONTEXT_ATTRIBUTE) == Boolean.TRUE;
    }

    /**
     * Makes a request to YouTube for a device code that users can then authorise to allow
     * this source to make requests using an account access token.
     * This will begin the oauth flow. If a refresh token is present, {@link #refreshAccessToken(boolean)} should
     * be used instead.
     */
    private void initializeAccessToken() {
        JsonObject response = fetchDeviceCode();

        log.debug("fetch device code response: {}", JsonWriter.string(response));

        String verificationUrl = response.getString("verification_url");
        String userCode = response.getString("user_code");
        String deviceCode = response.getString("device_code");
        long interval = response.getLong("interval") * 1000;

        log.info("==================================================");
        log.info("!!! DO NOT AUTHORISE WITH YOUR MAIN ACCOUNT, USE A BURNER !!!");
        log.info("OAUTH INTEGRATION: To give youtube-source access to your account, go to {} and enter code {}", verificationUrl, userCode);
        log.info("!!! DO NOT AUTHORISE WITH YOUR MAIN ACCOUNT, USE A BURNER !!!");
        log.info("==================================================");

        // Should this be a daemon?
        new Thread(() -> pollForToken(deviceCode, interval == 0 ? 5000 : interval), "youtube-source-token-poller").start();
    }

    private JsonObject fetchDeviceCode() {
        // @formatter:off
        String requestJson = JsonWriter.string()
            .object()
                .value("client_id", CLIENT_ID)
                .value("scope", SCOPES)
                .value("device_id", UUID.randomUUID().toString().replace("-", ""))
                .value("device_model", "ytlr::")
            .end()
            .done();
        // @formatter:on

        HttpPost request = new HttpPost("https://www.youtube.com/o/oauth2/device/code");
        StringEntity body = new StringEntity(requestJson, ContentType.APPLICATION_JSON);
        request.setEntity(body);

        try (HttpInterface httpInterface = getHttpInterface();
             CloseableHttpResponse response = httpInterface.execute(request)) {
            HttpClientTools.assertSuccessWithContent(response, "device code fetch");
            return JsonParser.object().from(response.getEntity().getContent());
        } catch (IOException | JsonParserException e) {
            throw ExceptionTools.toRuntimeException(e);
        }
    }

    private void pollForToken(String deviceCode, long interval) {
        // @formatter:off
        String requestJson = JsonWriter.string()
            .object()
                .value("client_id", CLIENT_ID)
                .value("client_secret", CLIENT_SECRET)
                .value("code", deviceCode)
                .value("grant_type", "http://oauth.net/grant_type/device/1.0")
            .end()
            .done();
        // @formatter:on

        HttpPost request = new HttpPost("https://www.youtube.com/o/oauth2/token");
        StringEntity body = new StringEntity(requestJson, ContentType.APPLICATION_JSON);
        request.setEntity(body);

        while (true) {
            try (HttpInterface httpInterface = getHttpInterface();
                 CloseableHttpResponse response = httpInterface.execute(request)) {
                HttpClientTools.assertSuccessWithContent(response, "oauth2 token fetch");
                JsonObject parsed = JsonParser.object().from(response.getEntity().getContent());

                log.debug("oauth2 token fetch response: {}", JsonWriter.string(parsed));

                if (parsed.has("error") && !parsed.isNull("error")) {
                    String error = parsed.getString("error");

                    switch (error) {
                        case "authorization_pending":
                        case "slow_down":
                            Thread.sleep(interval);
                            continue;
                        case "expired_token":
                            log.error("OAUTH INTEGRATION: The device token has expired. OAuth integration has been canceled.");
                        case "access_denied":
                            log.error("OAUTH INTEGRATION: Account linking was denied. OAuth integration has been canceled.");
                        default:
                            log.error("Unhandled OAuth2 error: {}", error);
                    }

                    return;
                }

                updateTokens(parsed);
                log.info("OAUTH INTEGRATION: Token retrieved successfully. Store your refresh token as this can be reused. ({})", refreshToken);
                enabled = true;
                return;
            } catch (IOException | JsonParserException | InterruptedException e) {
                log.error("Failed to fetch OAuth2 token response", e);
            }
        }
    }

    /**
     * Refreshes an access token using a supplied refresh token.
     * @param force Whether to forcefully renew the access token, even if it doesn't necessarily
     *              need to be refreshed yet.
     */
    public void refreshAccessToken(boolean force) {
       log.debug("Refreshing access token (force: {})", force);

    synchronized (this) {
        for (String token : MongoTokenProvider.getActiveTokens()) {
            
            if (!force && !shouldRefreshAccessToken()) {
                log.debug("Access token does not need to be refreshed yet.");
                return;
            }

            try {
                JsonObject json = createNewAccessToken(token);
                this.refreshToken = token;
                updateTokens(json);
                MongoTokenProvider.updateLastUsed(token);
                log.info("✅ YouTube access token refreshed successfully using token ending in: {}...", token.substring(Math.max(0, token.length() - 5)));
                return;
            } catch (RuntimeException e) {
                if (e.getMessage().contains("401") || e.getMessage().toLowerCase().contains("invalid_grant")) {
                    MongoTokenProvider.markBanned(token);
                    log.warn("❌ Token banned or invalid. Marked and skipping: {}", token);
                } else {
                    log.error("⚠️ Failed to refresh token {} due to error: {}", token, e.getMessage());
                }
            } catch (Exception ex) {
                log.error("⚠️ General exception for token {}: {}", token, ex.getMessage());
            }
        }

        this.accessToken = null;
        this.tokenExpires = 0;
        this.enabled = false;
        log.error("❌ All refresh tokens failed. Lavalink cannot authenticate to YouTube.");
    }
    }


    /**
     * Executes the HTTP request to refresh the access token and returns the response.
     *
     * @param refreshToken The refresh token to be included in the request.
     * @return The JSON response as a JsonObject.
     */
    public JsonObject createNewAccessToken(String refreshToken) {

        // @formatter:off
        String requestJson = JsonWriter.string()
                .object()
                    .value("client_id", CLIENT_ID)
                    .value("client_secret", CLIENT_SECRET)
                    .value("refresh_token", refreshToken)
                    .value("grant_type", "refresh_token")
                .end()
                .done();
        // @formatter:on

        HttpPost request = new HttpPost("https://www.youtube.com/o/oauth2/token");
        StringEntity entity = new StringEntity(requestJson, ContentType.APPLICATION_JSON);
        request.setEntity(entity);

        try (HttpInterface httpInterface = getHttpInterface();
             CloseableHttpResponse response = httpInterface.execute(request)) {
            HttpClientTools.assertSuccessWithContent(response, "oauth2 token fetch");
            JsonObject parsed = JsonParser.object().from(response.getEntity().getContent());

            if (parsed.has("error") && !parsed.isNull("error")) {
                throw new RuntimeException("Refreshing access token returned error " + parsed.getString("error"));
            }

            return parsed;
        } catch (IOException | JsonParserException e) {
            throw ExceptionTools.toRuntimeException(e);
        }
    }

    private void updateTokens(JsonObject json) {
        long tokenLifespan = json.getLong("expires_in");
        tokenType = json.getString("token_type");
        accessToken = json.getString("access_token");
        refreshToken = json.getString("refresh_token", refreshToken);
        tokenExpires = System.currentTimeMillis() + (tokenLifespan * 1000) - 60000;

        log.debug("OAuth access token is {} and refresh token is {}. Access token expires in {} seconds.", accessToken, refreshToken, tokenLifespan);
    }

    public void applyToken(HttpUriRequest request) {
        if (!enabled || DataFormatTools.isNullOrEmpty(refreshToken)) {
            return;
        }

        if (shouldRefreshAccessToken()) {
            log.debug("Access token has expired, refreshing...");

            try {
                refreshAccessToken(false);
            } catch (Throwable t) {
                if (++fetchErrorLogCount <= 3) {
                    // log fetch errors up to 3 consecutive times to avoid spamming logs. in theory requests can still be made
                    // without an access token, but they are less likely to succeed. regardless, we shouldn't bloat a
                    // user's logs just in case YT changed something and broke oauth integration.
                    log.error("Refreshing YouTube access token failed", t);
                } else {
                    log.debug("Refreshing YouTube access token failed", t);
                }

                // retry in 15 seconds to avoid spamming YouTube with requests.
                tokenExpires = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(15);
                return;
            }

            fetchErrorLogCount = 0;
        }

        // check again to ensure updating worked as expected.
        if (accessToken != null && tokenType != null && System.currentTimeMillis() < tokenExpires) {
            log.debug("Using oauth authorization header with value \"{} {}\"", tokenType, accessToken);
            request.setHeader("Authorization", String.format("%s %s", tokenType, accessToken));
        }
    }

    public void applyToken(HttpUriRequest request, String token) {
        request.setHeader("Authorization", String.format("%s %s", "Bearer", token));
    }

    private HttpInterface getHttpInterface() {
        HttpInterface httpInterface = httpInterfaceManager.getInterface();
        httpInterface.getContext().setAttribute(OAUTH_FETCH_CONTEXT_ATTRIBUTE, true);
        return httpInterface;
    }
}
