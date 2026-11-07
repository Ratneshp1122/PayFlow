package dev.ratnesh.payflow;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
public final class Rules {
 private Rules() {}
 public static void money(long amount,String currency) {
  if(amount<1 || amount>1_000_000_000_000L || currency==null || !Set.of("INR","USD","EUR").contains(currency)) throw new IllegalArgumentException("invalid amount or currency");
 }
 public static long available(long amount,long refunded,long reserved) {return Math.subtractExact(Math.subtractExact(amount,refunded),reserved);}
 public static String hash(String value) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
