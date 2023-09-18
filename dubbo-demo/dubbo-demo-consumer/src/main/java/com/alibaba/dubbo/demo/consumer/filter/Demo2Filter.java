package com.alibaba.dubbo.demo.consumer.filter;

import com.alibaba.dubbo.rpc.*;

/**
 * demo2
 *
 * @author 890065 [杨铃 | 890065@leapmotor.com]
 * 2023/9/14 16:52
 */
public class Demo2Filter implements Filter {
    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        Result result = invoker.invoke(invocation);
        result.getAttachments().put("demoKey", "demo2Value");
        return result;
    }
}
