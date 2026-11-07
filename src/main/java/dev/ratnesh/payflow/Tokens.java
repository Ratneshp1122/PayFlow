package dev.ratnesh.payflow;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Configuration
class TokenConfiguration {
  @Bean
  SecretKey signingKey(@Value("${payflow.jwt-secret}") String secret) {
    if (secret.getBytes(StandardCharsets.UTF_8).length < 32)
      throw new IllegalArgumentException("JWT_SECRET must contain at least 32 bytes");
    return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
  }

  @Bean
  JwtEncoder encoder(SecretKey key) {
    return new NimbusJwtEncoder(new ImmutableSecret<>(key));
  }

  @Bean
  JwtDecoder decoder(SecretKey key) {
    var decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("payflow-local"));
    return decoder;
  }
}

@RestController
public class Tokens {
  private final JwtEncoder encoder;

  public Tokens(JwtEncoder encoder) {
    this.encoder = encoder;
  }

  @PostMapping("/auth/token")
  public Map<String, Object> token(Authentication authentication) {
    if (!(authentication instanceof UsernamePasswordAuthenticationToken))
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Use HTTP Basic credentials to obtain a new token");
    Instant now = Instant.now();
    var roles =
        authentication.getAuthorities().stream()
            .map(a -> a.getAuthority().replaceFirst("^ROLE_", ""))
            .toList();
    var claims =
        JwtClaimsSet.builder()
            .issuer("payflow-local")
            .subject(authentication.getName())
            .issuedAt(now)
            .expiresAt(now.plusSeconds(900))
            .claim("roles", roles)
            .build();
    return Map.of(
        "access_token",
        encoder
            .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
            .getTokenValue(),
        "token_type",
        "Bearer",
        "expires_in",
        900);
  }
}
