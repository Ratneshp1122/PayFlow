package dev.ratnesh.payflow;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class RateLimit {
  private final StringRedisTemplate redis;
  private final int limit;
  private final DefaultRedisScript<Long> script =
      new DefaultRedisScript<>(
          "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('PEXPIRE',KEYS[1],1000) end;"
              + " return n",
          Long.class);

  public RateLimit(StringRedisTemplate redis, @Value("${payflow.rate-limit}") int limit) {
    this.redis = redis;
    this.limit = limit;
  }

  public void check(String merchant) {
    try {
      Long n = redis.execute(script, List.of("payflow:rate:" + merchant));
      if (n == null) throw new IllegalStateException();
      if (n > limit) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
    } catch (ResponseStatusException e) {
      throw e;
    } catch (Exception e) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "rate limiter unavailable");
    }
  }
}
