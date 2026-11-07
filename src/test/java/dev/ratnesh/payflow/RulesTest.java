package dev.ratnesh.payflow;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class RulesTest {
  @Test
  void rejectsInvalidMoney() {
    assertThrows(IllegalArgumentException.class, () -> Rules.money(-1, "INR"));
    assertThrows(IllegalArgumentException.class, () -> Rules.money(10, "XXX"));
    assertThrows(IllegalArgumentException.class, () -> Rules.money(Long.MAX_VALUE, "INR"));
  }

  @Test
  void refundReservationConservesBalance() {
    assertEquals(5000, Rules.available(25000, 0, 20000));
    assertEquals(5000, Rules.available(25000, 20000, 0));
    assertThrows(ArithmeticException.class, () -> Rules.available(Long.MIN_VALUE, 1, 0));
  }

  @Test
  void canonicalRequestHashChangesWithMoney() {
    assertNotEquals(Rules.hash("100:INR"), Rules.hash("100:USD"));
    assertEquals(Rules.hash("100:INR"), Rules.hash("100:INR"));
  }
}
