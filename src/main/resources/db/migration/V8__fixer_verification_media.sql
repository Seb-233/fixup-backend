-- FR-UC-23: los documentos de verificacion pasan por el mismo pipeline de media que las fotos de
-- solicitudes y el portafolio (subida -> confirmacion con validacion de formato/firma -> adjuntar),
-- en vez de aceptar un storage_key arbitrario que el cliente podia inventar sin que el backend
-- verificara jamas que el archivo existiera, le perteneciera al fixer o tuviera un formato valido.

ALTER TABLE media_assets DROP CONSTRAINT ck_media_purpose;
ALTER TABLE media_assets ADD CONSTRAINT ck_media_purpose
    CHECK (purpose IN ('FIXER_PORTFOLIO', 'REPAIR_REQUEST', 'FIXER_VERIFICATION'));

ALTER TABLE fixer_verification_documents DROP COLUMN storage_key;
ALTER TABLE fixer_verification_documents ADD COLUMN media_id UUID NOT NULL REFERENCES media_assets(id);
ALTER TABLE fixer_verification_documents
    ADD CONSTRAINT uq_fixer_verification_document_media UNIQUE (media_id);
