package dev.ratnesh.payflow;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
@Configuration
class Security {
 @Bean UserDetailsService users(@Value("${payflow.merchant-password}")String merchant,@Value("${payflow.operator-password}")String operator){
  var encoder=new BCryptPasswordEncoder();return new InMemoryUserDetailsManager(User.withUsername("merchant-demo").password(encoder.encode(merchant)).roles("MERCHANT").build(),User.withUsername("operator").password(encoder.encode(operator)).roles("OPERATOR").build());
 }
 @Bean SecurityFilterChain chain(HttpSecurity http)throws Exception{return http.csrf(c->c.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS)).authorizeHttpRequests(a->a.requestMatchers("/actuator/health","/provider/webhook","/simulator/merchant-webhook").permitAll().requestMatchers("/ops/**","/actuator/**").hasRole("OPERATOR").anyRequest().hasRole("MERCHANT")).httpBasic(c->{}).build();}
}
