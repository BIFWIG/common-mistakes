/*
 * Copyright (c) 2005, 2019, EVECOM Technology Co.,Ltd. All rights reserved.
 * EVECOM PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */

package cn.bif.common.mistakes.concurrenttool.thradlocal;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * @author Ted Wang
 * @created 2020/3/14 下午4:03
 */
@RestController
@RequestMapping("TA1")
public class UserError {
    
    private ThreadLocal<Integer> currentUser = ThreadLocal.withInitial(() -> null);
    
    
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
    
}
