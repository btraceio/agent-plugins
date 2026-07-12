package io.btrace.mcp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.btrace.client.Client;
import io.btrace.core.comm.Command;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BTraceClientTest {
  @Test
  void invokesTheMaskedClientContractReflectively() throws Exception {
    BTraceClient client = BTraceClient.create(2020);
    Client delegate = Client.last;
    StringWriter errors = new StringWriter();

    assertEquals(2020, delegate.port);
    assertArrayEquals(
        "Trace.java:class Trace:cp".getBytes(),
        client.compileSource("Trace.java", "class Trace", "cp", new PrintWriter(errors)));
    assertEquals("Generated:expression", client.onelinerSource("expression", "Generated"));

    client.attach("42", "system", "boot");
    assertEquals("42", delegate.attachedPid);

    AtomicReference<Object> submitted = new AtomicReference<>();
    client.submit("localhost", "Trace.java", new byte[] {1}, new String[] {"arg"}, submitted::set);
    assertEquals("localhost:Trace.java", client.printableText(submitted.get()));
    assertEquals(Command.STATUS, client.commandType(submitted.get()));
    assertEquals(Command.STATUS, client.commandConstant("STATUS"));

    AtomicReference<Object> probes = new AtomicReference<>();
    client.listProbes("localhost", probes::set);
    assertEquals("localhost", client.printableText(probes.get()));
    assertEquals(Command.LIST_PROBES, client.commandType(probes.get()));
    assertEquals("", client.printableText(new Command(Command.EXIT)));

    client.sendEvent();
    assertEquals("", delegate.event);
    client.sendEvent("tick");
    assertEquals("tick", delegate.event);
    client.sendDisconnect();
    client.sendExit(7);
    client.close();
    assertTrue(delegate.disconnected);
    assertEquals(7, delegate.exitCode);
    assertTrue(delegate.closed);
  }

  @Test
  void clientManagerTracksAndCleansUpClients() throws Exception {
    BTraceClient client = BTraceClient.create(2021);

    ClientManager.registerClient("43", 2021, client);
    assertEquals(client, ClientManager.getExistingClient("43", 2021));
    assertEquals(client, ClientManager.removeClient("43", 2021));
    assertFalse(ClientManager.getExistingClient("43", 2021) != null);

    ClientManager.registerClient("44", 2021, client);
    ClientManager.closeAll();
    assertTrue(Client.last.closed);
  }
}
