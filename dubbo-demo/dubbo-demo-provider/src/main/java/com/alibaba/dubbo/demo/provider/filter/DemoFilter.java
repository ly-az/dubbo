package com.alibaba.dubbo.demo.provider.filter;


import com.alibaba.dubbo.demo.provider.DemoRepository;
import com.alibaba.dubbo.demo.provider.DemoServiceImpl;
import com.alibaba.dubbo.rpc.*;

/**
 * @author 890065 [杨铃 | 890065@leapmotor.com]
 * 2023/9/14 16:20
 */
public class DemoFilter implements Filter {


    private  DemoRepository demoRepository;

    public DemoFilter setDemoRepository(DemoRepository demoRepository) {
        this.demoRepository = demoRepository;
        return this;
    }

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        Result result = invoker.invoke(invocation);
        System.out.println(result.getAttachments());
        result.getAttachments().put("demoKey", "demoValue");
        return result;
    }



}
