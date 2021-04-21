package com.tencent.tdmq.handlers.rocketmq.utils;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.rocketmq.common.message.MessageDecoder.CHARSET_UTF8;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.impl.MessageIdImpl;
import org.apache.pulsar.common.util.Murmur3_32Hash;
import org.apache.rocketmq.common.TopicFilterType;
import org.apache.rocketmq.common.UtilAll;
import org.apache.rocketmq.common.message.MessageAccessor;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.common.message.MessageDecoder;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.sysflag.MessageSysFlag;
import org.apache.rocketmq.store.MessageExtBrokerInner;

@Slf4j
public class CommonUtils {

    public static final String UNDERSCORE_CHAR = "_";
    public static final String PERCENTAGE_CHAR = "%";
    public static final String VERTICAL_LINE_CHAR = "｜";
    private static int MESSAGE_BYTEBUF_SIZE = 28;
    private static ThreadLocal<ByteBuffer> byteBufLocal = ThreadLocal
            .withInitial(() -> ByteBuffer.allocate(MESSAGE_BYTEBUF_SIZE));

    public static int newBrokerId(final InetSocketAddress address) {
        return Murmur3_32Hash.getInstance().makeHash((address.getHostString() + address.getPort()).getBytes(UTF_8));
    }

    public static String createMessageId(final long ledgerId, final long entryId, final long partitionId,
            final int batchNum) {
        ByteBuffer input = byteBufLocal.get();
        input.clear();
        input.putLong(ledgerId);
        input.putLong(entryId);
        input.putLong(partitionId);
        input.putInt(batchNum);
        return UtilAll.bytes2string(input.array());
    }

    public static MessageIdImpl decodeMessageId(final String msgId) {
        byte[] data = UtilAll.string2bytes(msgId);
        ByteBuffer bb = ByteBuffer.wrap(data);
        long ledgerId = bb.getLong();
        long entryId = bb.getLong();
        long partitionId = bb.getLong();
        return new MessageIdImpl(ledgerId, entryId, (int) partitionId);
    }

    public static int calMsgLength(int sysFlag, int bodyLength, int topicLength, int propertiesLength) {
        int bornhostLength = (sysFlag & MessageSysFlag.BORNHOST_V6_FLAG) == 0 ? 8 : 20;
        int storehostAddressLength = (sysFlag & MessageSysFlag.STOREHOSTADDRESS_V6_FLAG) == 0 ? 8 : 20;
        final int msgLen = 8 //tagsCode
                + 4 //TOTALSIZE
                + 4 //MAGICCODE
                + 4 //BODYCRC
                + 4 //QUEUEID
                + 4 //FLAG
                + 8 //QUEUEOFFSET
                + 8 //PHYSICALOFFSET
                + 4 //SYSFLAG
                + 8 //BORNTIMESTAMP
                + bornhostLength //BORNHOST
                + 8 //STORETIMESTAMP
                + storehostAddressLength //STOREHOSTADDRESS
                + 4 //RECONSUMETIMES
                + 8 //Prepared Transaction Offset
                + 4 + (bodyLength > 0 ? bodyLength : 0) //BODY
                + 1 + topicLength //TOPIC
                + 2 + (propertiesLength > 0 ? propertiesLength : 0) //propertiesLength
                + 0;
        return msgLen;
    }

    public static MessageExtBrokerInner messageTimeup(MessageExt msgExt) {
        MessageExtBrokerInner msgInner = new MessageExtBrokerInner();
        msgInner.setBody(msgExt.getBody());
        msgInner.setFlag(msgExt.getFlag());
        MessageAccessor.setProperties(msgInner, msgExt.getProperties());

        TopicFilterType topicFilterType = MessageExt.parseTopicFilterType(msgInner.getSysFlag());
        long tagsCodeValue =
                MessageExtBrokerInner.tagsString2tagsCode(topicFilterType, msgInner.getTags());
        msgInner.setTagsCode(tagsCodeValue);
        msgInner.setPropertiesString(MessageDecoder.messageProperties2String(msgExt.getProperties()));

        msgInner.setSysFlag(msgExt.getSysFlag());
        msgInner.setBornTimestamp(msgExt.getBornTimestamp());
        msgInner.setBornHost(msgExt.getBornHost());
        msgInner.setStoreHost(msgExt.getStoreHost());
        msgInner.setReconsumeTimes(msgExt.getReconsumeTimes());

        msgInner.setWaitStoreMsgOK(false);
        MessageAccessor.clearProperty(msgInner, MessageConst.PROPERTY_DELAY_TIME_LEVEL);

        msgInner.setTopic(msgInner.getProperty(MessageConst.PROPERTY_REAL_TOPIC));

        String queueIdStr = msgInner.getProperty(MessageConst.PROPERTY_REAL_QUEUE_ID);
        int queueId = Integer.parseInt(queueIdStr);
        msgInner.setQueueId(queueId);

        return msgInner;
    }

