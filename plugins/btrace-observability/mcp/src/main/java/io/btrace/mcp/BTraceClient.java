/*
 * Copyright (c) 2026, Jaroslav Bachorik <j.bachorik@btrace.io>.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.btrace.mcp;

import java.io.File;
import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;

/**
 * Reflective adapter for the client section of BTrace's masked distribution JAR.
 *
 * <p>The MCP server intentionally depends only on the public bootstrap loader. Client and compiler
 * classes remain hidden in the {@code client} classdata section and are loaded through the same
 * {@code MaskedClassLoader} boundary used by {@code java -jar btrace.jar}.
 */
public final class BTraceClient {
  @FunctionalInterface
  public interface CommandListener {
    void onCommand(Object command) throws Exception;
  }

  private final Object delegate;
  private final Class<?> clientClass;
  private final Class<?> commandListenerClass;
  private final Class<?> commandClass;
  private final Class<?> printableCommandClass;

  private BTraceClient(
      Object delegate,
      Class<?> clientClass,
      Class<?> commandListenerClass,
      Class<?> commandClass,
      Class<?> printableCommandClass) {
    this.delegate = delegate;
    this.clientClass = clientClass;
    this.commandListenerClass = commandListenerClass;
    this.commandClass = commandClass;
    this.printableCommandClass = printableCommandClass;
  }

  public static BTraceClient create(int port) throws Exception {
    Class<?> loaderClass = Class.forName("io.btrace.boot.MaskedClassLoader");
    File jar = jarFile(loaderClass);
    Constructor<?> constructor = loaderClass.getConstructor(File.class, String.class, ClassLoader.class);
    ClassLoader loader =
        (ClassLoader) constructor.newInstance(jar, "client", BTraceClient.class.getClassLoader());
    Class<?> clientClass = loader.loadClass("io.btrace.client.Client");
    Object client = clientClass.getConstructor(int.class).newInstance(port);
    return new BTraceClient(
        client,
        clientClass,
        loader.loadClass("io.btrace.core.comm.CommandListener"),
        loader.loadClass("io.btrace.core.comm.Command"),
        loader.loadClass("io.btrace.core.comm.PrintableCommand"));
  }

  public byte[] compileSource(String fileName, String source, String classPath, PrintWriter errors)
      throws Exception {
    return (byte[])
        clientClass
            .getMethod("compileSource", String.class, String.class, String.class, PrintWriter.class)
            .invoke(delegate, fileName, source, classPath, errors);
  }

  public String onelinerSource(String expression, String className) throws Exception {
    ClassLoader loader = clientClass.getClassLoader();
    Class<?> parser = loader.loadClass("io.btrace.compiler.oneliner.OnelinerParser");
    Object ast = parser.getMethod("parse", String.class).invoke(null, expression);
    Class<?> validator = loader.loadClass("io.btrace.compiler.oneliner.OnelinerValidator");
    for (Method method : validator.getMethods()) {
      if (method.getName().equals("validate") && method.getParameterCount() == 2) {
        method.invoke(null, ast, expression);
        break;
      }
    }
    Class<?> generator = loader.loadClass("io.btrace.compiler.oneliner.OnelinerCodeGenerator");
    for (Method method : generator.getMethods()) {
      if (method.getName().equals("generate") && method.getParameterCount() == 2) {
        return (String) method.invoke(null, ast, className);
      }
    }
    throw new IllegalStateException("BTrace oneliner generator is unavailable");
  }

  public void attach(String pid, String systemClassPath, String bootstrapClassPath) throws Exception {
    clientClass
        .getMethod("attach", String.class, String.class, String.class)
        .invoke(delegate, pid, systemClassPath, bootstrapClassPath);
  }

  public void submit(String host, String fileName, byte[] code, String[] args, CommandListener listener)
      throws Exception {
    clientClass
        .getMethod(
            "submit", String.class, String.class, byte[].class, String[].class, commandListenerClass)
        .invoke(delegate, host, fileName, code, args, listenerProxy(listener));
  }

  public void listProbes(String host, CommandListener listener) throws Exception {
    clientClass
        .getMethod("connectAndListProbes", String.class, commandListenerClass)
        .invoke(delegate, host, listenerProxy(listener));
  }

  public void sendExit(int code) throws Exception {
    clientClass.getMethod("sendExit", int.class).invoke(delegate, code);
  }

  public void sendDisconnect() throws Exception {
    clientClass.getMethod("sendDisconnect").invoke(delegate);
  }

  public void sendEvent() throws Exception {
    clientClass.getMethod("sendEvent").invoke(delegate);
  }

  public void sendEvent(String name) throws Exception {
    clientClass.getMethod("sendEvent", String.class).invoke(delegate, name);
  }

  public void close() throws Exception {
    clientClass.getMethod("close").invoke(delegate);
  }

  public int commandType(Object command) throws Exception {
    return ((Number) commandClass.getMethod("getType").invoke(command)).intValue();
  }

  public int commandConstant(String name) throws Exception {
    return commandClass.getField(name).getInt(null);
  }

  public String printableText(Object command) throws Exception {
    if (!printableCommandClass.isInstance(command)) {
      return "";
    }
    java.io.StringWriter output = new java.io.StringWriter();
    printableCommandClass.getMethod("print", PrintWriter.class).invoke(command, new PrintWriter(output));
    return output.toString();
  }

  private Object listenerProxy(CommandListener listener) {
    InvocationHandler handler =
        (proxy, method, args) -> {
          if (method.getName().equals("onCommand") && args != null && args.length == 1) {
            listener.onCommand(args[0]);
          }
          return null;
        };
    return Proxy.newProxyInstance(commandListenerClass.getClassLoader(), new Class<?>[] {commandListenerClass}, handler);
  }

  private static File jarFile(Class<?> loaderClass) throws Exception {
    ProtectionDomain protectionDomain = loaderClass.getProtectionDomain();
    CodeSource source = protectionDomain == null ? null : protectionDomain.getCodeSource();
    URL location = source == null ? null : source.getLocation();
    if (location == null || !"file".equals(location.getProtocol())) {
      throw new IllegalStateException("BTrace masked JAR is not available on the MCP server classpath");
    }
    return new File(location.toURI());
  }
}
