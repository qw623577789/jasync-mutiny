package team.ytk.jasync.mutiny;

import io.github.vipcxj.jasync.spec.Handle;
import io.github.vipcxj.jasync.spec.JAsync;
import io.github.vipcxj.jasync.spec.JPromise;
import io.github.vipcxj.jasync.spec.annotations.Async;
import io.smallrye.mutiny.TimeoutException;
import io.smallrye.mutiny.Uni;
import java.time.Duration;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class BaseTest {

    // 异步输出hello world!
    private JPromise<String> helloAsync() {
        return Promises.from(Uni.createFrom().item("hello world in async!"));
    }

    // 延迟5秒返回
    private JPromise<Void> sleepAsync() {
        Uni<Void> sleep = Uni.createFrom().voidItem().onItem().delayIt().by(Duration.ofSeconds(5));
        return Promises.from(sleep);
    }

    @Async //有await操作的话，必须加上这个
    private JPromise<String> asyncTest() throws Exception {
        // 异步等待方式，输出hello world in async!
        System.out.println(this.helloAsync().await());

        // 等待5秒
        sleepAsync().await();

        // 同步方式，输出hello world in sync!
        System.out.println("hello world in sync!");

        // 等待5秒
        sleepAsync().await();

        // 异步等待方式，输出hello world in async!
        System.out.println(this.helloAsync().await());

        // 等待5秒
        sleepAsync().await();

        // 完成测试
        System.out.println("done");

        // 用Async注解方法必须返回JPromise
        return JAsync.just("done");
    }

    @Test
    @SneakyThrows
    public void asyncBlock() {
        long start = System.currentTimeMillis();
        String result = this.asyncTest().block();
        long end = System.currentTimeMillis();
        Assertions.assertTrue(result.equals("done") && end - start > 1000, "asyncBlock failed");
    }

    @Test
    @SneakyThrows
    public void asyncBlockTimeout() {
        try {
            this.asyncTest().block(Duration.ofSeconds(1));
        } catch (TimeoutException error) {
            Assertions.assertTrue(true);
        } catch (Exception error) {
            Assertions.fail();
        }
    }

    @Test
    @SneakyThrows
    public void asyncNoBlock() {
        long start = System.currentTimeMillis();
        this.asyncTest().async();
        long end = System.currentTimeMillis();

        Assertions.assertTrue(end - start < 1000);
    }

    @Test
    @Async
    @SneakyThrows
    public void asyncNoBlockCancel() {
        try {
            Handle task = this.asyncTest().async();
            Thread.sleep(5000);
            task.cancel();
            Assertions.assertTrue(task.isCanceled());
        } catch (Exception error) {
            Assertions.fail();
        }
    }
}
