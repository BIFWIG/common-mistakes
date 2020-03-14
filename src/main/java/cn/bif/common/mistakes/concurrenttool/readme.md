## 使用了并发工具类库，线程安全就高枕无忧了吗？

- 没有意识到线程重用导致用户信息错乱的Bug：threadlocal
- 使用了线程安全的并发工具，并不代表解决了所有线程安全问题：concurrenthashmapmisuse
- 没有充分了解并发工具的特性，从而无法发挥其威力：concurrenthashmapperformance
- 没有认清并发工具的使用场景，因而导致性能问题：copyonwritelistmisuse

### 没有意识到线程重用导致用户信息错乱的Bug

* ThrealLocal 会重复利用线程池的线程，将springBoot中的配置文件添加`server.tomcat.max-threads=1` 此时Tomcat只有一个线程在运行，这时候这段代码就会出现问题

  ```java
  @GetMapping("wrong")
  public Map<String,Object> wrong(@RequestParam("userId") Integer userId){
  	String before = Thread.currentThread().getName()+":"+currentUser.get();
  	currentUser.set(userId);
  	String after = Thread.currentThread().getName()+":"+currentUser.get();
  	Map<String, Object> map  = new HashMap<>();
  	
  	map.put("before",before);
  	map.put("after",after);
  	
  	return map;
  }
  ```

  在第二个请求的时候 会获取到第一次的**UserID** 

  ```json
  {
    "before": "http-nio-8080-exec-1:1",
    "after": "http-nio-8080-exec-1:2"
  }
  ```

  按理说，在设置用户信息之前第一次获取的值始终应该是 null，但我们要意识到，程序运行在 Tomcat 中，执行程序的线程是 Tomcat 的工作线程，而 Tomcat 的工作线程是基于线程池的。顾名思义，线程池会重用固定的几个线程，一旦**线程重用**，那么很可能首次从 ThreadLocal 获取的值是之前其他用户的请求遗留的值。这时，ThreadLocal 中的用户信息就是其他用户的信息。

* 不能认为没有**显示**的开启多线程或者使用线程池 就没有线程安全的风险。

* 线程创建的代价比较贵，所以服务器会节省这部分的开销，使用线程池进行处理请求，Tomcat默认的线程池大小是*200* 代码中如果只是临时使用ThreadLocal变量，需要在使用结束后手动置空，防止被后续的请求给复用。

  ```java
    @GetMapping("right")
      public Map<String,Object> right(@RequestParam("userId") Integer userId){
         
          Map<String, Object> map;
          try {
              String before = Thread.currentThread().getName()+":"+currentUser.get();
              currentUser.set(userId);
              String after = Thread.currentThread().getName()+":"+currentUser.get();
              map = new HashMap<>();
              map.put("before",before);
              map.put("after",after);
              return map;
          } finally {
              currentUser.remove();
          }
      }
  ```

### 使用了线程安全的并发工具，并不代表解决了所有线程安全问题