    public static MessageExt decode(
            java.nio.ByteBuffer byteBuffer, final MessageIdImpl messageId, final boolean readBody,
            final boolean deCompressBody) {
        return decode(byteBuffer, messageId, readBody, deCompressBody, false);
    }

    public static MessageExt decode(
            java.nio.ByteBuffer byteBuffer, final MessageIdImpl messageId, final boolean readBody,
            final boolean deCompressBody,
            final boolean isClient) {
        try {

            MessageExt msgExt = new MessageExt();

            long tagCode = byteBuffer.getLong();
            // 1 TOTALSIZE
            int storeSize = byteBuffer.getInt();
            msgExt.setStoreSize(storeSize);

            // 2 MAGICCODE
            byteBuffer.getInt();

            // 3 BODYCRC
            int bodyCRC = byteBuffer.getInt();
            msgExt.setBodyCRC(bodyCRC);

            // 4 QUEUEID
            int queueId = byteBuffer.getInt();
            msgExt.setQueueId(queueId);

            // 5 FLAG
            int flag = byteBuffer.getInt();
            msgExt.setFlag(flag);

            // 6 QUEUEOFFSET
            long queueOffset = byteBuffer.getLong();
            msgExt.setQueueOffset(queueOffset);

            // 7 PHYSICALOFFSET
            long physicOffset = byteBuffer.getLong();
            msgExt.setCommitLogOffset(physicOffset);

            // 8 SYSFLAG
            int sysFlag = byteBuffer.getInt();
            msgExt.setSysFlag(sysFlag);

            // 9 BORNTIMESTAMP
            long bornTimeStamp = byteBuffer.getLong();
            msgExt.setBornTimestamp(bornTimeStamp);

            // 10 BORNHOST
            int bornhostIPLength = (sysFlag & MessageSysFlag.BORNHOST_V6_FLAG) == 0 ? 4 : 16;
            byte[] bornHost = new byte[bornhostIPLength];
            byteBuffer.get(bornHost, 0, bornhostIPLength);
            int port = byteBuffer.getInt();
            msgExt.setBornHost(new InetSocketAddress(InetAddress.getByAddress(bornHost), port));

            // 11 STORETIMESTAMP
            long storeTimestamp = byteBuffer.getLong();
            msgExt.setStoreTimestamp(storeTimestamp);

            // 12 STOREHOST
            int storehostIPLength = (sysFlag & MessageSysFlag.STOREHOSTADDRESS_V6_FLAG) == 0 ? 4 : 16;
            byte[] storeHost = new byte[storehostIPLength];
            byteBuffer.get(storeHost, 0, storehostIPLength);
            port = byteBuffer.getInt();
            msgExt.setStoreHost(new InetSocketAddress(InetAddress.getByAddress(storeHost), port));

            // 13 RECONSUMETIMES
            int reconsumeTimes = byteBuffer.getInt();
            msgExt.setReconsumeTimes(reconsumeTimes);

            // 14 Prepared Transaction Offset
            long preparedTransactionOffset = byteBuffer.getLong();
            msgExt.setPreparedTransactionOffset(preparedTransactionOffset);

            // 15 BODY
            int bodyLen = byteBuffer.getInt();
            if (bodyLen > 0) {
                if (readBody) {
                    byte[] body = new byte[bodyLen];
                    byteBuffer.get(body);

                    // uncompress body
                    if (deCompressBody
                            && (sysFlag & MessageSysFlag.COMPRESSED_FLAG) == MessageSysFlag.COMPRESSED_FLAG) {
                        body = UtilAll.uncompress(body);
                    }

                    msgExt.setBody(body);
                } else {
                    byteBuffer.position(byteBuffer.position() + bodyLen);
                }
            }

            // 16 TOPIC
            byte topicLen = byteBuffer.get();
            byte[] topic = new byte[(int) topicLen];
            byteBuffer.get(topic);
            msgExt.setTopic(new String(topic, CHARSET_UTF8));

            // 17 properties
            short propertiesLength = byteBuffer.getShort();
            if (propertiesLength > 0) {
                byte[] properties = new byte[propertiesLength];
                byteBuffer.get(properties);
                String propertiesString = new String(properties, CHARSET_UTF8);
                Map<String, String> map = MessageDecoder.string2messageProperties(propertiesString);
                MessageAccessor.setProperties(msgExt, map);
            }

            if (messageId != null) {
                String msgId = createMessageId(messageId.getLedgerId(), messageId.getEntryId(),
                        queueId, -1);
                msgExt.setMsgId(msgId);
            }

            return msgExt;
        } catch (Exception e) {
            byteBuffer.position(byteBuffer.limit());
        }

        return null;
    }

    public static ByteBuffer decode(ByteBuffer byteBuffer) {
        ByteBuffer slice = byteBuffer.slice();
        // 去除 tags 标记位的 8 个字节之后，将原先的 byteBuffer 返回
        Buffer buffer = slice.position(byteBuffer.position() + 8);
        return ((ByteBuffer) buffer).slice();
    }
}
