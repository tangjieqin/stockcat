package com.stockcat.mapper;

import com.stockcat.domain.NotificationRecord;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface NotificationRecordMapper {

    @Insert("""
            INSERT INTO notification_record (alert_event_id, channel, status, provider_message_id,
                                             attempt_count, last_error_code, last_error_message, created_at)
            VALUES (#{alertEventId}, #{channel}, #{status}, #{providerMessageId},
                    #{attemptCount}, #{lastErrorCode}, #{lastErrorMessage}, now())
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(NotificationRecord record);

    @Select("""
            SELECT id, alert_event_id, channel, status, provider_message_id,
                   attempt_count, last_error_code, last_error_message, created_at, sent_at
            FROM notification_record WHERE id = #{id}
            """)
    NotificationRecord findById(@Param("id") Long id);

    @Select("""
            SELECT id, alert_event_id, channel, status, provider_message_id,
                   attempt_count, last_error_code, last_error_message, created_at, sent_at
            FROM notification_record WHERE alert_event_id = #{alertEventId} ORDER BY id
            """)
    List<NotificationRecord> selectByEventId(@Param("alertEventId") Long alertEventId);

    @Select("""
            <script>
            SELECT n.id, n.alert_event_id, n.channel, n.status, n.provider_message_id,
                   n.attempt_count, n.last_error_code, n.last_error_message, n.created_at, n.sent_at
            FROM notification_record n
            <where>
                <if test="status != null">AND n.status = #{status}</if>
            </where>
            ORDER BY n.created_at DESC LIMIT #{limit}
            </script>
            """)
    List<NotificationRecord> selectRecent(@Param("status") String status, @Param("limit") int limit);

    @Update("""
            UPDATE notification_record
            SET status = #{status},
                provider_message_id = #{providerMessageId},
                attempt_count = #{attemptCount},
                last_error_code = #{lastErrorCode},
                last_error_message = #{lastErrorMessage},
                sent_at = #{sentAt}
            WHERE id = #{id}
            """)
    int updateResult(@Param("id") Long id, @Param("status") String status,
                     @Param("providerMessageId") String providerMessageId,
                     @Param("attemptCount") Integer attemptCount,
                     @Param("lastErrorCode") String lastErrorCode,
                     @Param("lastErrorMessage") String lastErrorMessage,
                     @Param("sentAt") java.time.OffsetDateTime sentAt);
}