* **ConcurrentHashMap** 只保证原子性的读写是线程安全的， 不代表对多个操作之间的状态是一致的，如果有其他线程在同时操作它，此时仍需要手动进行加锁

  ``` java
      //线程个数
      private static int THREAD_COUNT = 10;
      // 总元素数量
      private static int ITEM_COUNT = 1000;
      //帮助方法，用来获得一个指定元素数量模拟数据的
      private ConcurrentHashMap<String,Long> getData (int count) {
          return LongStream
                  .rangeClosed(1, count)
                  .boxed()
                  .collect(Collectors
                          .toConcurrentMap(i -> UUID.randomUUID()
                                  .toString(),
                                  Function.identity(),
                                  (o1, o2) -> o1, ConcurrentHashMap::new));
      }
       
  @GetMapping("wrong")
      public void wrong(){
          ConcurrentHashMap<String,Long> concurrentHashMap = getData(ITEM_COUNT-100);
          log.info("init Size{}",concurrentHashMap.size());
          ForkJoinPool forkJoinPool = new ForkJoinPool(THREAD_COUNT);
          forkJoinPool.execute(() -> IntStream.rangeClosed(1,10).parallel().forEach(value -> {
              if (ITEM_COUNT - concurrentHashMap.size()>0) {
                  int a = ITEM_COUNT - concurrentHashMap.size() ;
                  log.info("a size {}",a);
                  concurrentHashMap.putAll(getData(a));
              }
          }));
          forkJoinPool.shutdown();
          forkJoinPool.awaitQuiescence(1, TimeUnit.HOURS);
          log.info("finish Size {}",concurrentHashMap.size());
      }	
  ```

  ``` verilog
  2020-03-14 16:42:27.272  INFO 33005 --- [nio-8080-exec-1] c.b.c.m.c.c.MapError                     : init Size900
  2020-03-14 16:42:27.283  INFO 33005 --- [nio-8080-exec-1] c.b.c.m.c.c.MapError                     : a size 100
  2020-03-14 16:42:27.283  INFO 33005 --- [onPool-worker-2] c.b.c.m.c.c.MapError                     : a size 100
  2020-03-14 16:42:27.283  INFO 33005 --- [onPool-worker-4] c.b.c.m.c.c.MapError                     : a size 100
  2020-03-14 16:42:27.283  INFO 33005 --- [onPool-worker-1] c.b.c.m.c.c.MapError                     : a size 100
  2020-03-14 16:42:27.283  INFO 33005 --- [onPool-worker-5] c.b.c.m.c.c.MapError                     : a size 100
  2020-03-14 16:42:27.283  INFO 33005 --- [onPool-worker-6] c.b.c.m.c.c.MapError                     : a size 100
  2020-03-14 16:42:27.283  INFO 33005 --- [onPool-worker-3] c.b.c.m.c.c.MapError                     : a size 100
  2020-03-14 16:42:27.283  INFO 33005 --- [onPool-worker-7] c.b.c.m.c.c.MapError                     : a size 100
  2020-03-14 16:42:27.292  INFO 33005 --- [nio-8080-exec-1] c.b.c.m.c.c.MapError                     : finish Size 1700
  ```

  此时得到的1700 并不符合我们所需要的1000的目标值，说明这段代码存在线程不安全的问题。

  ```java
   @GetMapping("right")
      public void right(){
          ConcurrentHashMap<String,Long> concurrentHashMap = getData(ITEM_COUNT-100);
          log.info("init Size{}",concurrentHashMap.size());
          ForkJoinPool forkJoinPool = new ForkJoinPool(THREAD_COUNT);
          forkJoinPool.execute(() -> IntStream.rangeClosed(1,10).parallel().forEach(value -> {
              //此处进行加锁，但是也丢失了并发的作用
              synchronized (concurrentHashMap){
                  if (ITEM_COUNT - concurrentHashMap.size()>0) {
                      int a = ITEM_COUNT - concurrentHashMap.size() ;
                      log.info("a size {}",a);
                      concurrentHashMap.putAll(getData(a));
                  }
              }
          }));
          forkJoinPool.shutdown();
          forkJoinPool.awaitQuiescence(1, TimeUnit.HOURS);
          log.info("finish Size {}",concurrentHashMap.size());
      }
  ```

  对*concurrentHashMap*进行加锁肯定是能够解决问题的，但是这么一来还有必要选择使用**ConcurrentHashMap**？

  直接使用**HashMap**加锁也有同样的效果,说明在此处的**ConcurrentHashMap**是没必要的,是错误理解了它的作用而得到的错误用法。

### 没有充分了解并发工具的特性，从而无法发挥其威力

* **ConCurrentHashMap** 的一个常用的场景是用于统计 Map中key出现的次数

* 假设使用10个并发线程往 Map中写入值，Key的范围是0-9，循环写入1000W次，Value为Key出现的次数

