package com.softwaremill.mqperf.mq

import com.amazonaws.services.sqs.AmazonSQSAsyncClientBuilder
import com.amazonaws.services.sqs.buffered.AmazonSQSBufferedAsyncClient
import com.amazonaws.services.sqs.model.{
  CreateQueueRequest,
  DeleteMessageBatchRequest,
  DeleteMessageBatchRequestEntry,
  ReceiveMessageRequest,
  SendMessageBatchRequestEntry
}
import com.softwaremill.mqperf.config.{AWS, TestConfig}
import com.typesafe.scalalogging.StrictLogging

import java.net.InetAddress
import java.util.concurrent.atomic.AtomicLong

import scala.collection.JavaConverters._
import scala.collection.mutable.HashMap

class SqsFifoMq(testConfig: TestConfig) extends Mq with StrictLogging {
  private val asyncClient =
    AmazonSQSAsyncClientBuilder
      .standard()
      .withCredentials(AWS.CredentialProvider)
      .withRegion(AWS.DefaultRegion)
      .build()

  private val asyncBufferedClient = new AmazonSQSBufferedAsyncClient(asyncClient)

  private val qAttribs = HashMap(
    "FifoQueue"->"true", 
    "DeduplicationScope"->"messageGroup",
    "FifoThroughputLimit"->"perMessageGroupId").asJava

  private val createQueueRequest = 
    new CreateQueueRequest()
      .withQueueName("mqperf-test-queue.fifo")
      .withAttributes(qAttribs)
      
  private val queueUrl = asyncBufferedClient.createQueue(createQueueRequest).getQueueUrl

  private var msgGroupMaster = new AtomicLong(0)
  
  private val hostIp = InetAddress.getLocalHost.getHostAddress
  
  override type MsgId = String

  override def createSender() =
    new MqSender {
      override def send(msgs: List[String]) = {
        val msgGroup = msgGroupMaster.incrementAndGet
        var msgDedupId = 0L
        asyncClient.sendMessageBatch(
          queueUrl,
          msgs.zipWithIndex.map { case (m, i) => 
            msgDedupId += 1
            new SendMessageBatchRequestEntry(i.toString, m)
              .withMessageGroupId(s"${hostIp}-${msgGroup}")
              .withMessageDeduplicationId(s"${hostIp}-${msgGroup}-${msgDedupId}")
          }.asJava
        )
        logger.debug(s"Sent ${msgDedupId} messages in group ${hostIp}-${msgGroup}")
      }
    }

  override def createReceiver() =
    new MqReceiver {
      override def receive(maxMsgCount: Int) = {
        asyncBufferedClient
          .receiveMessage(new ReceiveMessageRequest(queueUrl).withMaxNumberOfMessages(maxMsgCount))
          .getMessages
          .asScala
          .map(m => (m.getReceiptHandle, m.getBody))
          .toList
      }

      override def ack(ids: List[MsgId]) = {
        asyncBufferedClient.deleteMessageBatchAsync(
          new DeleteMessageBatchRequest(
            queueUrl,
            ids.zipWithIndex.map { case (m, i) => new DeleteMessageBatchRequestEntry(i.toString, m) }.asJava
          )
        )
      }
    }
}
