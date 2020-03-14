/*
 * Copyright (c) 2005, 2019, EVECOM Technology Co.,Ltd. All rights reserved.
 * EVECOM PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */

package cn.bif.common.mistakes.concurrenttool.concurrenthashmapmisuse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

/**
 * @author Ted Wang
 * @created 2020/3/14 下午4:32
 */
@RestController
@RequestMapping("TA2")
@Slf4j
public class MapError {
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
}
