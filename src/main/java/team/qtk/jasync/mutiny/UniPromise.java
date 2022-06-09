package team.qtk.jasync.mutiny;

import io.github.vipcxj.jasync.runtime.helpers.ArrayIterator;
import io.github.vipcxj.jasync.spec.*;
import io.github.vipcxj.jasync.spec.catcher.Catcher;
import io.github.vipcxj.jasync.spec.functional.*;
import io.github.vipcxj.jasync.spec.switchexpr.ICase;
import io.smallrye.mutiny.Uni;

import java.time.Duration;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public class UniPromise<T> implements JPromise<T> {

    private boolean resolved;
    private T value;
    private Throwable error;
    private final Uni<T> uni;

    public UniPromise(Uni<T> uni) {
        this.uni = uni;
    }

    private void resolve(T value) {
        this.resolved = true;
        this.value = value;
        this.error = null;
    }

    private void reject(Throwable error) {
        this.resolved = true;
        this.value = null;
        this.error = error;
    }

    @Override
    public <O> JPromise<O> then(PromiseFunction<T, O> resolver) {
        AtomicReference<Boolean> empty = new AtomicReference<>(true);
        return new UniPromise<>(
            uni
                .<O>flatMap(
                    v -> {
                        resolve(v);
                        empty.set(false);
                        try {
                            return Utils.safeApply(resolver, v).unwrap(Uni.class);
                        } catch (Throwable t) {
                            return Uni.createFrom().failure(t);
                        }
                    }
                )
                .onItem()
                .ifNull()
                .switchTo(
                    Uni
                        .createFrom()
                        .deferred(
                            () -> {
                                if (empty.get()) {
                                    resolve(null);
                                    try {
                                        return Utils.safeApply(resolver, null).unwrap(Uni.class);
                                    } catch (Throwable t) {
                                        return Uni.createFrom().failure(t);
                                    }
                                } else {
                                    return Uni.createFrom().nullItem();
                                }
                            }
                        )
                )
        );
    }

    @Override
    public JPromise<T> doCatch(
        List<Class<? extends Throwable>> exceptionsType,
        PromiseFunction<Throwable, T> reject
    ) {
        return doCatch(exceptionsType, reject, true);
    }

    @Override
    @SuppressWarnings("unchecked")
    public JPromise<T> doCatch(List<Catcher<?, T>> catchers) {
        return this.doCatch(
            Collections.singletonList(Throwable.class),
            t -> {
                List<? extends Class<? extends Throwable>> exceptionsType = catchers
                    .stream()
                    .map(Catcher::getExceptionType)
                    .collect(Collectors.toList());
                if (JAsync.mustRethrowException(t, exceptionsType)) {
                    return JAsync.error(t);
                }
                for (Catcher<?, T> catcher : catchers) {
                    if (catcher.match(t)) {
                        //noinspection unchecked
                        PromiseFunction<Throwable, T> reject = (PromiseFunction<Throwable, T>) catcher.getReject();
                        JPromise<T> res = reject != null ? reject.apply(t) : null;
                        return res != null ? res : JAsync.just();
                    }
                }
                return null;
            },
            false
        );
    }

    private JPromise<T> doCatch(
        List<Class<? extends Throwable>> exceptionsType,
        PromiseFunction<Throwable, T> reject,
        boolean processInnerExceptions
    ) {
        return new UniPromise<>(
            uni
                .onFailure(
                    t -> exceptionsType.stream().anyMatch(e -> e.isAssignableFrom(t.getClass()))
                )
                .recoverWithUni(
                    t -> {
                        if (processInnerExceptions && JAsync.mustRethrowException(t, exceptionsType)) {
                            return Uni.createFrom().failure(t);
                        }
                        reject(t);
                        try {
                            return Utils.safeApply(reject, t).unwrap(Uni.class);
                        } catch (Throwable e) {
                            return Uni.createFrom().failure(e);
                        }
                    }
                )
        );
    }

    @Override
    public JPromise<T> doFinally(VoidPromiseSupplier block) {
        AtomicReference<Boolean> isCatch = new AtomicReference<>(false);
        return doCatch(
            Collections.singletonList(Throwable.class),
            t -> {
                isCatch.set(true);
                return Utils.safeGetVoid(block).then(() -> JAsync.error(t));
            },
            false
        )
            .then(
                v -> {
                    if (!isCatch.get()) {
                        return Utils.safeGetVoid(block).then(() -> just(v));
                    } else {
                        return just(v);
                    }
                }
            );
    }

    private Uni<? extends AtomicReference<T>> doWhileBody(
        PromiseFunction<T, T> body,
        String label,
        AtomicReference<T> ref
    )
        throws Throwable {
        try {
            return Utils
                .safeApply(body, ref.get())
                .then(
                    a -> {
                        ref.set(a);
                        return null;
                    }
                )
                .doCatch(
                    ContinueException.class,
                    e -> {
                        if (e.matchLabel(label)) {
                            return null;
                        }
                        return JAsync.error(e);
                    }
                )
                .unwrap(Uni.class);
        } catch (ContinueException e) {
            if (e.matchLabel(label)) {
                return JAsync.just(null).unwrap(Uni.class);
            }
            return JAsync.error(e).unwrap(Uni.class);
        }
    }

    private Uni<? extends Integer> doWhileBody(VoidPromiseSupplier body, String label) throws Throwable {
        try {
            return Utils
                .safeGetVoid(body)
                .then(a -> null)
                .doCatch(
                    ContinueException.class,
                    e -> {
                        if (e.matchLabel(label)) {
                            return null;
                        }
                        return JAsync.error(e);
                    }
                )
                .unwrap(Uni.class);
        } catch (ContinueException e) {
            if (e.matchLabel(label)) {
                return JAsync.just(null).unwrap(Uni.class);
            }
            return JAsync.error(e).unwrap(Uni.class);
        }
    }

    @Override
    public JPromise<T> doWhile(BooleanSupplier predicate, PromiseFunction<T, T> block, String label) {
        return this.then(
                v -> {
                    AtomicReference<T> ref = new AtomicReference<>(v);
                    return new UniPromise<>(
                        Uni
                            .createFrom()
                            .deferred(
                                () -> {
                                    try {
                                        if (predicate.getAsBoolean()) {
                                            return doWhileBody(block, label, ref);
                                        } else {
                                            return Uni.createFrom().item(ref);
                                        }
                                    } catch (Throwable t) {
                                        return Uni.createFrom().failure(t);
                                    }
                                }
                            )
                            .repeat()
                            .until(Objects::nonNull)
                            .toUni()
                            .map(AtomicReference::get)
                    );
                }
            )
            .doCatch(
                BreakException.class,
                e -> {
                    if (e.matchLabel(label)) {
                        return null;
                    }
                    return JAsync.error(e);
                }
            );
    }

    @Override
    public JPromise<Void> doWhileVoid(BooleanSupplier predicate, VoidPromiseSupplier block, String label) {
        return this.then(
                () ->
                    new UniPromise<Void>(
                        Uni
                            .createFrom()
                            .deferred(
                                () -> {
                                    try {
                                        if (Utils.safeTest(predicate)) {
                                            return doWhileBody(block, label);
                                        } else {
                                            return Uni.createFrom().item(1);
                                        }
                                    } catch (Throwable t) {
                                        return Uni.createFrom().failure(t);
                                    }
                                }
                            )
                            .repeat()
                            .until(Objects::nonNull)
                            .toUni()
                            .flatMap(v -> Uni.createFrom().nullItem())
                    )
            )
            .doCatch(
                BreakException.class,
                e -> {
                    if (e.matchLabel(label)) {
                        return null;
                    }
                    return JAsync.error(e);
                }
            );
    }

    @Override
    public JPromise<T> doWhile(
        PromiseSupplier<Boolean> predicate,
        PromiseFunction<T, T> block,
        String label
    ) {
        return this.then(
                v -> {
                    AtomicReference<T> ref = new AtomicReference<>(v);
                    return new UniPromise<>(
                        Uni
                            .createFrom()
                            .deferred(
                                () -> {
                                    try {
                                        return Utils
                                            .safeTest(predicate)
                                            .<Uni<Boolean>>unwrap(Uni.class)
                                            .flatMap(
                                                test -> {
                                                    try {
                                                        if (test) {
                                                            return doWhileBody(block, label, ref);
                                                        } else {
                                                            return Uni.createFrom().item(ref);
                                                        }
                                                    } catch (Throwable t) {
                                                        return Uni.createFrom().failure(t);
                                                    }
                                                }
                                            );
                                    } catch (Throwable t) {
                                        return Uni.createFrom().failure(t);
                                    }
                                }
                            )
                            .repeat()
                            .until(Objects::nonNull)
                            .toUni()
                            .map(AtomicReference::get)
                    );
                }
            )
            .doCatch(
                BreakException.class,
                e -> {
                    if (e.matchLabel(label)) {
                        return null;
                    }
                    return JAsync.error(e);
                }
            );
    }

    @Override
    public JPromise<Void> doWhileVoid(
        PromiseSupplier<Boolean> predicate,
        VoidPromiseSupplier block,
        String label
    ) {
        return this.then(
                () ->
                    new UniPromise<Void>(
                        Uni
                            .createFrom()
                            .deferred(
                                () -> {
                                    try {
                                        return Utils
                                            .safeTest(predicate)
                                            .<Uni<Boolean>>unwrap(Uni.class)
                                            .flatMap(
                                                test -> {
                                                    try {
                                                        if (test) {
                                                            return doWhileBody(block, label);
                                                        } else {
                                                            return Uni.createFrom().item(1);
                                                        }
                                                    } catch (Throwable t) {
                                                        return Uni.createFrom().failure(t);
                                                    }
                                                }
                                            );
                                    } catch (Throwable t) {
                                        return Uni.createFrom().failure(t);
                                    }
                                }
                            )
                            .repeat()
                            .until(Objects::nonNull)
                            .toUni()
                            .flatMap(v -> Uni.createFrom().nullItem())
                    )
            )
            .doCatch(
                BreakException.class,
                e -> {
                    if (e.matchLabel(label)) {
                        return null;
                    }
                    return JAsync.error(e);
                }
            );
    }

    private <E> JPromise<Void> doForEachIterator(
        Iterator<E> iterator,
        VoidPromiseFunction<E> block,
        String label
    ) {
        return this.doWhileVoid(
            iterator::hasNext,
            () -> {
                E next = iterator.next();
                return Utils.safeApply(block, next);
            },
            label
        );
    }

    @Override
    public <E> JPromise<Void> doForEachIterable(
        Iterable<E> iterable,
        VoidPromiseFunction<E> block,
        String label
    ) {
        return doForEachIterator(iterable.iterator(), block, label);
    }

    @Override
    public <E> JPromise<Void> doForEachObjectArray(E[] array, VoidPromiseFunction<E> block, String label) {
        return doForEachIterator(new ArrayIterator<>(array), block, label);
    }

    @Override
    public JPromise<Void> doForEachBooleanArray(
        boolean[] array,
        BooleanVoidPromiseFunction block,
        String label
    ) {
        AtomicInteger index = new AtomicInteger();
        int length = array.length;
        return this.doWhileVoid(
            () -> index.get() < length,
            () -> {
                boolean next = array[index.getAndIncrement()];
                return Utils.safeApply(block, next);
            },
            label
        );
    }

    @Override
    public JPromise<Void> doForEachByteArray(byte[] array, ByteVoidPromiseFunction block, String label) {
        AtomicInteger index = new AtomicInteger();
        int length = array.length;
        return this.doWhileVoid(
            () -> index.get() < length,
            () -> {
                byte next = array[index.getAndIncrement()];
                return Utils.safeApply(block, next);
            },
            label
        );
    }

    @Override
    public JPromise<Void> doForEachCharArray(char[] array, CharVoidPromiseFunction block, String label) {
        AtomicInteger index = new AtomicInteger();
        int length = array.length;
        return this.doWhileVoid(
            () -> index.get() < length,
            () -> {
                char next = array[index.getAndIncrement()];
                return Utils.safeApply(block, next);
            },
            label
        );
    }

    @Override
    public JPromise<Void> doForEachShortArray(short[] array, ShortVoidPromiseFunction block, String label) {
        AtomicInteger index = new AtomicInteger();
        int length = array.length;
        return this.doWhileVoid(
            () -> index.get() < length,
            () -> {
                short next = array[index.getAndIncrement()];
                return Utils.safeApply(block, next);
            },
            label
        );
    }

    @Override
    public JPromise<Void> doForEachIntArray(int[] array, IntVoidPromiseFunction block, String label) {
        AtomicInteger index = new AtomicInteger();
        int length = array.length;
        return this.doWhileVoid(
            () -> index.get() < length,
            () -> {
                int next = array[index.getAndIncrement()];
                return Utils.safeApply(block, next);
            },
            label
        );
    }

    @Override
    public JPromise<Void> doForEachLongArray(long[] array, LongVoidPromiseFunction block, String label) {
        AtomicInteger index = new AtomicInteger();
        int length = array.length;
        return this.doWhileVoid(
            () -> index.get() < length,
            () -> {
                long next = array[index.getAndIncrement()];
                return Utils.safeApply(block, next);
            },
            label
        );
    }

    @Override
    public JPromise<Void> doForEachFloatArray(float[] array, FloatVoidPromiseFunction block, String label) {
        AtomicInteger index = new AtomicInteger();
        int length = array.length;
        return this.doWhileVoid(
            () -> index.get() < length,
            () -> {
                float next = array[index.getAndIncrement()];
                return Utils.safeApply(block, next);
            },
            label
        );
    }

    @Override
    public JPromise<Void> doForEachDoubleArray(
        double[] array,
        DoubleVoidPromiseFunction block,
        String label
    ) {
        AtomicInteger index = new AtomicInteger();
        int length = array.length;
        return this.doWhileVoid(
            () -> index.get() < length,
            () -> {
                double next = array[index.getAndIncrement()];
                return Utils.safeApply(block, next);
            },
            label
        );
    }

    @Override
    public <C> JPromise<Void> doSwitch(C value, List<? extends ICase<C>> cases, String label) {
        boolean matched = false;
        JPromise<Void> result = null;
        if (cases != null) {
            for (int i = 0; i < 2; ++i) {
                for (ICase<C> aCase : cases) {
                    if (!matched && aCase.is(value, i != 0)) {
                        matched = true;
                    }
                    if (matched) {
                        result =
                            (result != null ? result : this).then(
                                () -> {
                                    try {
                                        VoidPromiseSupplier body = aCase.getBody();
                                        return Utils.safeGetVoid(body);
                                    } catch (Throwable t) {
                                        return JAsync.error(t);
                                    }
                                }
                            );
                    }
                }
                if (matched) {
                    break;
                }
            }
        }

        return result != null
            ? result.doCatch(
            BreakException.class,
            e -> {
                if (e.matchLabel(label)) {
                    return null;
                }
                return JAsync.error(e);
            }
        )
            : JAsync.just();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <O> JPromise<O> catchReturn() {
        AtomicBoolean errorFlag = new AtomicBoolean(false);
        AtomicReference<O> errorRef = new AtomicReference<>();
        return new UniPromise<>(
            uni
                // if error
                .onFailure(ReturnException.class)
                // then,获取下错误信息
                .recoverWithUni( //
                    e -> {
                        ReturnException returnException = (ReturnException) e;
                        errorFlag.set(true);
                        //noinspection unchecked
                        errorRef.set((O) returnException.getValue());
                        return Uni.createFrom().nullItem();
                    }
                )
                // 正常逻辑，返回正常结果或者错误信息
                .onItem() // 转到正常流程分支
                .transformToUni(
                    v ->
                        Uni
                            .createFrom()
                            .deferred(
                                () -> {
                                    if (errorFlag.get()) {
                                        return Uni.createFrom().item(errorRef.get());
                                    } else {
                                        return Uni.createFrom().nullItem();
                                    }
                                }
                            )
                )
        );
    }

    // await语法糖
    @Override
    public T await() {
        if (resolved) {
            if (error != null) {
                sneakyThrow(error);
            } else {
                return value;
            }
        }
        throw new UnsupportedOperationException();
    }

    // 非阻塞执行
    @Override
    public Handle async() {
        return new DisposableHandle(
            cancelCallback -> uni.onCancellation().invoke(cancelCallback).subscribe().with(v -> {
            })
        );
    }

    // 阻塞等待结果（无限制等待）
    @Override
    public T block() {
        return uni.await().indefinitely();
    }

    // 阻塞等待结果（设定超时）
    @Override
    public T block(Duration duration) {
        return uni.await().atMost(duration);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <I> I unwrap(Class<?> type) {
        if (!Uni.class.equals(type)) {
            throw new UnwrapUnsupportedException(type, Uni.class);
        }
        //noinspection unchecked
        return (I) uni;
    }

    public static <O> UniPromise<O> just(O value) {
        return new UniPromise<>(Uni.createFrom().item(value));
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrow(Throwable e) throws E {
        //noinspection unchecked
        throw (E) e;
    }
}
