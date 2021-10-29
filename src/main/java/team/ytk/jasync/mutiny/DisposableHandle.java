package team.ytk.jasync.mutiny;

import io.github.vipcxj.jasync.spec.Handle;
import io.smallrye.mutiny.subscription.Cancellable;
import java.util.function.Function;

public class DisposableHandle implements Handle {

    private final Cancellable cancellable;

    private boolean isCanceled = false;

    private final Runnable onCancellation = () -> {
        this.isCanceled = true;
    };

    public DisposableHandle(Function<Runnable, Cancellable> handler) {
        this.cancellable = handler.apply(onCancellation);
    }

    @Override
    public void cancel() {
        cancellable.cancel();
    }

    @Override
    public boolean isCanceled() {
        return isCanceled;
    }
}