* 一般做法就是直接map进行循环，为了保证不重复初始化Key，这时候为Key的累加加上锁

  ```java
   private Map<String, Long> normaluse() throws InterruptedException {
          Map<String, Long> freqs = new HashMap<>(ITEM_COUNT);
          ForkJoinPool forkJoinPool = new ForkJoinPool(THREAD_COUNT);
          forkJoinPool.execute(() -> IntStream.rangeClosed(1, LOOP_COUNT).parallel().forEach(i -> {
                      //获得一个随机的Key
                      String key = "item" + ThreadLocalRandom.current().nextInt(ITEM_COUNT);
                      synchronized (freqs) {
                          if (freqs.containsKey(key)) {
                              //Key存在则+1
                              freqs.put(key, freqs.get(key) + 1);
                          } else {
                              //Key不存在则初始化为1
                              freqs.put(key, 1L);
                          }
                      }
                  }
          ));
          forkJoinPool.shutdown();
          forkJoinPool.awaitTermination(1, TimeUnit.HOURS);
          return freqs;
      }
  ```

* 第二种做法就是使用ConcurrentHashMap 内置的 conputeIfAbsent 方法进行增加

  ```java
   private Map<String, Long> gooduse() throws InterruptedException {
          ConcurrentHashMap<String, LongAdder> freqs = new ConcurrentHashMap<>(ITEM_COUNT);
          ForkJoinPool forkJoinPool = new ForkJoinPool(THREAD_COUNT);
          forkJoinPool.execute(() -> IntStream.rangeClosed(1, LOOP_COUNT).parallel().forEach(i -> {
                      String key = "item" + ThreadLocalRandom.current().nextInt(ITEM_COUNT);
                      //利用computeIfAbsent()方法来实例化LongAdder，然后利用LongAdder来进行线程安全计数
                      freqs.computeIfAbsent(key, k -> new LongAdder()).increment();
                  }
          ));
          forkJoinPool.shutdown();
          forkJoinPool.awaitTermination(1, TimeUnit.HOURS);
          //因为我们的Value是LongAdder而不是Long，所以需要做一次转换才能返回
          return freqs.entrySet().stream()
                  .collect(Collectors.toMap(
                          Map.Entry::getKey,
                          e -> e.getValue().longValue())
                  );
      }
  ```

* 这两种做法都能满足我们统计的需求，我们通过*StopWatch* 来对两个方式的速度进行统计

  ```java
   @GetMapping("/compare")
      public void compareTime() throws InterruptedException {
          
          StopWatch stopWatch = new StopWatch();
          stopWatch.start("goodUse");
          Map<String, Long> goodUse = goodUse();
          stopWatch.stop();
          Assert.isTrue(goodUse.size() == ITEM_COUNT,"goodUse count Err");
          Assert.isTrue(goodUse.values().stream()
                  .mapToLong(value -> value)
                  .reduce(0,Long::sum)==LOOP_COUNT,"goodUse count Err");
      
          stopWatch.start("normalUse");
          Map<String, Long> normalUse = normalUse();
          stopWatch.stop();
          Assert.isTrue(normalUse.size() == ITEM_COUNT,"normalUse count Err");
          Assert.isTrue(normalUse.values().stream()
                  .mapToLong(value -> value)
                  .reduce(0,Long::sum)==LOOP_COUNT,"normalUse count Err");
          
          log.info(stopWatch.prettyPrint());
          
      }	
  ```

  ```verilog
  2020-03-14 20:41:00.001  INFO 33005 --- [nio-8080-exec-3] c.b.c.m.c.c.KeyError                     : StopWatch '': running time = 17247637770 ns
  ---------------------------------------------
  ns         %     Task name
  ---------------------------------------------
  1860003595  011%  goodUse
  15387634175  089%  normalUse
  ```

  由日志可知，两者时间上的差距将近9倍。

### 没有认清并发工具的使用场景，因而导致性能问题

* **CopyOnWrite** （目前我暂时没使用过，这儿就直接引用专栏老师的例子）

  * 这个是在写入的时候直接复制一份数据进行写入，然后将旧的引用指向新的队列，这样可以不用加锁，在写的同时，读操作依然可以读取旧的数据，Concurrent容器则不能做到这一点。
  * 但是也有两个缺点：
    *  1.因为对旧的数据进行了一份拷贝，所以占用的内存比较大，等于是空间换时间。
    *  2.不适用于对数据一致性时效要求比较高的场景，因为他只保证了数据的最终一致性，Concurrent则是保证了数据的随时一致性，*适用于黑名单缓存等对时效性要求没有那么高的场景*。

