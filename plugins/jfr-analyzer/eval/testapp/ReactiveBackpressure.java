import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import java.time.Duration;

public class ReactiveBackpressure implements main.Scenario {
    @Override public String id() { return "reactive_backpressure"; }

    @Override public void run(long durationMs) throws Exception {
        var subscription = Flux.interval(Duration.ofMillis(1))
            .onBackpressureBuffer(256)
            .publishOn(Schedulers.boundedElastic())
            .subscribe(i -> {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

        Thread.sleep(durationMs);
        subscription.dispose();
    }
}
