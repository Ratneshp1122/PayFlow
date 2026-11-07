package dev.ratnesh.payflow;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
@RestController
public class Webhooks {
 final Payments p;final String secret,url;final boolean enabled;
 final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
 public Webhooks(Payments p,@Value("${payflow.webhook-secret}")String secret,@Value("${payflow.webhook-url}")String url,@Value("${payflow.workers:true}")boolean enabled){this.p=p;this.secret=secret;this.url=url;this.enabled=enabled;}
 public String sign(String timestamp,String body){try{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal((timestamp+"."+body).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
 void verify(String timestamp,String signature,String body){try{if(body.length()>65536 || Math.abs(Math.subtractExact(Instant.now().getEpochSecond(),Long.parseLong(timestamp)))>300 || !MessageDigest.isEqual(sign(timestamp,body).getBytes(StandardCharsets.UTF_8),signature.getBytes(StandardCharsets.UTF_8)))throw new IllegalArgumentException();}catch(Exception e){throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);}}
 @PostMapping("/provider/webhook") public void incoming(@RequestHeader("X-Timestamp")String ts,@RequestHeader("X-Signature")String signature,@RequestBody String body)throws Exception{
  verify(ts,signature,body);var node=p.json.readTree(body);UUID event=UUID.fromString(node.path("eventId").asText());UUID op=UUID.fromString(node.path("operationId").asText());
  p.tx.executeWithoutResult(s->{int added=p.db.update("INSERT INTO webhook_inbox(event_id,body_hash) VALUES (?,?) ON CONFLICT DO NOTHING",event,Rules.hash(body));if(added==0&&!Rules.hash(body).equals(p.db.queryForObject("SELECT body_hash FROM webhook_inbox WHERE event_id=?",String.class,event)))throw new ResponseStatusException(HttpStatus.CONFLICT,"event identity conflict");
   // Callbacks only wake authoritative reconciliation. Their claimed status cannot undo a terminal payment.
   p.db.update("UPDATE operation SET lease_until=now() WHERE id=? AND state='UNKNOWN'",op);
  });
 }
 @PostMapping("/simulator/merchant-webhook")public void receiver(@RequestHeader("X-Timestamp")String ts,@RequestHeader("X-Signature")String signature,@RequestBody String body){verify(ts,signature,body);}
 @KafkaListener(topics="payflow.lifecycle.v1",autoStartup="${payflow.workers:true}")public void consume(String body)throws Exception{UUID event=UUID.fromString(p.json.readTree(body).path("eventId").asText());p.tx.executeWithoutResult(s->{if(p.db.update("INSERT INTO consumer_receipt(event_id) VALUES (?) ON CONFLICT DO NOTHING",event)==1)p.db.update("INSERT INTO webhook_delivery(event_id,payload) VALUES (?,?)",event,body);});}
 @Scheduled(fixedDelay=200)public void deliver(){if(!enabled)return;
  var claims=p.tx.execute(s->p.db.queryForList("UPDATE webhook_delivery SET next_attempt=now()+interval '30 seconds',token=token+1,attempts=attempts+1 WHERE event_id IN (SELECT event_id FROM webhook_delivery WHERE NOT delivered AND next_attempt<=now() AND attempts<20 LIMIT 16 FOR UPDATE SKIP LOCKED) RETURNING *"));
  for(var row:claims){boolean ok=false;try{String ts=Long.toString(Instant.now().getEpochSecond()),body=row.get("payload").toString();var request=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(2)).header("Content-Type","application/json").header("X-Timestamp",ts).header("X-Signature",sign(ts,body)).POST(HttpRequest.BodyPublishers.ofString(body)).build();int code=http.send(request,HttpResponse.BodyHandlers.discarding()).statusCode();ok=code>=200&&code<300;}catch(Exception ignored){}long seconds=Math.min(3600,1L<<Math.min(12,((Number)row.get("attempts")).intValue()));p.db.update("UPDATE webhook_delivery SET delivered=?,next_attempt=now()+(? * interval '1 second') WHERE event_id=? AND token=?",ok,seconds,row.get("event_id"),row.get("token"));}
 }
}