* 我们发现一段简单的非数据库操作的业务逻辑，消耗了超出预期的时间，在修改数据时操作本地缓存比回写数据库慢许多。查看代码发现，开发同学使用了 CopyOnWriteArrayList 来缓存大量的数据，而数据变化又比较频繁

* 在 Java 中，**CopyOnWriteArrayList 虽然是一个线程安全的 ArrayList，但因为其实现方式是，每次修改数据时都会复制一份数据出来，所以有明显的适用场景，即读多写少或者说希望无锁读的场景**。

* 如果我们要使用 CopyOnWriteArrayList，那一定是因为场景需要而不是因为足够酷炫。如果读写比例均衡或者有大量写操作的话，使用 CopyOnWriteArrayList 的性能会非常糟糕。

* 我们写一段测试代码，来比较下使用 CopyOnWriteArrayList 和普通加锁方式 ArrayList 的读写性能吧。在这段代码中我们针对并发读和并发写分别写了一个测试方法，测试两者一定次数的写或读操作的耗时。

  ```java
   //测试并发写的性能
      @GetMapping("write")
      public Map testWrite() {
          List<Integer> copyOnWriteArrayList = new CopyOnWriteArrayList<>();
          List<Integer> synchronizedList = Collections.synchronizedList(new ArrayList<>());
          StopWatch stopWatch = new StopWatch();
          int loopCount = 100000;
          stopWatch.start("Write:copyOnWriteArrayList");
          //循环100000次并发往CopyOnWriteArrayList写入随机元素
          IntStream.rangeClosed(1, loopCount).parallel().forEach(i -> copyOnWriteArrayList.add(ThreadLocalRandom.current().nextInt(loopCount)));
          stopWatch.stop();
          stopWatch.start("Write:synchronizedList");
          //循环100000次并发往加锁的ArrayList写入随机元素
          IntStream.rangeClosed(1, loopCount).parallel().forEach(i -> synchronizedList.add(ThreadLocalRandom.current().nextInt(loopCount)));
          stopWatch.stop();
          log.info(stopWatch.prettyPrint());
          Map result = new HashMap();
          result.put("copyOnWriteArrayList", copyOnWriteArrayList.size());
          result.put("synchronizedList", synchronizedList.size());
          return result;
      }
      
      //帮助方法用来填充List
      private void addAll(List<Integer> list) {
          list.addAll(IntStream.rangeClosed(1, 1000000).boxed().collect(Collectors.toList()));
      }
      
      //测试并发读的性能
      @GetMapping("read")
      public Map testRead() {
          //创建两个测试对象
          List<Integer> copyOnWriteArrayList = new CopyOnWriteArrayList<>();
          List<Integer> synchronizedList = Collections.synchronizedList(new ArrayList<>());
          //填充数据
          addAll(copyOnWriteArrayList);
          addAll(synchronizedList);
          StopWatch stopWatch = new StopWatch();
          int loopCount = 1000000;
          int count = copyOnWriteArrayList.size();
          stopWatch.start("Read:copyOnWriteArrayList");
          //循环1000000次并发从CopyOnWriteArrayList随机查询元素
          IntStream.rangeClosed(1, loopCount).parallel().forEach(i -> copyOnWriteArrayList.get(ThreadLocalRandom.current().nextInt(count)));
          stopWatch.stop();
          stopWatch.start("Read:synchronizedList");
          //循环1000000次并发从加锁的ArrayList随机查询元素
          IntStream.range(0, loopCount).parallel().forEach(i -> synchronizedList.get(ThreadLocalRandom.current().nextInt(count)));
          stopWatch.stop();
          log.info(stopWatch.prettyPrint());
          Map result = new HashMap();
          result.put("copyOnWriteArrayList", copyOnWriteArrayList.size());
          result.put("synchronizedList", synchronizedList.size());
          return result;
      }
  ```

  

