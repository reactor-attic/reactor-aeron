package reactor.ipc.aeron.demo;

import io.aeron.driver.AeronResources;
import reactor.core.publisher.Mono;
import reactor.ipc.aeron.ByteBufferFlux;
import reactor.ipc.aeron.client.AeronClient;

public class ClientDemo {

  /**
   * Main runner.
   *
   * @param args program arguments.
   */
  public static void main(String[] args) {

    try (AeronResources aeronResources = new AeronResources("test")) {
      AeronClient client =
          AeronClient.create(
              "client",
              aeronResources,
              options -> {
                options.serverChannel("aeron:udp?endpoint=localhost:13000");
                options.clientChannel("aeron:udp?endpoint=localhost:12001");
              });
      client
          .newHandler(
              (inbound, outbound) -> {
                System.out.println("Handler invoked");
                outbound
                    .send(ByteBufferFlux.from("Hello", "world!").log("send"))
                    .then()
                    .subscribe(
                        avoid -> {
                          // no-op
                        },
                        Throwable::printStackTrace);
                return Mono.never();
              })
          .block();
    }
    System.out.println("main completed");
  }
}
