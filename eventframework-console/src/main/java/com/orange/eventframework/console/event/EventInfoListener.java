package com.orange.eventframework.console.event;

import com.alibaba.fastjson.JSON;
import com.orange.eventframework.Constants;
import com.orange.eventframework.config.Config;
import com.orange.eventframework.console.entity.EventRelation;
import com.orange.eventframework.console.service.EventRelationService;
import com.orange.eventframework.eventinfo.ConsumeEventInfo;
import com.orange.eventframework.eventinfo.ProduceEventInfo;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 事件信息监听器
 *
 * @author 小天
 * @date 2019/4/2 14:48
 */
@Component
public class EventInfoListener implements MessageListenerConcurrently, SmartLifecycle {

    private Logger logger = LoggerFactory.getLogger(getClass());

    private DefaultMQPushConsumer consumer;

    @Value("${eventframework.console.rocketmq.consumer.groupId}")
    public String consumerGroupId;

    @Resource
    private EventRelationService eventRelationService;

    @Resource(name = "eventFrameworkConfig")
    private Config eventFrameworkConfig;

    @Override
    public void start() {

        this.consumer = new DefaultMQPushConsumer(consumerGroupId);
        this.consumer.setNamesrvAddr(eventFrameworkConfig.getNameSrvAddr());
        this.consumer.setClientIP(eventFrameworkConfig.getClientIP());
        this.consumer.setMaxReconsumeTimes(eventFrameworkConfig.getMaxReconsumeTimes());
        this.consumer.setConsumeThreadMax(eventFrameworkConfig.getConsumeThreadMax());
        this.consumer.setConsumeThreadMin(eventFrameworkConfig.getConsumeThreadMin());
        this.consumer.setPullBatchSize(eventFrameworkConfig.getPullBatchSize());

        try {
            this.consumer.subscribe(eventFrameworkConfig.getTopic(), "*");
        } catch (MQClientException e) {
            throw new IllegalStateException(e);
        }

        logger.info("注册 rocketmq 监听器： topic={}, consumerGroupId={}, tag=*", eventFrameworkConfig.getTopic(), this.consumerGroupId);
        this.consumer.registerMessageListener(this);

        try {
            this.consumer.start();
        } catch (MQClientException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    public int getPhase() {
        return 0;
    }

    @Override
    public void stop() {
        if (this.consumer != null) {
            this.consumer.shutdown();
            this.consumer = null;
        }
    }

    @Override
    public boolean isRunning() {
        return this.consumer != null;
    }

    @Override
    public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> msgs, ConsumeConcurrentlyContext context) {
        for (MessageExt msg : msgs) {
            EventRelation eventRelation = null;
            try {
                String body = new String(msg.getBody(), StandardCharsets.UTF_8);

                logger.debug("监听到消息： msgId={},topic={},tag={},body={}", msg.getMsgId(), msg.getTopic(), msg.getTags(), body);

                if (Constants.EVENT_INFO_PRODUCE.equals(msg.getTags())) {

                    ProduceEventInfo eventInfo = JSON.parseObject(body, ProduceEventInfo.class);

                    // 保存事件信息
                    eventRelationService.save(eventInfo);

                    // 创建事件关系
                    eventRelation = new EventRelation(eventInfo);
                } else if (Constants.EVENT_INFO_CONSUME.equals(msg.getTags())) {
                    ConsumeEventInfo eventInfo = JSON.parseObject(msg.getBody(), ConsumeEventInfo.class);

                    // 保存事件信息
                    eventRelationService.save(eventInfo);

                    // 创建事件关系
                    eventRelation = new EventRelation(eventInfo);
                } else {
                    continue;
                }

                if (logger.isDebugEnabled()) {
                    logger.debug("监听到事件关系： {}", JSON.toJSONString(eventRelation));
                }

                // 保存事件关系
            } catch (Exception e) {
                logger.error("", e);
            }
            eventRelationService.save(eventRelation);
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }

}
