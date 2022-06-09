package team.qtk.jasync.mutiny;

import io.github.vipcxj.jasync.runtime.helpers.IntReference;
import io.github.vipcxj.jasync.spec.JAsync;
import io.smallrye.mutiny.Uni;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

public class MutinyPromiseTest {

    @Test
    public void test() {
        Promises
            .from(Uni.createFrom().item(6).onItem().delayIt().by(Duration.ofSeconds(1)))
            .then(a ->
                Promises
                    .from(Uni.createFrom().item(6).onItem().delayIt().by(Duration.ofSeconds(1)))
                    .then(b ->
                        JAsync.deferVoid(() -> {
                            IntReference iRef = new IntReference(0);
                            return JAsync.just().doWhileVoid(
                                    () -> iRef.getValue() < 10,
                                    () -> JAsync.deferVoid(() -> {
                                        if (iRef.getValue() % 2 == 0) {
                                            JAsync.doContinue(null);
                                        }
                                        System.out.println(iRef.getValue());
                                        return JAsync.just();
                                    }).doFinally(() -> {
                                        iRef.incrementAndGetValue();
                                        return JAsync.just();
                                    })
                            );
                        }).then(() ->
                                JAsync.deferVoid(() -> {
                                    AtomicInteger iRef = new AtomicInteger(0);
                                    return JAsync.just().doWhileVoid(
                                            () -> iRef.get() < a,
                                            () -> JAsync.defer(() -> {
                                                AtomicInteger jRef = new AtomicInteger(0);
                                                return JAsync.just().doWhileVoid(
                                                        () -> jRef.get() < b,
                                                        () -> JAsync.defer(
                                                            () -> Promises
                                                                .from(Uni.createFrom().item(iRef.get() * jRef.get()).onItem().delayIt().by(Duration.ofSeconds(1)))
                                                                .<Void>then(t0 -> {
                                                            System.out.println(iRef.get() + " * " + jRef.get() + " = " + t0);
                                                            if (iRef.get() == 5) {
                                                                JAsync.doBreak(null);
                                                            } else if (iRef.get() == 3 && jRef.get() == 4) {
                                                                return JAsync.doReturn(null);
                                                            }
                                                            return null;
                                                        })).doFinally(() -> {
                                                            jRef.set(jRef.get() + 1);
                                                            return null;
                                                        })
                                                );
                                            }).doFinally(() -> {
                                                iRef.set(iRef.get() + 1);
                                                return null;
                                            })
                                    );
                                })
                        )
                )
        ).catchReturn().block();
    }
}
