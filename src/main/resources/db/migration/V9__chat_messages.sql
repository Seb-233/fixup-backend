-- FR-UC-24: chat privado atado a una solicitud ya asignada. Un repair_request en estado ASSIGNED
-- define de forma unica a sus dos participantes (owner_user_id, assigned_fixer_user_id), asi que el
-- hilo de mensajes cuelga directamente de repair_requests: no hace falta una entidad "conversation"
-- separada, y no cuelga de quotations porque una cotizacion es un registro efimero (solo importa
-- antes de la decision) mientras que el repair_request es el trabajo persistente sobre el que se
-- conversa. notification_status registra si el intento de notificacion push tuvo exito o no: nunca
-- bloquea el envio del mensaje (ver PushNotificationGateway), asi que solo existen dos valores
-- terminales -- el intento siempre ocurre en la misma transaccion, antes de insertar la fila.

CREATE TABLE chat_messages (
    id UUID PRIMARY KEY,
    request_id UUID NOT NULL REFERENCES repair_requests(id),
    sender_user_id UUID NOT NULL REFERENCES users(id),
    body VARCHAR(2000) NOT NULL,
    sent_at TIMESTAMP WITH TIME ZONE NOT NULL,
    notification_status VARCHAR(32) NOT NULL,
    CONSTRAINT ck_chat_message_body CHECK (length(trim(body)) > 0),
    CONSTRAINT ck_chat_message_notification_status CHECK (notification_status IN ('SENT', 'SKIPPED', 'FAILED'))
);

CREATE INDEX ix_chat_messages_request ON chat_messages (request_id, sent_at);
