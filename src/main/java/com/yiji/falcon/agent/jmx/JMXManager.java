/*
 * www.yiji.com Inc.
 * Copyright (c) 2016 All Rights Reserved
 */
package com.yiji.falcon.agent.jmx;

import com.yiji.falcon.agent.exception.JMXUnavailabilityException;
import com.yiji.falcon.agent.jmx.vo.JMXConnectionInfo;
import com.yiji.falcon.agent.jmx.vo.JMXMetricsValueInfo;
import com.yiji.falcon.agent.jmx.vo.JMXObjectNameInfo;
import com.yiji.falcon.agent.util.BlockingQueueUtil;
import com.yiji.falcon.agent.util.ExceptionUtil;
import com.yiji.falcon.agent.util.ExecuteThreadUtil;
import lombok.extern.slf4j.Slf4j;

import javax.management.MBeanAttributeInfo;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/*
 * 修订记录:
 * guqiu@yiji.com 2016-06-22 17:48 创建
 */

/**
 * @author guqiu@yiji.com
 */
@Slf4j
public class JMXManager {

    private static final int timeout = 15;

    /**
     * 获取指定应用的名称(如运行的main类名称)所有的jmx值
     * @param serverName
     * @return
     */
    public synchronized static List<JMXMetricsValueInfo> getJmxMetricValue(String serverName){
        final BlockingQueue<Object> blockingQueue4BeanSet = new ArrayBlockingQueue<>(1);
        final BlockingQueue<Object> blockingQueue4BeanValue = new ArrayBlockingQueue<>(1);
        JMXConnection jmxConnection = new JMXConnection(serverName);
        List<JMXConnectionInfo> mbeanConns = jmxConnection.getMBeanConnection();
        if(mbeanConns.size() == 1
                && !mbeanConns.get(0).isValid()
                && mbeanConns.get(0).getmBeanServerConnection() == null
                && mbeanConns.get(0).getConnectionQualifiedServerName() == null
                && mbeanConns.get(0).getConnectionServerName() == null
                && mbeanConns.get(0).getCacheKeyId() == null){
            log.error("SuitAgent启动时应用 {} jmx连接失败,请检查应用是否已启动",serverName);
            JMXMetricsValueInfo jmxMetricsValueInfo = new JMXMetricsValueInfo();
            jmxMetricsValueInfo.setJmxConnectionInfo(mbeanConns.get(0));
            //返回上层返回的JMX服务不可用的对象
            return Collections.singletonList(jmxMetricsValueInfo);
        }

        int validCount = 0;
        List<JMXMetricsValueInfo> jmxMetricsValueInfoList = new ArrayList<>();//返回对象
        for (JMXConnectionInfo connectionInfo : mbeanConns) {//遍历JMX连接
            JMXMetricsValueInfo jmxMetricsValueInfo = new JMXMetricsValueInfo();//监控值信息对象
            if(connectionInfo.isValid()){//若该JMX连接可用
                try {
                    //阻塞队列异步执行
                    ExecuteThreadUtil.execute(() -> {
                        try {
                            Set<ObjectInstance> beanSet = connectionInfo.getmBeanServerConnection().queryMBeans(null, null);
                            if("org.apache.catalina.startup.Bootstrap".equals(serverName)){
                                //若tomcat服务器运行了springMVC的应用，必须要过滤有以下字符串的mBean，否则可能会导致tomcat中的应用启动失败
                                beanSet = beanSet.stream()
                                        .filter(mbean ->
                                                !(mbean.getObjectName().toString().contains("j2eeType=Servlet")))
                                        .collect(Collectors.toSet());
                            }

                            if (!blockingQueue4BeanSet.offer(beanSet)){
                                log.error("JMX {} 的objectNameList对象offer失败",connectionInfo.toString());
                            }
                        } catch (Throwable t) {
                            blockingQueue4BeanSet.offer(t);
                        }
                    });

                    //超时15秒
                    Object resultBeanSet = BlockingQueueUtil.getResult(blockingQueue4BeanSet,timeout, TimeUnit.SECONDS);
                    blockingQueue4BeanSet.clear();

                    if(resultBeanSet instanceof Set){
                        List<JMXObjectNameInfo> objectNameList = new ArrayList<>();//该jmx连接下的所有ObjectName值信息
                        Set<ObjectInstance> beanSet = (Set<ObjectInstance>) resultBeanSet;

                        for (ObjectInstance mbean : beanSet) {

                            //阻塞队列异步执行
                            ExecuteThreadUtil.execute(() -> {
                                try {
                                    Map<String,Object> map = new HashMap<>();
                                    JMXObjectNameInfo jmxObjectNameInfo = new JMXObjectNameInfo();
                                    ObjectName objectName = mbean.getObjectName();
                                    jmxObjectNameInfo.setObjectName(objectName);
                                    jmxObjectNameInfo.setJmxConnectionInfo(connectionInfo);
                                    try {
                                        for (MBeanAttributeInfo mBeanAttributeInfo : connectionInfo.getmBeanServerConnection().getMBeanInfo(objectName).getAttributes()) {
                                            map.put(mBeanAttributeInfo.getName(),
                                                    connectionInfo.getmBeanServerConnection().getAttribute(mbean.getObjectName(),mBeanAttributeInfo.getName())
                                            );
                                        }
                                    } catch (Exception e) {
                                        List<Throwable> throwables = ExceptionUtil.getExceptionCauses(e);
                                        for (Throwable throwable : throwables) {
                                            if (throwable != null &&
                                                    throwable.getClass() == java.net.ConnectException.class){
                                                throw new JMXUnavailabilityException(e);
                                            }
                                        }
                                    }

                                    jmxObjectNameInfo.setMetricsValue(map);

                                    if (!blockingQueue4BeanValue.offer(jmxObjectNameInfo)){
                                        log.error("mbean {} 的值集合offer失败",mbean.toString());
                                    }
                                } catch (Throwable t) {
                                    blockingQueue4BeanValue.offer(t);
                                }
                            });

                            //超时15秒
                            Object resultOni = BlockingQueueUtil.getResult(blockingQueue4BeanValue,timeout, TimeUnit.SECONDS);
                            blockingQueue4BeanValue.clear();

                            if(resultOni instanceof JMXObjectNameInfo){
                                JMXObjectNameInfo jmxObjectNameInfo = (JMXObjectNameInfo) resultOni;
                                objectNameList.add(jmxObjectNameInfo);
                            }else if(resultOni == null){
                                throw new JMXUnavailabilityException(String.format("mbean %s 的值集合获取失败：超时%d秒",mbean.toString(),timeout));
                            }else if(resultOni instanceof JMXUnavailabilityException){
                                throw (JMXUnavailabilityException) resultOni;
                            }else if (resultOni instanceof Throwable){
                                throw new JMXUnavailabilityException(String.format("mbean %s 的值集合获取异常",mbean.toString()), (Exception) resultOni);
                            }else {
                                throw new JMXUnavailabilityException("未匹配到的数据：" + resultOni);
                            }
                        }

                        //设置监控值对象
                        jmxMetricsValueInfo.setJmxObjectNameInfoList(objectNameList);
                        validCount++;
                    }else if (resultBeanSet == null){
                        throw new JMXUnavailabilityException(String.format("JMX %s 的objectNameList对象获取失败：超时%d秒",connectionInfo.toString(),timeout));
                    }else if (resultBeanSet instanceof Throwable){
                        throw new JMXUnavailabilityException(String.format("JMX %s 的objectNameList对象获取异常：%s",connectionInfo.toString(),resultBeanSet.toString()));
                    }else {
                        throw new JMXUnavailabilityException("未匹配到的数据：" + resultBeanSet);
                    }

                } catch (Exception e) {
                    if (e instanceof JMXUnavailabilityException){
                        // JMX连接异常，报告不可用,将会在下一次获取连接时进行维护
                        //JMX连接异常，报告不可用,将会在下一次获取连接时进行维护
                        log.error("JMXUnavailabilityException(Effect availability To false)",e);
                        connectionInfo.setValid(false);
                    }
                }finally {
                    //设置返回对象-添加监控值对象
                    jmxMetricsValueInfo.setJmxConnectionInfo(connectionInfo);
                    jmxMetricsValueInfoList.add(jmxMetricsValueInfo);
                }
            }else{
                //设置返回对象-添加监控值对象,连接不可用也需要返回,以便于构建连接不可用的报告对象
                jmxMetricsValueInfo.setJmxConnectionInfo(connectionInfo);
                jmxMetricsValueInfoList.add(jmxMetricsValueInfo);
            }
        }

        //若JMX可用的连接数小于该服务应有的JMX连接数,则进行尝试重新构建连接
        //将会在下一次获取监控值时生效
        if(validCount < JMXConnection.getServerConnectCount(serverName)){
            // TODO 这里可以设置重试次数,超过次数就进行此连接的清除
            log.error("发现服务{}有缺失的JMX连接,尝试重新构建该服务的jmx连接",serverName);
            jmxConnection.resetMBeanConnection();
        }

        return jmxMetricsValueInfoList;
    }

}
