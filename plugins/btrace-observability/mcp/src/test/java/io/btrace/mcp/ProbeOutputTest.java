package io.btrace.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ProbeOutputTest {
  @Test
  void drainReturnsOnlyOutputSinceTheLastRead() {
    ProbeOutput output = new ProbeOutput(1024);
    output.append("chargeCard 312ms\n");
    output.append("chargeCard 287ms\n");

    assertEquals("chargeCard 312ms\nchargeCard 287ms\n", output.drain());
    assertEquals("", output.drain());

    output.append("chargeCard 341ms\n");
    assertEquals("chargeCard 341ms\n", output.drain());
  }

  @Test
  void joinsALinePrintedInPieces() {
    ProbeOutput output = new ProbeOutput(1024);
    output.append("validateOrder ");
    output.append("threw\n");

    assertEquals("validateOrder threw\n", output.drain());
  }

  @Test
  void keepsTheNewestWholeLinesAndReportsWhatItDropped() {
    ProbeOutput output = new ProbeOutput(20);
    for (int i = 1; i <= 9; i++) {
      output.append("line-" + i + "\n");
    }

    String text = output.drain();
    assertTrue(text.endsWith("line-8\nline-9\n"), text);
    assertFalse(text.contains("line-7"), text);
    assertTrue(text.startsWith("[7 earlier lines dropped"), text);
    assertEquals("", output.drain(), "the dropped-lines note is reported once");
  }

  @Test
  void waitsForTheProbeToFinish() throws Exception {
    ProbeOutput output = new ProbeOutput(1024);
    assertFalse(output.awaitFinished(10));

    output.finish();
    assertTrue(output.awaitFinished(10));
  }

  @Test
  void waitsForOutputUntilItArrives() throws Exception {
    ProbeOutput output = new ProbeOutput(1024);
    Thread printer =
        new Thread(
            () -> {
              try {
                Thread.sleep(200);
              } catch (InterruptedException e) {
                return;
              }
              output.append("refreshSession 412ms\n");
            });
    printer.start();

    long start = System.nanoTime();
    assertEquals("refreshSession 412ms\n", output.drainAwaiting(5_000));
    assertTrue(System.nanoTime() - start < 4_000_000_000L, "returned as soon as output arrived");
    printer.join();
  }

  @Test
  void stopsWaitingAtTheTimeoutOrWhenTheProbeFinishes() throws Exception {
    ProbeOutput output = new ProbeOutput(1024);
    long start = System.nanoTime();
    assertEquals("", output.drainAwaiting(150));
    assertTrue(System.nanoTime() - start >= 140_000_000L, "waited for the timeout");

    output.finish();
    start = System.nanoTime();
    assertEquals("", output.drainAwaiting(5_000));
    assertTrue(System.nanoTime() - start < 1_000_000_000L, "a finished probe prints nothing more");
  }

  @Test
  void collectsAReportPrintedLineByLine() throws Exception {
    ProbeOutput output = new ProbeOutput(1024);
    Thread printer =
        new Thread(
            () -> {
              try {
                Thread.sleep(100);
                for (String line : new String[] {"calls=196\n", "errors=31\n", "max ms=480\n"}) {
                  output.append(line);
                  Thread.sleep(20);
                }
              } catch (InterruptedException e) {
                // test ends
              }
            });
    printer.start();

    assertEquals("calls=196\nerrors=31\nmax ms=480\n", output.drainAwaiting(5_000));
    printer.join();
  }
}
