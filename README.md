Jasync-mutiny
===============

**Jasync-mutiny**是[jasync](https://github.com/vipcxj/jasync)的[smallrye-mutiny](https://smallrye.io/smallrye-mutiny/index.html)的实现库，`JPromise` 对象是 `Uni` 对象的封装。

## 用法
```java
// Uni<T> => JPromise<T>
JPromise<T> xxJPromise = team.qtk.jasync.mutiny.Promises.from(io.smallrye.mutiny.Uni<T>)

// JPromise<T> => Uni<T>
Uni xxUni = xxJPromise.unwrap(Uni.class);

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

// 异步执行asyncTest
Handle async = awaitTest().async();

// 临时终止异步方法
async.cancel();

// 阻塞方式等待asyncTest结果
String result = awaitTest().block();

// 阻塞方式等待asyncTest结果,含超时抛错
String result = awaitTest().block(Duration.ofSeconds(1));
```

## 提示
- 本组件不支持ecj编译器，所以非idea打开工程的话，记得先使用gradle/maven进行编译后，再运行或者调试