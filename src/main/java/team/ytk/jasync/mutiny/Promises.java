package team.ytk.jasync.mutiny;

import io.github.vipcxj.jasync.spec.JPromise;
import io.github.vipcxj.jasync.spec.Utils;
import io.github.vipcxj.jasync.spec.functional.PromiseSupplier;
import io.github.vipcxj.jasync.spec.spi.PromiseProvider;
import io.smallrye.mutiny.Uni;

public class Promises implements PromiseProvider {

    public static <T> JPromise<T> from(Uni<T> uni) {
        return new UniPromise<>(uni);
    }

    public <T> JPromise<T> just(T value) {
        return new UniPromise<>(Uni.createFrom().item(value));
    }

    public <T> JPromise<T> defer(PromiseSupplier<T> block) {
        return from(
            Uni
                .createFrom()
                .deferred(
                    () -> {
                        try {
                            return Utils.safeGet(block).unwrap(Uni.class);
                        } catch (Throwable t) {
                            return Uni.createFrom().failure(t);
                        }
                    }
                )
        );
    }

    @Override
    public <T> JPromise<T> error(Throwable t) {
        return from(Uni.createFrom().failure(t));
    }
}
