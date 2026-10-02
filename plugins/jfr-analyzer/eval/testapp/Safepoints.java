import java.lang.reflect.*;

public class Safepoints implements main.Scenario {
    @Override public String id() { return "safepoints"; }

    @Override public void run(long durationMs) throws Exception {
        long deadline = System.currentTimeMillis() + durationMs;
        Class<?> cls = null;
        Method method = null;
        int iter = 0;
        while (!Thread.currentThread().isInterrupted()
               && System.currentTimeMillis() < deadline) {
            cls = Class.forName("java.util.HashMap");
            if (method == null) {
                method = cls.getDeclaredMethod("size");
            }
            Object instance = cls.getDeclaredConstructor().newInstance();
            method.invoke(instance);
            iter++;
            if (iter % 1000 == 0) Thread.yield();
        }
    }
}
