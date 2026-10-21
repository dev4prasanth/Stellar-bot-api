package com.stellartrack.betfair.session;

import com.stellartrack.betfair.config.BetfairProperties;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.springframework.stereotype.Component;

/**
 * HTTP clients for Betfair. The certificate-login client presents the application's client certificate
 * (PKCS#12 keystore from secure configuration); the API client uses standard server-authenticated TLS.
 */
@Component
public class BetfairHttpClients {

    private final BetfairProperties properties;
    private volatile HttpClient apiClient;
    private volatile HttpClient certificateClient;

    public BetfairHttpClients(BetfairProperties properties) {
        this.properties = properties;
    }

    public HttpClient api() {
        if (apiClient == null) {
            synchronized (this) {
                if (apiClient == null) {
                    apiClient = HttpClient.newBuilder()
                            .connectTimeout(properties.connectTimeout())
                            .version(HttpClient.Version.HTTP_1_1)
                            .build();
                }
            }
        }
        return apiClient;
    }

    public HttpClient certificateLogin() {
        if (certificateClient == null) {
            synchronized (this) {
                if (certificateClient == null) {
                    certificateClient = HttpClient.newBuilder()
                            .connectTimeout(properties.connectTimeout())
                            .sslContext(clientCertificateContext())
                            .build();
                }
            }
        }
        return certificateClient;
    }

    public SSLContext clientCertificateContext() {
        char[] password = properties.certKeystorePassword() == null ? new char[0]
                : properties.certKeystorePassword().toCharArray();
        try (InputStream in = Files.newInputStream(Path.of(properties.certKeystore()))) {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(in, password);
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, password);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(kmf.getKeyManagers(), null, null);
            return context;
        } catch (IOException | GeneralSecurityException e) {
            // The exception message may contain the path but never the password.
            throw new IllegalStateException("Cannot load Betfair client certificate keystore", e);
        }
    }
}
