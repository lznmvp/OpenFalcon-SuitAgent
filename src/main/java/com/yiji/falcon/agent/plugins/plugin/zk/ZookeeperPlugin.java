/*
 * www.yiji.com Inc.
 * Copyright (c) 2016 All Rights Reserved
 */
package com.yiji.falcon.agent.plugins.plugin.zk;
/*
 * 修订记录:
 * guqiu@yiji.com 2016-06-27 15:41 创建
 */

import com.yiji.falcon.agent.falcon.CounterType;
import com.yiji.falcon.agent.falcon.FalconReportObject;
import com.yiji.falcon.agent.falcon.MetricsType;
import com.yiji.falcon.agent.jmx.vo.JMXMetricsValueInfo;
import com.yiji.falcon.agent.jmx.vo.JMXObjectNameInfo;
import com.yiji.falcon.agent.plugins.JMXPlugin;
import com.yiji.falcon.agent.plugins.metrics.MetricsCommon;
import com.yiji.falcon.agent.plugins.util.PluginActivateType;
import com.yiji.falcon.agent.util.CommandUtilForUnix;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * @author guqiu@yiji.com
 */
@Slf4j
public class ZookeeperPlugin implements JMXPlugin {

    private String basePropertiesKey;
    private String jmxServerName;
    private int step;
    private PluginActivateType pluginActivateType;
    private static String lastAgentSignName = "";

    /**
     * 自定义的监控属性的监控值基础配置名
     *
     * @return 若无配置文件, 可返回null
     */
    @Override
    public String basePropertiesKey() {
        return basePropertiesKey;
    }

    /**
     * 该插件所要监控的服务在JMX连接中的displayName识别名
     * 若有该插件监控的相同类型服务,但是displayName不一样,可用逗号(,)进行分隔,进行统一监控
     *
     * @return
     */
    @Override
    public String jmxServerName() {
        return jmxServerName;
    }

    /**
     * 该插件监控的服务标记名称,目的是为能够在操作系统中准确定位该插件监控的是哪个具体服务
     * 如该服务运行的端口号等
     * 若不需要指定则可返回null
     *
     * @param jmxMetricsValueInfo 该服务连接的jmx对象
     * @param pid                   该服务当前运行的进程id
     * @return
     */
    @Override
    public String agentSignName(JMXMetricsValueInfo jmxMetricsValueInfo, int pid) {
        try {
            lastAgentSignName = String.valueOf(ZKConfig.getClientPort(pid));
            return lastAgentSignName;
        } catch (IOException e) {
            log.error("获取zookeeper clientPort 信息失败 ，返回最后的AgentSignName：{}",lastAgentSignName);
            return lastAgentSignName;
        }
    }

    /**
     * 插件监控的服务正常运行时的內建监控报告
     * 若有些特殊的监控值无法用配置文件进行配置监控,可利用此方法进行硬编码形式进行获取
     * 注:此方法只有在监控对象可用时,才会调用,并加入到监控值报告中,一并上传
     *
     * @param metricsValueInfo 当前的JMXMetricsValueInfo信息
     * @return
     */
    @Override
    public Collection<FalconReportObject> inbuiltReportObjectsForValid(JMXMetricsValueInfo metricsValueInfo) {
        boolean isLeader = false;
        String name = metricsValueInfo.getJmxConnectionInfo().getName();
        List<FalconReportObject> result = new ArrayList<>();
        for (JMXObjectNameInfo objectNameInfo : metricsValueInfo.getJmxObjectNameInfoList()) {
            if(objectNameInfo.toString().contains("Leader")){
                //若ObjectName中包含有 Leader 则该zk为Leader角色
                isLeader = true;
            }
        }
        result.add(generatorIsLeaderReport(isLeader,name));
        return result;
    }

    private FalconReportObject generatorIsLeaderReport(boolean isLeader,String name){
        FalconReportObject falconReportObject = new FalconReportObject();
        MetricsCommon.setReportCommonValue(falconReportObject,step);
        falconReportObject.setCounterType(CounterType.GAUGE);
        falconReportObject.setMetric(MetricsCommon.getMetricsName("isZookeeperLeader"));
        falconReportObject.setValue(isLeader ? "1" : "0");
        falconReportObject.setTimestamp(System.currentTimeMillis() / 1000);
        falconReportObject.appendTags(MetricsCommon.getTags(name,this,serverName(), MetricsType.JMX_OBJECT_IN_BUILD));
        return falconReportObject;
    }

    /**
     * 插件初始化操作
     * 该方法将会在插件运行前进行调用
     * @param properties
     * 包含的配置:
     * 1、插件目录绝对路径的(key 为 pluginDir),可利用此属性进行插件自定制资源文件读取
     * 2、插件指定的配置文件的全部配置信息(参见 {@link com.yiji.falcon.agent.plugins.Plugin#configFileName()} 接口项)
     * 3、授权配置项(参见 {@link com.yiji.falcon.agent.plugins.Plugin#authorizationKeyPrefix()} 接口项
     */
    @Override
    public void init(Map<String, String> properties) {
        basePropertiesKey = properties.get("basePropertiesKey");
        jmxServerName = properties.get("jmxServerName");
        step = Integer.parseInt(properties.get("step"));
        pluginActivateType = PluginActivateType.valueOf(properties.get("pluginActivateType"));
    }

    /**
     * 该插件监控的服务名
     * 该服务名会上报到Falcon监控值的tag(service)中,可用于区分监控值服务
     *
     * @return
     */
    @Override
    public String serverName() {
        return "zookeeper";
    }

    /**
     * 监控值的获取和上报周期(秒)
     *
     * @return
     */
    @Override
    public int step() {
        return step;
    }

    /**
     * 插件运行方式
     *
     * @return
     */
    @Override
    public PluginActivateType activateType() {
        return pluginActivateType;
    }

    /**
     * Agent关闭时的调用钩子
     * 如，可用于插件的资源释放等操作
     */
    @Override
    public void agentShutdownHook() {

    }

    @Override
    public String serverPath(int pid, String serverName) {
        String dirPath = "";
        try {
            dirPath = CommandUtilForUnix.getCmdDirByPid(pid);
        } catch (IOException e) {
            log.error("zk serverDirPath获取异常",e);
        }
        return dirPath;
    }
}