* 运行程序可以看到，大量写的场景（10 万次 add 操作），CopyOnWriteArray 几乎比同步的 ArrayList 慢一百倍：

  ```verilog
  2020-03-14 20:52:43.816  INFO 33005 --- [nio-8080-exec-4] c.b.c.m.c.c.CopyOnWriteError             : StopWatch '': running time = 4207613566 ns
  ---------------------------------------------
  ns         %     Task name
  ---------------------------------------------
  4117309161  098%  Write:copyOnWriteArrayList
  090304405  002%  Write:synchronizedList
  ```
  
* 而在大量读的场景下（100 万次 get 操作），CopyOnWriteArray 又比同步的 ArrayList 快十倍以上

  ``` verilog
    2020-03-14 20:53:43.589  INFO 33005 --- [nio-8080-exec-5] c.b.c.m.c.c.CopyOnWriteError             : StopWatch '': running time = 416496271 ns
  ---------------------------------------------
  ns         %     Task name
  ---------------------------------------------
  028776967  007%  Read:copyOnWriteArrayList
  387719304  093%  Read:synchronizedList
  ```

  以 add 方法为例，每次 add 时，都会用 Arrays.copyOf 创建一个新数组，频繁 add 时内存的申请释放消耗会很大：

  ```java
  
      /**
       * Appends the specified element to the end of this list.
       *
       * @param e element to be appended to this list
       * @return {@code true} (as specified by {@link Collection#add})
       */
      public boolean add(E e) {
          synchronized (lock) {
              Object[] elements = getArray();
              int len = elements.length;
              Object[] newElements = Arrays.copyOf(elements, len + 1);
              newElements[len] = e;
              setArray(newElements);
              return true;
          }
      }	
  ```

  get方法则相反，因为在读取的时候CopyOnWriteArray不需要加锁，所以总体开销是比加锁的ArrayList低的。

### 思考题

* 我们多次用到了 ThreadLocalRandom，你觉得是否可以把它的实例设置到静态变量中，在多线程情况下重用呢？

  不可以，结果是除了初始化 ThreadLocalRandom 的主线程获取的随机值是无模式的（调用者不可预测下个返回值，满足我们对伪随机的要求）之外，其他线程获得随机值都不是相互独立的（本质上来说，是因为他们用于生成随机数的种子 seed 的值可预测的，为 i * gamma，其中 i 是当前线程调用随机数生成方法次数，而 gamma 是 ThreadLocalRandom 类的一个 long 静态字段值）。例如，一个有趣的现象是，所有非初始化 ThreadLocalRandom 实例的线程如果调用相同次数的 nextInt() 方法，他们得到的随机数串是完全相同的。
  造成这样现象的原因在于，ThreadLocalRandom 类维护了一个类单例字段，线程通过调用 ThreadLocalRandom#current() 方法来获取 ThreadLocalRandom 单例，然后以线程维护的实例字段 threadLocalRandomSeed 为种子生成下一个随机数和下一个种子值。
  那么既然是单例模式，为什么多线程共用主线程初始化的实例就会出问题呢。问题就在于 current 方法，线程在调用 current() 方法的时候，会根据用每个线程的 thread 的一个实例字段 threadLocalRandomProbe 是否为 0 来判断是否当前线程实例是否为第一次调用随机数生成方法，从而决定是否要给当前线程初始化一个随机的 threadLocalRandomSeed 种子值。因此，如果其他线程绕过 current 方法直接调用随机数方法，那么它的种子值就是 0, 1 * gamma, 2 * gamma... 因此也就是可预测的了。

* ConcurrentHashMap 还提供了 putIfAbsent 方法，你能否通过查阅JDK 文档，说说 computeIfAbsent 和 putIfAbsent 方法的区别？

  computeIfAbsent和putIfAbsent区别是三点：

  1. 当Key存在的时候，如果Value获取比较昂贵的话，putIfAbsent就白白浪费时间在获取这个昂贵的Value上（这个点特别注意）
  2. Key不存在的时候，putIfAbsent返回null，小心空指针，而computeIfAbsent返回计算后的值
  3. 当Key不存在的时候，putIfAbsent允许put null进去，而computeIfAbsent不能，之后进行containsKey查询是有区别的（当然了，此条针对HashMap，ConcurrentHashMap不允许put null value进去）

  

