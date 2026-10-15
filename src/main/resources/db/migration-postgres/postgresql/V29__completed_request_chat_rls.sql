-- A completed CASH request may still have an outstanding debt. Its two
-- participants retain chat access while the owner resolves the balance.
DROP POLICY chat_messages_insert ON chat_messages;
CREATE POLICY chat_messages_insert ON chat_messages FOR INSERT WITH CHECK (
    sender_user_id = app_current_user_id()
    AND EXISTS (
        SELECT 1 FROM repair_requests r
        WHERE r.id = chat_messages.request_id
          AND r.status IN ('ASSIGNED', 'COMPLETED')
          AND r.assigned_fixer_user_id IS NOT NULL
          AND (r.owner_user_id = app_current_user_id()
               OR r.assigned_fixer_user_id = app_current_user_id())
    )
);
