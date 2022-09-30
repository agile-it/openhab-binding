package com.qubular.openhab.binding.vicare.internal.tokenstore;

import com.google.gson.*;
import com.qubular.openhab.binding.vicare.internal.CryptUtil;
import com.qubular.vicare.TokenStore;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.Optional;

import static java.util.Optional.empty;
import static java.util.Optional.ofNullable;

@Component(service = TokenStore.class)
public class PersistedTokenStore implements TokenStore {
    private static final String TOKEN_STORE_PID = "com.qubular.openhab.binding.vicare.PersistedTokenStore";
    private static final String PROPERTY_ACCESS_TOKEN = "accessToken";
    private static final String PROPERTY_SECURE_ACCESS_TOKEN = "secureAccessToken";
    private static final String PROPERTY_REFRESH_TOKEN = "refreshToken";
    private static final String PROPERTY_SECURE_REFRESH_TOKEN = "secureRefreshToken";

    private final Logger logger = LoggerFactory.getLogger(PersistedTokenStore.class);
    private final ConfigurationAdmin configurationAdmin;
    private final CryptUtil cryptUtil;

    @Activate
    public PersistedTokenStore(@Reference ConfigurationAdmin configurationAdmin) {
        this.configurationAdmin = configurationAdmin;
        try {
            this.cryptUtil = new CryptUtil(configurationAdmin.getConfiguration(TOKEN_STORE_PID));
            migratePlainTextTokens();
        } catch (IOException | GeneralSecurityException e) {
            throw new RuntimeException("Unable to write configuration.", e);
        }
    }

    private void migratePlainTextTokens() throws IOException, GeneralSecurityException {
        Configuration configuration = configurationAdmin.getConfiguration(TOKEN_STORE_PID);
        Dictionary<String, Object> properties = configuration.getProperties();
        String accessToken = (String) properties.get(PROPERTY_ACCESS_TOKEN);
        String refreshToken = (String) properties.get(PROPERTY_REFRESH_TOKEN);
        if (accessToken != null || refreshToken != null) {
            logger.info("Encrypting tokens");
            if (accessToken != null) {
                properties.put(PROPERTY_SECURE_ACCESS_TOKEN, cryptUtil.encrypt(accessToken));
                properties.remove(PROPERTY_ACCESS_TOKEN);
            }
            if (refreshToken != null) {
                properties.put(PROPERTY_SECURE_REFRESH_TOKEN, cryptUtil.encrypt(refreshToken));
                properties.remove(PROPERTY_REFRESH_TOKEN);
            }
            configuration.update(properties);
        }
    }

    @Override
    public AccessToken storeAccessToken(String accessToken, Instant expiry) {
        AccessToken token = new AccessToken(accessToken, expiry);
        try {
            Configuration configuration = configurationAdmin.getConfiguration(TOKEN_STORE_PID);
            if (configuration != null) {
                Dictionary<String, Object> props = ofNullable(configuration.getProperties()).orElseGet(Hashtable::new);
                String json = gson().toJson(token);
                props.put(PROPERTY_SECURE_ACCESS_TOKEN, cryptUtil.encrypt(json));
                configuration.update(props);
            }
        } catch (IOException | GeneralSecurityException e) {
            logger.warn("Unable to store access token", e);
        }
        return token;
    }

    private Gson gson() {
        return new GsonBuilder()
                .setFieldNamingPolicy(FieldNamingPolicy.IDENTITY)
                .registerTypeAdapter(Instant.class, new InstantDeserializer())
                .registerTypeAdapter(Instant.class, new InstantSerializer())
                .create();
    }

    private static class InstantDeserializer implements JsonDeserializer<Instant> {
        @Override
        public Instant deserialize(JsonElement jsonElement, Type type, JsonDeserializationContext jsonDeserializationContext) throws JsonParseException {
            return Instant.parse(jsonElement.getAsString());
        }
    }

    private static class InstantSerializer implements JsonSerializer<Instant> {
        @Override
        public JsonElement serialize(Instant instant, Type type, JsonSerializationContext jsonSerializationContext) {
            return jsonSerializationContext.serialize(instant.toString());
        }
    }
    @Override
    public void storeRefreshToken(String refreshToken) {
        try {
            Configuration configuration = configurationAdmin.getConfiguration(TOKEN_STORE_PID);
            if (configuration != null) {
                Dictionary<String, Object> props = ofNullable(configuration.getProperties()).orElseGet(Hashtable::new);
                String encrypted = cryptUtil.encrypt(refreshToken);
                props.put(PROPERTY_SECURE_REFRESH_TOKEN, encrypted);
                configuration.update(props);
            }
        } catch (IOException | GeneralSecurityException e) {
            logger.warn("Unable to store refresh token", e);
        }
    }

    @Override
    public Optional<AccessToken> getAccessToken() {
        try {
            Configuration configuration = configurationAdmin.getConfiguration(TOKEN_STORE_PID);
            if (configuration != null) {
                Dictionary<String, Object> props = ofNullable(configuration.getProperties()).orElseGet(Hashtable::new);
                String encryptedToken = (String) props.get(PROPERTY_SECURE_ACCESS_TOKEN);
                if (encryptedToken != null) {
                    String decryptedToken = cryptUtil.decrypt(encryptedToken);
                    return Optional.of(gson().fromJson(decryptedToken, AccessToken.class));
                }
                return empty();
            }
        } catch (IOException e) {
            logger.warn("Unable to fetch access token from store.", e);
        } catch (GeneralSecurityException e) {
            logger.warn("Unable to decrypt stored access token.", e);
        }
        return Optional.empty();
    }

    @Override
    public Optional<String> getRefreshToken() {
        try {
            Configuration configuration = configurationAdmin.getConfiguration(TOKEN_STORE_PID);
            if (configuration != null) {
                Optional<String> encryptedRefreshToken = ofNullable(configuration.getProperties())
                        .map(c -> (String) c.get(PROPERTY_REFRESH_TOKEN));
                if (encryptedRefreshToken.isPresent()) {
                    return Optional.of(cryptUtil.decrypt(encryptedRefreshToken.get()));
                }
            }
        } catch (IOException e) {
            logger.warn("Unable to fetch refresh token from store.");
        } catch (GeneralSecurityException e) {
            logger.warn("Unable to decrypt refresh token.");
        }
        return Optional.empty();
    }
}
