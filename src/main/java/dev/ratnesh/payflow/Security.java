package dev.ratnesh.payflow;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class Security {
  @Bean
  UserDetailsService users(
      @Value("${payflow.merchant-password}") String merchant,
      @Value("${payflow.operator-password}") String operator) {
    var encoder = new BCryptPasswordEncoder();
    return new InMemoryUserDetailsManager(
        User.withUsername("merchant-demo")
            .password("{bcrypt}" + encoder.encode(merchant))
            .roles("MERCHANT")
            .build(),
        User.withUsername("operator")
            .password("{bcrypt}" + encoder.encode(operator))
            .roles("OPERATOR")
            .build());
  }

  @Bean
  SecurityFilterChain chain(HttpSecurity http) throws Exception {
    var authorities =
        new org.springframework.security.oauth2.server.resource.authentication
            .JwtGrantedAuthoritiesConverter();
    authorities.setAuthoritiesClaimName("roles");
    authorities.setAuthorityPrefix("ROLE_");
    var converter =
        new org.springframework.security.oauth2.server.resource.authentication
            .JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(authorities);
    return http.csrf(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        "/actuator/health", "/provider/webhook", "/simulator/merchant-webhook")
                    .permitAll()
                    .requestMatchers("/auth/token")
                    .authenticated()
                    .requestMatchers("/ops/**", "/actuator/**")
                    .hasRole("OPERATOR")
                    .anyRequest()
                    .hasRole("MERCHANT"))
        .httpBasic(c -> {})
        .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter)))
        .build();
  }
}
